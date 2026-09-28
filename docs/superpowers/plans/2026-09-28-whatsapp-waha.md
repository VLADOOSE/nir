# Чаты WhatsApp через свой шлюз WAHA — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Приём WhatsApp работает через бесплатный самостоятельно размещённый шлюз WAHA (Docker на нашем сервере): номер привязывается QR-кодом прямо в АИС («Система → WhatsApp»), сообщения приходят вебхуком в свою очередь в БД и догоняются из истории, входящие звонки видны строкой в переписке; Green-API остаётся запасным провайдером за переключателем.

**Architecture:** Общий цикл приёма (`WhatsappChatSync` + `WhatsappChatScheduler`, поток `whatsapp-chats`) отвязывается от Green-API интерфейсом `WhatsappSource`; реализации — `GreenApiSource` (прежнее поведение) и `WahaInboxSource`. WAHA шлёт подписанные HMAC вебхуки → `WahaWebhookController` кладёт сырое событие в таблицу `whatsapp_inbox` (`INSERT … ON CONFLICT DO NOTHING`) и сразу отвечает 200 → цикл берёт события из таблицы по времени, разбирает `WahaEventParser`'ом и пишет существующим `ChatIngestWriter`. Раз в минуту — статус сессии, раз в 10 мин и при подключении — догонка по истории WAHA, раз в сутки — уборка очереди.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Hibernate 6, Flyway (V22), `java.net.http`, Jackson, `javax.crypto` (HMAC-SHA512), JdbcTemplate; JUnit 5 + AssertJ + MockMvc на nirdb; Angular 21 standalone; Node 23 (dev-стаб WAHA); Docker (`devlikeapro/waha:gows-2026.9.1`).

**Spec:** `docs/superpowers/specs/2026-09-28-whatsapp-waha-design.md` — читать вместе с планом. Это ДЕЛЬТА к `docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md`: модель чатов, правила обращений, файлы, экраны «Чаты» и карточка обращения — оттуда.

## Global Constraints

- Java **17**: `instanceof`-шаблоны можно, **pattern matching в `switch` — нельзя**.
- Схема — **только новой миграцией `V22__whatsapp_inbox.sql`**; V1–V21 не трогать (CLAUDE.md §10). `ChatMessageType.CALL` миграции не требует (`chat_message.type` — `VARCHAR(20)` без CHECK).
- `whatsapp_inbox` — НЕ рыночная таблица (сырые события до разбора); рынок ставится при записи чата (`WHATSAPP_MARKET`), как сейчас.
- Фоновый поток ставит `MarketContext.set(<рынок из конфига>)` сам и чистит в `finally`; БД — в `@Transactional`-методах отдельного бина; сеть и файлы — **вне** транзакций (CLAUDE.md §6).
- Вебхук: **только с верной подписью** (`hex(HMAC-SHA512(сырое тело, WHATSAPP_WAHA_HMAC_KEY))`, сравнение за постоянное время); без подписи или с чужой — `401`, ничего не пишем, в лог — строка без тела. В запросе вебхука — ни разбора, ни сети: запись в очередь и `200`; БД недоступна — `503`.
- Ключ API WAHA и ключ HMAC — только из env; **в тексты ошибок, логи и строку состояния не попадают** ни ключи, ни адреса запросов (закреплено тестами). В строку состояния (её видит любой вошедший) — только свои тексты; чужие исключения — классом.
- Каждый HTTP-обмен со шлюзом — `sendAsync` с дедлайном на ВЕСЬ ответ, тело включительно (таймаут `HttpRequest` в JDK 17 снимается на заголовках — CLAUDE.md §14).
- Файлы: только личные чаты; размер известен и больше `chats.whatsapp.max-file-mb` (25) → `TOO_LARGE` **без скачивания**; скачивание — с обрывом на пределе; ссылку на файл берём **только с адреса самой WAHA** (ключ API уходит в заголовке).
- `DataIntegrityViolationException` целиком не глотать (дубль сообщения проверяется явно, дубль события — `ON CONFLICT`).
- Фронт: только токены (ни одного нового хекса), `@media` — последним блоком `styles`, мобильный ≤900px, тач-таргеты 40px, `cdr.detectChanges()` после async, **без нативного `await`** в экранах, картинки с `/api` — только `HttpClient` blob.
- Тесты бэкенда — `@SpringBootTest @Transactional` на nirdb (MockMvc — только из общего контекста: `MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity())`, **не** `@AutoConfigureMockMvc`/`@TestPropertySource` — каждый особый контекст съедает ещё 10 соединений nirdb, §14). Запуск `./gradlew …` и `psql` — **с `dangerouslyDisableSandbox: true`**. Гейт — `./gradlew cleanTest test` (0 падений) и `cd frontend && npm run build`.
- Секреты не печатать (`/tmp/*.pass`, `~/.config/ais/*`, ключи WAHA) — только через `$(cat …)`. Для живой WAHA — **тестовый** номер WhatsApp, не рабочий.
- Временные файлы — в scratchpad сессии (в командах плана — `/private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/c947d9ac-6874-4d66-8090-31f80e0e2615/scratchpad`; в другой сессии подставить её scratchpad из системного промпта), не в `/tmp` и не в репозиторий.
- Коммиты — на ветке `feature/whatsapp-waha`, каждый заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. `git push origin main` делает оператор. Git и gradlew — из корня (`cd /Users/vlad/IdeaProjects/AIS && …`).

## Карта файлов

Пути Java — относительно `src/main/java/com/vladoose/nir/` (тесты — `src/test/java/com/vladoose/nir/`).

| Файл | Задача | Ответственность |
|---|---|---|
| `integration/whatsapp/` ← из `integration/greenapi/`: `ChatKind`, `FileRef`, `IncomingFile`, `ParsedNotification`, `FileTooLargeException`, `WhatsappChatSync`, `WhatsappChatScheduler`, `WhatsappStatusHolder`; `GreenApi*Exception` → `Gateway*Exception` | 1 | общий цикл приёма, не знающий шлюза |
| `integration/whatsapp/WhatsappSource.java`, `WhatsappNotification.java`, `WhatsappProviders.java`, `WhatsappSourceConfig.java` | 1 | абстракция источника и выбор провайдера |
| `integration/greenapi/GreenApiSource.java` | 1 | Green-API за интерфейсом источника |
| `entity/ChatMessageType.java`, `service/ChatIngestWriter.java`, `service/LeadIntakeService.java` | 2 | звонок — служебная строка и обращение «Звонок в WhatsApp» |
| `integration/waha/WahaEventParser.java` | 3 | событие WAHA → `ParsedNotification` |
| `db/migration/V22__whatsapp_inbox.sql`, `entity/WhatsappInboxEvent.java`, `entity/WhatsappInboxStatus.java`, `repository/WhatsappInboxRepository.java`, `integration/waha/WahaInboxWriter.java`, `WahaSignature.java`, `WahaWebhookController.java`, `config/SecurityConfig.java`, `frontend/nginx.conf`, `application.yaml`, `build.gradle` | 4 | очередь и приём вебхука |
| `integration/whatsapp/GatewayHttp.java`, `LimitedBytes.java`, `integration/waha/WahaClient.java`, `WahaHttpClient.java`, `WahaSession.java`, `WahaContact.java` | 5 | HTTP к WAHA |
| `integration/waha/WahaSessionManager.java` | 6 | сессия: создание, статус, номер, QR, перезапуск, отвязка |
| `integration/waha/WahaInboxSource.java`, `WahaChatNames.java` | 7 | очередь → общий цикл, имена чатов, файлы |
| `integration/waha/WahaCatchUp.java`, `repository/ChatMessageRepository.java` | 8 | догонка по истории, уборка очереди |
| `dto/response/WhatsappSessionResponse.java`, `service/WhatsappSessionService.java`, `controller/WhatsappSessionController.java` | 9 | REST «Система → WhatsApp» |
| `scripts/waha-stub.mjs`, `scripts/stub-files.mjs`, `scripts/greenapi-stub.mjs` | 10 | dev-стаб WAHA |
| `frontend/src/app/pages/whatsapp/whatsapp.component.ts`, `shared/whatsapp-status.ts`, `shared/chat-message.component.ts`, `services/api.service.ts`, `app.routes.ts`, `app.config.ts`, `layout/layout.component.ts` | 11 | страница, тексты строки состояния, строка звонка |
| `docker-compose.yml`, `.env.example` | 12 | сервис WAHA под профилем |
| `src/test/resources/waha/*.json` | 13 | фикстуры с живой WAHA |
| `CLAUDE.md`, `DEPLOY.md`, `docs/PROGRESS.md` | 14 | документация |

---

### Task 1: Общий цикл приёма отвязан от Green-API

Чистый рефакторинг плюс одно новое правило: размер файла, известный из уведомления, больше предела → не качаем. Поведение Green-API не меняется — все его тесты остаются зелёными.

**Files:**
- Move: `integration/greenapi/{ChatKind,FileRef,IncomingFile,ParsedNotification,FileTooLargeException,WhatsappChatSync,WhatsappChatScheduler,WhatsappStatusHolder}.java` → `integration/whatsapp/`
- Move+rename: `integration/greenapi/GreenApiException.java` → `integration/whatsapp/GatewayException.java`; `GreenApiAuthException` → `GatewayAuthException`; `GreenApiQuotaException` → `GatewayQuotaException`
- Create: `integration/whatsapp/WhatsappNotification.java`, `WhatsappSource.java`, `WhatsappProviders.java`, `WhatsappSourceConfig.java`; `integration/greenapi/GreenApiSource.java`
- Rewrite: `integration/whatsapp/ChatKind.java`, `FileRef.java`, `GatewayException.java`, `GatewayAuthException.java`, `GatewayQuotaException.java`, `WhatsappStatusHolder.java`, `WhatsappChatSync.java`, `WhatsappChatScheduler.java`
- Modify: `integration/greenapi/GreenApiNotificationParser.java`, `GreenApiHttpClient.java` (импорты), `controller/ChatController.java`, `service/ChatIngestWriter.java` (импорты), `dto/response/WhatsappStatusResponse.java`, `src/main/resources/application.yaml`
- Move tests: `integration/greenapi/{WhatsappChatSyncTest,WhatsappChatSchedulerTest}.java` → `integration/whatsapp/`
- Create test: `integration/whatsapp/WhatsappSourceConfigTest.java`
- Modify tests: `integration/greenapi/FakeGreenApiClient.java`, `GreenApiHttpClientTest.java`, `GreenApiNotificationParserTest.java`, `chat/ChatApiTest.java`, `chat/ChatIngestWriterTest.java`, `chat/LeadChatTest.java` (импорты)

**Interfaces:**
- Consumes: существующие `ChatIngestWriter.write(Message, IncomingFile, List<IncomingLead.Item>)`, `applyDelete(Delete)`; `GreenApiClient`.
- Produces (пакет `com.vladoose.nir.integration.whatsapp`):
  - `public enum ChatKind { PERSONAL, PERSONAL_HIDDEN, GROUP; public static ChatKind of(String chatId) }` — `null` для «не чата».
  - `public record FileRef(String locator, String fileName, String mimeType, Long sizeBytes)` + конструктор `FileRef(String locator, String fileName, String mimeType)` (размер `null`).
  - `public record WhatsappNotification(long id, JsonNode body)`.
  - `public interface WhatsappSource { String name(); boolean isConfigured(); String configHint(); String waitingNote(); void housekeeping(WhatsappStatusHolder status); WhatsappNotification next(); ParsedNotification parse(WhatsappNotification n); void ack(WhatsappNotification n, String droppedReason); byte[] download(FileRef ref, long maxBytes); }`
  - `public final class WhatsappProviders { WAHA = "waha"; GREENAPI = "greenapi"; static String normalize(String) }`.
  - `public class GatewayException extends RuntimeException { GatewayException(int status, String message); int status() }`, `GatewayAuthException`, `GatewayQuotaException` (оба `extends GatewayException`, конструктор `(int, String)`).
  - `WhatsappStatusHolder`: `setState(String)`, `setNumber(String wid)`, `setSourceWarnings(List<String>)`, `messageSeen`, `quotaExceeded`, `messageDropped`, `recentDrops`, `setLastError`, `lastError`, `progress`, `sinceProgressMs`, `snapshot(boolean enabled, boolean configured)`; константы `WEBHOOK_URL_SET`, `INCOMING_OFF`, `OUTGOING_PHONE_OFF`, `QUOTA_EXCEEDED`, `MESSAGE_DROPPED`.
  - `WhatsappChatSync(WhatsappSource source, ChatIngestWriter writer, WestmedClient westmedClient, WhatsappStatusHolder status, String siteUrl, int maxFileMb)`; `boolean drain(int)`; пакетные `boolean process(WhatsappNotification)`, `static String reason(Throwable)`, `static boolean isInfrastructureFailure(Throwable)` — **публичный** (его зовёт вебхук в задаче 4), `MAX_ATTEMPTS = 3`, `DROP_FUSE = 3`.
  - `WhatsappChatScheduler(WhatsappChatSync sync, WhatsappSource source, WhatsappStatusHolder status, boolean enabled, String market, long authBackoffMs, long errorBackoffMs, long stallMs)`; `void tick()`, пакетный `void cycle()`, `WhatsappStatusResponse status()`.
  - `WhatsappSourceConfig.select(String provider, GreenApiSource greenApi)` (пакетный static; в задаче 7 получит третий параметр).
  - `com.vladoose.nir.integration.greenapi.GreenApiSource(GreenApiClient client, int receiveTimeoutSec, long stateRefreshMs, long settingsRefreshMs)`, `NAME = "greenapi"`.
  - `WhatsappStatusResponse.provider` (String).

- [ ] **Step 1: Перенести файлы и поправить пакеты и имена**

```bash
cd /Users/vlad/IdeaProjects/AIS
M=src/main/java/com/vladoose/nir/integration
T=src/test/java/com/vladoose/nir/integration
mkdir -p $M/whatsapp $T/whatsapp
for f in ChatKind FileRef IncomingFile ParsedNotification FileTooLargeException WhatsappChatSync WhatsappChatScheduler WhatsappStatusHolder; do
  git mv $M/greenapi/$f.java $M/whatsapp/$f.java
done
git mv $M/greenapi/GreenApiException.java $M/whatsapp/GatewayException.java
git mv $M/greenapi/GreenApiAuthException.java $M/whatsapp/GatewayAuthException.java
git mv $M/greenapi/GreenApiQuotaException.java $M/whatsapp/GatewayQuotaException.java
git mv $T/greenapi/WhatsappChatSyncTest.java $T/whatsapp/WhatsappChatSyncTest.java
git mv $T/greenapi/WhatsappChatSchedulerTest.java $T/whatsapp/WhatsappChatSchedulerTest.java
perl -pi -e 's/^package com\.vladoose\.nir\.integration\.greenapi;/package com.vladoose.nir.integration.whatsapp;/' $M/whatsapp/*.java $T/whatsapp/*.java
# имена исключений — во всём коде (grep без \b: BSD grep на macOS его не знает)
grep -rlE 'GreenApi(Auth|Quota)?Exception' src | xargs perl -pi -e 's/\bGreenApiAuthException\b/GatewayAuthException/g; s/\bGreenApiQuotaException\b/GatewayQuotaException/g; s/\bGreenApiException\b/GatewayException/g'
# импорты перенесённых классов у внешних пользователей (ChatController, ChatIngestWriter, тесты chat/*)
grep -rlE 'import com\.vladoose\.nir\.integration\.greenapi\.(ChatKind|FileRef|IncomingFile|ParsedNotification|FileTooLargeException|WhatsappChatSync|WhatsappChatScheduler|WhatsappStatusHolder);' src \
  | xargs perl -pi -e 's/import com\.vladoose\.nir\.integration\.greenapi\.(ChatKind|FileRef|IncomingFile|ParsedNotification|FileTooLargeException|WhatsappChatSync|WhatsappChatScheduler|WhatsappStatusHolder);/import com.vladoose.nir.integration.whatsapp.$1;/'
# классы greenapi, которые пользовались перенесёнными без импорта (были в одном пакете)
for f in $M/greenapi/GreenApiHttpClient.java $M/greenapi/GreenApiNotificationParser.java \
         $T/greenapi/FakeGreenApiClient.java $T/greenapi/GreenApiHttpClientTest.java $T/greenapi/GreenApiNotificationParserTest.java; do
  perl -0pi -e 's/^(package com\.vladoose\.nir\.integration\.greenapi;\n)/$1\nimport com.vladoose.nir.integration.whatsapp.*;\n/m' "$f"
done
git status --short src | head -40
```

Expected: 13 переименований (`R`), изменённые (`M`) `ChatController`, `ChatIngestWriter`, тесты `chat/*`, `FakeGreenApiClient`, `GreenApiHttpClient*`, `GreenApiNotificationParser*`.

- [ ] **Step 2: Переписать мелкие перенесённые классы**

`integration/whatsapp/ChatKind.java`:

```java
package com.vladoose.nir.integration.whatsapp;

/** Вид чата по chatId: @c.us — личный с номером, @lid — личный со скрытым номером, @g.us — группа. */
public enum ChatKind {
    PERSONAL, PERSONAL_HIDDEN, GROUP;

    /** null — это не чат: истории status@broadcast, каналы …@newsletter, рассылки …@broadcast. */
    public static ChatKind of(String chatId) {
        if (chatId == null || chatId.isBlank()) return null;
        if (chatId.endsWith("@g.us")) return GROUP;
        if (chatId.endsWith("@c.us")) return PERSONAL;
        if (chatId.endsWith("@lid")) return PERSONAL_HIDDEN;
        return null;
    }
}
```

`integration/whatsapp/FileRef.java`:

```java
package com.vladoose.nir.integration.whatsapp;

/**
 * Файл сообщения в уведомлении. locator — чем его скачать; понимает только источник, который его выдал (Green-API —
 * ссылка downloadUrl, WAHA — полный id сообщения). sizeBytes — размер, если шлюз его сообщил (Green-API — нет):
 * больше предела → не качаем вовсе (WAHA держит скачиваемый файл целиком в памяти, спека whatsapp-waha §6).
 */
public record FileRef(String locator, String fileName, String mimeType, Long sizeBytes) {

    public FileRef(String locator, String fileName, String mimeType) {
        this(locator, fileName, mimeType, null);
    }
}
```

`integration/whatsapp/GatewayException.java`:

```java
package com.vladoose.nir.integration.whatsapp;

/**
 * Ошибка шлюза WhatsApp (Green-API, WAHA). status 0 — сеть/конфиг. Текст — человеческий и БЕЗ адреса и ключей:
 * он уходит в строку состояния, которую видит любой вошедший (у Green-API токен стоит прямо в пути URL).
 */
public class GatewayException extends RuntimeException {

    private final int status;

    public GatewayException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
```

`integration/whatsapp/GatewayAuthException.java`:

```java
package com.vladoose.nir.integration.whatsapp;

/** Ключ шлюза отклонён (401/403), не задан или адрес не собирается — планировщик ставит длинную паузу. */
public class GatewayAuthException extends GatewayException {
    public GatewayAuthException(int status, String message) { super(status, message); }
}
```

`integration/whatsapp/GatewayQuotaException.java`:

```java
package com.vladoose.nir.integration.whatsapp;

/** Исчерпан лимит тарифа шлюза (Green-API: HTTP 466). */
public class GatewayQuotaException extends GatewayException {
    public GatewayQuotaException(int status, String message) { super(status, message); }
}
```

В `integration/whatsapp/ParsedNotification.java` поменять только первую строку javadoc интерфейса:

```java
/** Разобранное уведомление шлюза WhatsApp — Green-API или WAHA (спеки whatsapp-chats §2, §6.5; whatsapp-waha §2). */
```

`IncomingFile.java` и `FileTooLargeException.java` — только пакет (сделано в шаге 1).

- [ ] **Step 3: Новые типы источника**

`integration/whatsapp/WhatsappNotification.java`:

```java
package com.vladoose.nir.integration.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;

/** Уведомление из очереди источника: id — чем подтверждать (receiptId Green-API / строка whatsapp_inbox), body — само уведомление. */
public record WhatsappNotification(long id, JsonNode body) {}
```

`integration/whatsapp/WhatsappSource.java`:

```java
package com.vladoose.nir.integration.whatsapp;

/**
 * Откуда приходят уведомления WhatsApp (спека whatsapp-waha §3): Green-API (очередь сервиса) или WAHA (своя очередь
 * whatsapp_inbox). Общий цикл — WhatsappChatSync + WhatsappChatScheduler — от источника не зависит.
 * Все методы, кроме name/isConfigured, зовёт один поток приёма «whatsapp-chats».
 */
public interface WhatsappSource {

    /** Для строки состояния и страницы «Система → WhatsApp»: waha / greenapi. */
    String name();

    boolean isConfigured();

    /** Что не задано — текст для строки состояния (имена переменных, без значений). */
    String configHint();

    /** Где ждут непринятые сообщения, пока приём стоит, — для текстов строки состояния. */
    String waitingNote();

    /** Периодическое, до прохода по очереди: состояние подключения и номер; у WAHA ещё догонка и уборка. */
    void housekeeping(WhatsappStatusHolder status);

    /** Голова очереди; null — пусто. Та же голова приходит снова, пока её не подтвердят. */
    WhatsappNotification next();

    ParsedNotification parse(WhatsappNotification n);

    /** Принято (droppedReason == null) или пропущено как «ядовитое» (droppedReason — свой текст причины). */
    void ack(WhatsappNotification n, String droppedReason);

    /** Байты файла; больше maxBytes → FileTooLargeException; сбой — GatewayException. */
    byte[] download(FileRef ref, long maxBytes);
}
```

`integration/whatsapp/WhatsappProviders.java`:

```java
package com.vladoose.nir.integration.whatsapp;

import java.util.Locale;

/** Значения chats.whatsapp.provider (WHATSAPP_PROVIDER). */
public final class WhatsappProviders {

    public static final String WAHA = "waha";
    public static final String GREENAPI = "greenapi";

    private WhatsappProviders() {}

    /** « GreenAPI » → «greenapi»; null → "". */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }
}
```

`integration/whatsapp/WhatsappSourceConfig.java`:

```java
package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.integration.greenapi.GreenApiSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Какой источник уведомлений работает — chats.whatsapp.provider (спека whatsapp-waha §1, решение 2). Опечатка АИС
 * не роняет (как уронил бы @ConditionalOnProperty без подходящего бина): приём стоит с красной строкой — тот же
 * урок, что с опечаткой в WHATSAPP_MARKET.
 */
@Configuration
public class WhatsappSourceConfig {

    /** destroyMethod = "": бин — тот же объект, что источник-компонент, закрывать его вторым именем незачем. */
    @Bean(destroyMethod = "")
    @Primary
    public WhatsappSource whatsappSource(@Value("${chats.whatsapp.provider:greenapi}") String provider,
                                         GreenApiSource greenApi) {
        return select(provider, greenApi);
    }

    static WhatsappSource select(String provider, GreenApiSource greenApi) {
        if (WhatsappProviders.GREENAPI.equals(WhatsappProviders.normalize(provider))) return greenApi;
        return new UnknownProviderSource(provider);
    }

    /** Источник для опечатки в WHATSAPP_PROVIDER: ничего не принимает, строка состояния объясняет. */
    static final class UnknownProviderSource implements WhatsappSource {

        private final String raw;

        UnknownProviderSource(String raw) { this.raw = raw == null ? "" : raw; }

        @Override public String name() { return WhatsappProviders.normalize(raw); }
        @Override public boolean isConfigured() { return false; }
        @Override public String configHint() {
            return "WHATSAPP_PROVIDER: неизвестный провайдер «" + raw + "» — приём остановлен (нужно waha или greenapi)";
        }
        @Override public String waitingNote() { return "сообщения ждут на стороне шлюза"; }
        @Override public void housekeeping(WhatsappStatusHolder status) { }
        @Override public WhatsappNotification next() { return null; }
        @Override public ParsedNotification parse(WhatsappNotification n) { return new ParsedNotification.Skip("нет источника"); }
        @Override public void ack(WhatsappNotification n, String droppedReason) { }
        @Override public byte[] download(FileRef ref, long maxBytes) { throw new GatewayException(0, configHint()); }
    }
}
```

- [ ] **Step 4: Переписать `WhatsappStatusHolder` (настройки Green-API уходят в источник)**

Полностью заменить `integration/whatsapp/WhatsappStatusHolder.java`:

```java
package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Состояние подключения WhatsApp для UI (спеки whatsapp-chats §7, whatsapp-waha §7). Пишут цикл приёма и источник
 * (поток «whatsapp-chats»), читают контроллеры — поля volatile, список предупреждений заменяется целиком.
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
    private volatile int droppedCount;
    /** Предупреждения источника (Green-API — настройки инстанса); заменяются целиком. */
    private volatile List<String> sourceWarnings = List.of();
    /** Когда проход приёма в последний раз продвинулся (ответ шлюза, записанное уведомление). */
    private volatile long progressAt = System.currentTimeMillis();

    public void setState(String s) { state = s == null || s.isBlank() ? null : s; }

    /** «77000000001@c.us» / «77000000001:12@s.whatsapp.net» / «77000000001» → «77000000001»; пусто → null. */
    public void setNumber(String wid) {
        String w = wid == null ? "" : wid.strip();
        int at = w.indexOf('@');
        String user = at < 0 ? w : w.substring(0, at);
        int device = user.indexOf(':');
        String digits = device < 0 ? user : user.substring(0, device);
        number = digits.isEmpty() ? null : digits;
    }

    public void setSourceWarnings(List<String> warnings) { sourceWarnings = List.copyOf(warnings); }

    public void messageSeen(OffsetDateTime at) {
        if (at != null && (lastMessageAt == null || at.isAfter(lastMessageAt))) lastMessageAt = at;
    }

    public void quotaExceeded() { quotaExceededAt = OffsetDateTime.now(); }

    /** Пишет один поток приёма — счёт без гонок; через сутки тишины начинается заново. */
    public void messageDropped() {
        OffsetDateTime now = OffsetDateTime.now();
        if (droppedAt == null || droppedAt.isBefore(now.minus(RECENT))) droppedCount = 0;
        droppedCount++;
        droppedAt = now;
    }

    /** Пропущено «ядовитых» за сутки (счёт живёт с последнего пропуска). */
    public int recentDrops() {
        OffsetDateTime at = droppedAt;
        return at != null && at.isAfter(OffsetDateTime.now().minus(RECENT)) ? droppedCount : 0;
    }

    public void setLastError(String e) { lastError = e; }

    public String lastError() { return lastError; }

    public void progress() { progressAt = System.currentTimeMillis(); }

    public long sinceProgressMs() { return System.currentTimeMillis() - progressAt; }

    public WhatsappStatusResponse snapshot(boolean enabled, boolean configured) {
        WhatsappStatusResponse r = new WhatsappStatusResponse();
        r.setEnabled(enabled);
        r.setConfigured(configured);
        r.setState(state);
        r.setNumber(number);
        r.setLastMessageAt(lastMessageAt);
        r.setLastError(lastError);
        List<String> w = new ArrayList<>(sourceWarnings);
        OffsetDateTime cutoff = OffsetDateTime.now().minus(RECENT);
        if (quotaExceededAt != null && quotaExceededAt.isAfter(cutoff)) w.add(QUOTA_EXCEEDED);
        if (droppedAt != null && droppedAt.isAfter(cutoff)) {
            w.add(MESSAGE_DROPPED);
            r.setDroppedCount(droppedCount);
        }
        r.setWarnings(w);
        return r;
    }
}
```

В `dto/response/WhatsappStatusResponse.java` после поля `market` добавить:

```java
    /** Шлюз: waha / greenapi (или опечатка из WHATSAPP_PROVIDER — тогда configured=false и lastError объясняет). */
    private String provider;
```

- [ ] **Step 5: Адаптировать перенесённые тесты цикла и написать новые (красные)**

В `src/test/java/com/vladoose/nir/integration/whatsapp/WhatsappChatSyncTest.java`:

1. Импорты — после `import com.vladoose.nir.entity.*;` добавить:

```java
import com.vladoose.nir.integration.greenapi.FakeGreenApiClient;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiSource;
```

и к блоку `java.*`: `import java.time.OffsetDateTime;`.

2. Поля и `setUp` — заменить

```java
    FakeGreenApiClient fake;
    FakeWestmedClient westmed;
    WhatsappStatusHolder status;
```

на

```java
    FakeGreenApiClient fake;
    GreenApiSource source;
    FakeWestmedClient westmed;
    WhatsappStatusHolder status;
```

и в `setUp()` после `fake = new FakeGreenApiClient();` добавить строку `source = new GreenApiSource(fake, 5, 300_000, 3_600_000);`.

3. Фабрику `sync(ChatIngestWriter w)` заменить:

```java
    /** Предел файла — 1 МБ, чтобы тест «больше предела» не гонял 25 МБ. */
    private WhatsappChatSync sync(ChatIngestWriter w) {
        return new WhatsappChatSync(source, w, westmed, status, "https://westmed.kz/", 1);
    }
```

4. Перед закрывающей скобкой класса добавить тест и вспомогательный источник:

```java
    /**
     * Размер сообщён шлюзом (WAHA — proto-поле fileLength) и больше предела — не качаем вовсе: WAHA тянет скачиваемый
     * файл целиком в память (спека whatsapp-waha §6).
     */
    @Test
    void fileWithKnownSizeOverLimitIsNotDownloaded() {
        String chat = personal();
        ParsedNotification.Message m = new ParsedNotification.Message(GreenApiJson.ACCOUNT, chat, ChatKind.PERSONAL,
                "+" + chat.substring(0, 11), "Айгерим", "Айгерим", LeadDirection.IN, id(), OffsetDateTime.now().minusMinutes(5),
                ChatMessageType.DOCUMENT, "Смета", new FileRef("loc-1", "big.pdf", "application/pdf", 2L * 1024 * 1024),
                null, false);
        SingleMessageSource one = new SingleMessageSource(m);

        new WhatsappChatSync(one, writer, westmed, status, "https://westmed.kz", 1).drain(10);

        assertThat(one.downloads).isEmpty();
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(lastMessage(chat).getId())))
                .singleElement().extracting(ChatAttachmentMeta::notStoredReason).isEqualTo(AttachmentNotStoredReason.TOO_LARGE);
    }

    /** Источник из одного готового сообщения — для правил цикла, которые от шлюза не зависят. */
    static final class SingleMessageSource implements WhatsappSource {
        final ParsedNotification.Message message;
        final List<String> downloads = new ArrayList<>();
        boolean acked;

        SingleMessageSource(ParsedNotification.Message message) { this.message = message; }

        @Override public String name() { return "test"; }
        @Override public boolean isConfigured() { return true; }
        @Override public String configHint() { return ""; }
        @Override public String waitingNote() { return "сообщения ждут в тесте"; }
        @Override public void housekeeping(WhatsappStatusHolder status) { }
        @Override public WhatsappNotification next() { return acked ? null : new WhatsappNotification(1, null); }
        @Override public ParsedNotification parse(WhatsappNotification n) { return message; }
        @Override public void ack(WhatsappNotification n, String droppedReason) { acked = true; }
        @Override public byte[] download(FileRef ref, long maxBytes) {
            downloads.add(ref.locator());
            return new byte[]{1};
        }
    }
```

В `src/test/java/com/vladoose/nir/integration/whatsapp/WhatsappChatSchedulerTest.java`:

1. Импорты — после `import com.vladoose.nir.entity.Market;` добавить:

```java
import com.vladoose.nir.integration.greenapi.FakeGreenApiClient;
import com.vladoose.nir.integration.greenapi.GreenApiJson;
import com.vladoose.nir.integration.greenapi.GreenApiSettings;
import com.vladoose.nir.integration.greenapi.GreenApiSource;
```

2. Заменить блок полей, `setUp`, `sync()` и обе фабрики `scheduler(...)`:

```java
    FakeGreenApiClient fake;
    GreenApiSource source;
    WhatsappStatusHolder status;

    @BeforeEach
    void setUp() {
        fake = new FakeGreenApiClient();
        source = new GreenApiSource(fake, 5, 300_000, 3_600_000);
        status = new WhatsappStatusHolder();
    }

    @AfterEach void tearDown() { MarketContext.clear(); }

    private WhatsappChatSync sync() {
        return new WhatsappChatSync(source, writer, new FakeWestmedClient(), status, "https://westmed.kz", 25);
    }

    private WhatsappChatScheduler scheduler(boolean enabled) { return scheduler(enabled, 300_000); }

    private WhatsappChatScheduler scheduler(boolean enabled, long stallMs) {
        return new WhatsappChatScheduler(sync(), source, status, enabled, "KZ", 600_000, 30_000, stallMs);
    }
```

3. В `unknownMarketStopsIntakeInsteadOfWritingToRf` конструктор заменить на

```java
        WhatsappChatScheduler s = new WhatsappChatScheduler(sync(), source, status, true, "KZZ", 600_000, 30_000, 300_000);
```

4. Добавить тест:

```java
    @Test
    void statusTellsWhichGatewayIsActive() {
        assertThat(scheduler(true).status().getProvider()).isEqualTo("greenapi");
    }
```

Создать `src/test/java/com/vladoose/nir/integration/whatsapp/WhatsappSourceConfigTest.java`:

```java
package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.integration.greenapi.FakeGreenApiClient;
import com.vladoose.nir.integration.greenapi.GreenApiSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WhatsappSourceConfigTest {

    final GreenApiSource green = new GreenApiSource(new FakeGreenApiClient(), 5, 300_000, 3_600_000);

    @Test
    void greenApiIsChosenWhateverTheCaseAndSpaces() {
        assertThat(WhatsappSourceConfig.select(" GreenAPI ", green)).isSameAs(green);
    }

    /** Опечатка не роняет АИС и не превращается молча в другой шлюз: приём стоит, строка объясняет. */
    @Test
    void typoStopsIntakeWithHint() {
        WhatsappSource s = WhatsappSourceConfig.select("grenapi", green);

        assertThat(s.isConfigured()).isFalse();
        assertThat(s.configHint()).contains("WHATSAPP_PROVIDER").contains("grenapi");
        assertThat(s.next()).isNull();
    }
}
```

- [ ] **Step 6: Убедиться, что тесты красные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava` (`dangerouslyDisableSandbox: true`)
Expected: FAIL — компиляция: нет `GreenApiSource`; `WhatsappChatSync`/`WhatsappChatScheduler` ещё старые (зовут удалённый `settingsChecked` и ждут `GreenApiClient`) — их переписывают шаги 8–9.

- [ ] **Step 7: `GreenApiSource`**

Создать `integration/greenapi/GreenApiSource.java`:

```java
package com.vladoose.nir.integration.greenapi;

import com.vladoose.nir.integration.whatsapp.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Green-API как источник (спека whatsapp-chats-green-api): очередь сервиса, long-poll; раз в 5 мин — состояние
 * инстанса, раз в час — настройки (номер, адрес вебхука, флаги уведомлений). Поведение то же, что до выделения
 * источника (спека whatsapp-waha §1, решение 2 — Green-API остаётся запасным).
 */
@Component
public class GreenApiSource implements WhatsappSource {

    public static final String NAME = WhatsappProviders.GREENAPI;

    private final GreenApiClient client;
    private final int receiveTimeoutSec;
    private final long stateRefreshMs;
    private final long settingsRefreshMs;
    private long nextStateCheck;
    private long nextSettingsCheck;

    @Autowired
    public GreenApiSource(GreenApiClient client,
                          @Value("${chats.whatsapp.receive-timeout-s:20}") int receiveTimeoutSec,
                          @Value("${chats.whatsapp.state-refresh-ms:300000}") long stateRefreshMs,
                          @Value("${chats.whatsapp.settings-refresh-ms:3600000}") long settingsRefreshMs) {
        this.client = client;
        this.receiveTimeoutSec = receiveTimeoutSec;
        this.stateRefreshMs = stateRefreshMs;
        this.settingsRefreshMs = settingsRefreshMs;
    }

    @Override public String name() { return NAME; }

    @Override public boolean isConfigured() { return client.isConfigured(); }

    @Override public String configHint() {
        return "не заданы учётные данные Green-API (WHATSAPP_API_URL / WHATSAPP_ID_INSTANCE / WHATSAPP_API_TOKEN)";
    }

    @Override public String waitingNote() { return "сообщения ждут в очереди Green-API до суток"; }

    @Override
    public void housekeeping(WhatsappStatusHolder status) {
        long now = System.currentTimeMillis();
        if (now >= nextSettingsCheck) {
            GreenApiSettings s = client.settings();
            status.setNumber(s.wid());
            status.setSourceWarnings(warnings(s));
            nextSettingsCheck = now + settingsRefreshMs;
        }
        if (now >= nextStateCheck) {
            status.setState(client.state());
            nextStateCheck = now + stateRefreshMs;
        }
    }

    /** Без этих настроек приём молча неполный (спека whatsapp-chats §7). */
    static List<String> warnings(GreenApiSettings s) {
        List<String> w = new ArrayList<>();
        if (s.webhookUrl() != null && !s.webhookUrl().isBlank()) w.add(WhatsappStatusHolder.WEBHOOK_URL_SET);
        if (!s.incomingWebhook()) w.add(WhatsappStatusHolder.INCOMING_OFF);
        if (!s.outgoingMessageWebhook()) w.add(WhatsappStatusHolder.OUTGOING_PHONE_OFF);
        return w;
    }

    @Override
    public WhatsappNotification next() {
        GreenApiReceived r = client.receive(receiveTimeoutSec);
        return r == null ? null : new WhatsappNotification(r.receiptId(), r.body());
    }

    @Override
    public ParsedNotification parse(WhatsappNotification n) { return GreenApiNotificationParser.parse(n.body()); }

    /** Green-API различий «принято/пропущено» не знает: в обоих случаях уведомление удаляется из его очереди. */
    @Override
    public void ack(WhatsappNotification n, String droppedReason) { client.delete(n.id()); }

    @Override
    public byte[] download(FileRef ref, long maxBytes) { return client.download(ref.locator(), maxBytes); }
}
```

В `integration/greenapi/GreenApiNotificationParser.java`:
- удалить метод `static ChatKind kindOf(String chatId) {…}` целиком вместе с его javadoc;
- строку `ChatKind kind = kindOf(chatId);` заменить на `ChatKind kind = ChatKind.of(chatId);`;
- первую строку javadoc класса оставить.

- [ ] **Step 8: Переписать `WhatsappChatSync`**

Полностью заменить `integration/whatsapp/WhatsappChatSync.java`:

```java
package com.vladoose.nir.integration.whatsapp;

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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Проход по очереди источника (спеки whatsapp-chats §6, whatsapp-waha §3): взять голову → (скачать файл, найти бренды
 * корзины — ВНЕ транзакции) → записать (ChatIngestWriter, своя транзакция) → подтвердить. Подтверждаем ТОЛЬКО после
 * записи: сбой посередине → уведомление придёт снова, дубль отсечёт writer. Сам НЕ транзакционный; MarketContext
 * ставит вызывающий (WhatsappChatScheduler). Шлюза не знает — всё шлюзовое за WhatsappSource.
 */
@Service
public class WhatsappChatSync {

    private static final Logger log = LoggerFactory.getLogger(WhatsappChatSync.class);
    static final int MAX_ATTEMPTS = 3;
    /**
     * Столько «ядовитых» ЗА СУТКИ — это уже не битое уведомление, а поломка (регрессия, ломающая запись): дальше не
     * пропускаем, стоим с красной строкой — новые сообщения ждут в очереди. Именно за сутки, а не подряд: успешные
     * служебные уведомления между «ядовитыми» (статусы, группы) сбрасывали бы серию, и регрессия, ломающая только
     * личные сообщения, выкидывала бы очередь по одному (перепроверка ревью 2026-09-28).
     */
    static final int DROP_FUSE = 3;
    /** Предел глубины цепочки причин — от циклов, которые не самоссылка. */
    private static final int MAX_CAUSE_DEPTH = 32;
    /** Классы SQLSTATE: 08 соединение, 40 откат (deadlock), 53 ресурсы (диск/память), 57 вмешательство, 58 система. */
    private static final Set<String> INFRA_SQLSTATE_CLASSES = Set.of("08", "40", "53", "57", "58");

    private final WhatsappSource source;
    private final ChatIngestWriter writer;
    private final WestmedClient westmedClient;
    private final WhatsappStatusHolder status;
    private final String siteUrl;
    private final long maxFileBytes;
    /** id уведомления → сколько раз подряд не приняли. В памяти: после рестарта счёт с нуля — это ≤ 2 лишние попытки. */
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();
    /**
     * Состояния «база недоступна» и «приём остановлен» повторяются каждым проходом: в лог — один раз на вход в
     * состояние, иначе тысячи трасс в сутки. Сбрасывает успешное уведомление. Пишет один поток приёма.
     */
    private boolean infraLogged;
    private boolean haltLogged;

    public WhatsappChatSync(WhatsappSource source, ChatIngestWriter writer, WestmedClient westmedClient,
                            WhatsappStatusHolder status,
                            @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl,
                            @Value("${chats.whatsapp.max-file-mb:25}") int maxFileMb) {
        this.source = source;
        this.writer = writer;
        this.westmedClient = westmedClient;
        this.status = status;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.maxFileBytes = maxFileMb * 1024L * 1024L;
    }

    /** До maxNotifications уведомлений или до пустой очереди. false — проход оборван сбоем уведомления (нужна пауза). */
    public boolean drain(int maxNotifications) {
        for (int i = 0; i < maxNotifications; i++) {
            WhatsappNotification n = source.next();
            status.progress();
            if (n == null) return true;
            if (!process(n)) return false;
            status.progress();
        }
        return true;
    }

    /**
     * Одно уведомление. false — не принято и оставлено в очереди (источник отдаст его снова). Сбой базы/диска — не
     * вина уведомления: попытку не считаем. «Ядовитое» (упало MAX_ATTEMPTS раз) пропускаем, чтобы оно не забило
     * голову очереди навсегда, — но не больше DROP_FUSE за сутки.
     */
    boolean process(WhatsappNotification n) {
        int attempt = failures.getOrDefault(n.id(), 0) + 1;
        String dropped = null;
        try {
            handle(source.parse(n), attempt);
            infraLogged = false;
            haltLogged = false;
        } catch (RuntimeException e) {
            if (isInfrastructureFailure(e)) {
                status.setLastError("приём приостановлен: база данных недоступна (" + e.getClass().getSimpleName()
                        + ") — " + source.waitingNote());
                if (!infraLogged) {
                    log.warn("WhatsApp: база данных недоступна — уведомление {} ждёт в очереди", n.id(), e);
                    infraLogged = true;
                }
                return false;
            }
            String reason = reason(e);
            if (attempt < MAX_ATTEMPTS) {
                failures.put(n.id(), attempt);
                status.setLastError("сообщение не принято (попытка " + attempt + " из " + MAX_ATTEMPTS + "): " + reason);
                log.warn("WhatsApp: уведомление {} не принято (попытка {} из {})", n.id(), attempt, MAX_ATTEMPTS, e);
                return false;
            }
            if (status.recentDrops() >= DROP_FUSE) {
                failures.put(n.id(), attempt);
                status.setLastError("приём остановлен: за сутки не записались " + status.recentDrops()
                        + " сообщения — нужна проверка; " + source.waitingNote() + ". Причина: " + reason);
                if (!haltLogged) {
                    log.error("WhatsApp: за сутки не записались {} уведомления — приём остановлен, {} оставлено в очереди",
                            status.recentDrops(), n.id(), e);
                    haltLogged = true;
                }
                return false;
            }
            status.messageDropped();
            status.setLastError("сообщение пропущено после " + MAX_ATTEMPTS + " попыток: " + reason);
            log.warn("WhatsApp: уведомление {} не принято {} раз подряд — пропущено", n.id(), attempt, e);
            dropped = reason;
        }
        failures.remove(n.id());
        source.ack(n, dropped);
        return true;
    }

    /**
     * Текст для строки состояния, которую видит любой вошедший: свои сообщения шлюза — как есть (собраны без адреса
     * и ключей), чужие исключения — только класс: в их тексте бывают SQL, значения и адреса.
     */
    static String reason(Throwable e) {
        if (e instanceof GatewayException) return e.getMessage();
        return "внутренняя ошибка (" + e.getClass().getSimpleName() + ") — подробности в логе сервера";
    }

    /** Сбой инфраструктуры (база, диск, соединение), а не битое уведомление — по всей цепочке причин. */
    public static boolean isInfrastructureFailure(Throwable e) {
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
        // Skip — только подтвердить
    }

    private IncomingFile fetchFile(ParsedNotification.Message m, int attempt) {
        FileRef ref = m.file();
        if (ref == null) return null;
        if (m.kind() == ChatKind.GROUP) return IncomingFile.notStored(ref, AttachmentNotStoredReason.GROUP);
        if (ref.locator() == null) return IncomingFile.notStored(ref, AttachmentNotStoredReason.DOWNLOAD_FAILED);
        // размер известен заранее — большой файл не качаем вовсе (WAHA держит скачиваемое целиком в памяти)
        if (ref.sizeBytes() != null && ref.sizeBytes() > maxFileBytes) {
            return IncomingFile.notStored(ref, AttachmentNotStoredReason.TOO_LARGE);
        }
        try {
            return IncomingFile.stored(ref, source.download(ref, maxFileBytes));
        } catch (FileTooLargeException e) {
            return IncomingFile.notStored(ref, AttachmentNotStoredReason.TOO_LARGE);
        } catch (GatewayException e) {
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

- [ ] **Step 9: Переписать `WhatsappChatScheduler`**

Полностью заменить `integration/whatsapp/WhatsappChatScheduler.java`:

```java
package com.vladoose.nir.integration.whatsapp;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.WhatsappStatusResponse;
import com.vladoose.nir.entity.Market;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Приём WhatsApp (спеки whatsapp-chats §6.1, whatsapp-waha §3): тик раз в секунду ставит проход в СВОЙ однопоточный
 * экзекьютор «whatsapp-chats» — long-poll Green-API держит поток до 20 с, общий scheduling-1 занимать нельзя. Рынок
 * ставится ЯВНО и чистится в finally (§6 CLAUDE.md). Проход: обслуживание источника (состояние, номер; у WAHA ещё
 * догонка и уборка) → очередь. Паузы: отказ ключа — 10 мин, прочие сбои — 30 с.
 */
@Component
public class WhatsappChatScheduler {

    private static final Logger log = LoggerFactory.getLogger(WhatsappChatScheduler.class);
    static final int MAX_PER_DRAIN = 200;

    private final WhatsappChatSync sync;
    private final WhatsappSource source;
    private final WhatsappStatusHolder status;
    private final boolean enabled;
    /** null — WHATSAPP_MARKET не распознан: приём стоит (раньше опечатка молча превращалась в RF). */
    private final Market market;
    private final String marketRaw;
    private final long authBackoffMs;
    private final long errorBackoffMs;
    /** Проход без продвижения дольше этого — строка состояния говорит «приём не продвигается». */
    private final long stallMs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "whatsapp-chats");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long pausedUntil;
    private volatile boolean credentialsWarned;
    private volatile boolean marketWarned;

    public WhatsappChatScheduler(WhatsappChatSync sync, WhatsappSource source, WhatsappStatusHolder status,
                                 @Value("${chats.whatsapp.enabled:false}") boolean enabled,
                                 @Value("${chats.whatsapp.market:KZ}") String market,
                                 @Value("${chats.whatsapp.auth-backoff-ms:600000}") long authBackoffMs,
                                 @Value("${chats.whatsapp.error-backoff-ms:30000}") long errorBackoffMs,
                                 @Value("${chats.whatsapp.stall-ms:300000}") long stallMs) {
        this.sync = sync;
        this.source = source;
        this.status = status;
        this.enabled = enabled;
        this.market = parseMarket(market);
        this.marketRaw = market;
        this.authBackoffMs = authBackoffMs;
        this.errorBackoffMs = errorBackoffMs;
        this.stallMs = stallMs;
    }

    static Market parseMarket(String raw) {
        if (raw == null) return null;
        try {
            return Market.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        status.progress();
        if (market == null) {
            status.setLastError("WHATSAPP_MARKET: неизвестный рынок «" + marketRaw + "» — приём остановлен (нужно KZ или RF)");
            if (!marketWarned) {
                log.error("WhatsApp: {}", status.lastError());
                marketWarned = true;
            }
            return;
        }
        if (!source.isConfigured()) {
            status.setLastError(source.configHint());
            if (!credentialsWarned) {
                log.warn("WhatsApp: {}", status.lastError());
                credentialsWarned = true;
            }
            return;
        }
        if (System.currentTimeMillis() < pausedUntil) return;   // пауза: lastError уже объясняет
        MarketContext.set(market);
        try {
            source.housekeeping(status);
            if (sync.drain(MAX_PER_DRAIN)) {
                status.setLastError(null);
            } else {
                pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            }
        } catch (GatewayAuthException e) {
            pausedUntil = System.currentTimeMillis() + authBackoffMs;
            if (changed(e.getMessage() + " — повтор через " + Math.max(1, authBackoffMs / 60_000) + " мин")) {
                log.warn("WhatsApp: {}", status.lastError());
            }
        } catch (GatewayQuotaException e) {
            status.quotaExceeded();
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            changed(e.getMessage());
        } catch (GatewayException e) {
            // свой текст без адреса; трасса ни о чём не скажет — сеть или ответ шлюза
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            if (changed(e.getMessage())) log.warn("WhatsApp: {} — пауза {} с", e.getMessage(), errorBackoffMs / 1000);
        } catch (RuntimeException e) {
            // в строку состояния (её видит любой вошедший) — только класс, подробности — в лог
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            if (changed(WhatsappChatSync.reason(e))) {
                log.warn("WhatsApp: проход приёма не удался — пауза {} с", errorBackoffMs / 1000, e);
            }
        } catch (Error e) {
            // нехватка памяти и т.п.: без паузы та же голова очереди повторялась бы раз в секунду, молча
            pausedUntil = System.currentTimeMillis() + errorBackoffMs;
            if (changed(WhatsappChatSync.reason(e))) {
                log.error("WhatsApp: проход приёма упал ({}) — пауза {} с", e.getClass().getSimpleName(), errorBackoffMs / 1000, e);
            }
        } finally {
            MarketContext.clear();
        }
    }

    /** Записать ошибку в строку состояния; true — текст новый (в лог — один раз на состояние, не каждым проходом). */
    private boolean changed(String error) {
        boolean isNew = !Objects.equals(status.lastError(), error);
        status.setLastError(error);
        return isNew;
    }

    public WhatsappStatusResponse status() {
        WhatsappStatusResponse r = status.snapshot(enabled, source.isConfigured());
        r.setProvider(source.name());
        r.setMarket(market == null ? null : market.name());
        long idleMs = status.sinceProgressMs();
        if (running.get() && idleMs > stallMs) {
            r.setLastError("приём не продвигается " + Math.max(1, idleMs / 60_000) + " мин — перезапустите бэкенд"
                    + " (docker compose restart ais-backend), подробности в логе сервера");
        }
        return r;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 10: Настройка провайдера**

В `src/main/resources/application.yaml`, блок `chats.whatsapp`, сразу после строки `enabled: ${WHATSAPP_ENABLED:false}` добавить:

```yaml
    provider: ${WHATSAPP_PROVIDER:greenapi}        # greenapi | waha
```

- [ ] **Step 11: Прогнать затронутые тесты**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew cleanTest test --tests 'com.vladoose.nir.integration.whatsapp.*' --tests 'com.vladoose.nir.integration.greenapi.*' --tests 'com.vladoose.nir.chat.*'` (`dangerouslyDisableSandbox: true`)
Expected: PASS, в т.ч. новые `fileWithKnownSizeOverLimitIsNotDownloaded`, `statusTellsWhichGatewayIsActive`, `WhatsappSourceConfigTest`.

- [ ] **Step 12: Мутация — снять проверку размера до скачивания**

Временно закомментировать в `WhatsappChatSync.fetchFile` три строки блока `if (ref.sizeBytes() != null && …) { … }`.
Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.whatsapp.WhatsappChatSyncTest'`
Expected: FAIL ровно `fileWithKnownSizeOverLimitIsNotDownloaded` (`downloads` не пуст). Вернуть строки, повторить — PASS.

- [ ] **Step 13: Полный гейт**

Run: `./gradlew cleanTest test` (`dangerouslyDisableSandbox: true`)
Expected: 0 падений (было 674 теста; стало 678 — четыре новых).

- [ ] **Step 14: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add -A src/main/java/com/vladoose/nir/integration src/test/java/com/vladoose/nir/integration \
  src/main/java/com/vladoose/nir/controller/ChatController.java src/main/java/com/vladoose/nir/service/ChatIngestWriter.java \
  src/main/java/com/vladoose/nir/dto/response/WhatsappStatusResponse.java src/main/resources/application.yaml src/test/java/com/vladoose/nir/chat
git commit -m "refactor(whatsapp): общий цикл приёма за интерфейсом источника; Green-API — GreenApiSource

Пакет integration/whatsapp (цикл, состояние, исключения шлюза), провайдер по WHATSAPP_PROVIDER
(опечатка — красная строка, не падение АИС), известный размер файла больше предела — без скачивания.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Звонок — служебная строка переписки и обращение «Звонок в WhatsApp»

**Files:**
- Modify: `entity/ChatMessageType.java`, `service/ChatIngestWriter.java`, `service/LeadIntakeService.java`
- Test: `src/test/java/com/vladoose/nir/chat/ChatIngestWriterTest.java`

**Interfaces:**
- Consumes: `ParsedNotification.Message` (задача 1), `ChatIngestWriter.write`.
- Produces: `ChatMessageType.CALL`; соглашение о звонке (его выполняет парсер задачи 3): `idMessage = "call:" + id звонка`, `type = CALL`, `direction = IN`, текст «📞 Входящий звонок» / «📹 Входящий видеозвонок»; исход — сообщение-правка с `editOf = "call:" + id` и полным новым текстом «… — принят» / «… — отклонён». `ChatIngestWriter.CALL_SUBJECT = "Звонок в WhatsApp"`.

- [ ] **Step 1: Тесты (красные)**

В `ChatIngestWriterTest` добавить импорты `com.vladoose.nir.integration.whatsapp.ChatKind` и `java.time.ZoneOffset`, затем после метода `leadsOf` — хелперы:

```java
    /** Звонок WAHA (спека whatsapp-waha §8): outcome — null у call.received, « — принят»/« — отклонён» у исхода. */
    private ParsedNotification.Message call(String chat, String callId, String outcome) {
        String externalId = "call:" + callId;
        ChatKind kind = ChatKind.of(chat);
        return new ParsedNotification.Message(GreenApiJson.ACCOUNT, chat, kind,
                kind == ChatKind.PERSONAL ? "+" + chat.substring(0, chat.indexOf('@')) : null, null, null,
                LeadDirection.IN, externalId, OffsetDateTime.ofInstant(Instant.ofEpochSecond(clock += 60), ZoneOffset.UTC),
                ChatMessageType.CALL, "📞 Входящий звонок" + (outcome == null ? "" : outcome), null,
                outcome == null ? null : externalId, false);
    }

    private List<ChatMessage> messagesOf(Long chatId) { return messageRepository.findLatest(chatId, PageRequest.of(0, 20)); }
```

и тесты в конец класса:

```java
    @Test
    void incomingCallIsServiceLineAndStartsCallLead() {
        ChatIngestWriter.Outcome o = write(call(personal(), "C1", null));

        ChatMessage m = messageRepository.findById(o.messageId()).orElseThrow();
        assertThat(m.getType()).isEqualTo(ChatMessageType.CALL);
        assertThat(m.getBody()).isEqualTo("📞 Входящий звонок");
        assertThat(m.isEdited()).isFalse();
        Lead l = leadRepository.findById(o.createdLeadId()).orElseThrow();
        assertThat(l.getSubject()).isEqualTo("Звонок в WhatsApp");
        assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(l.getMessage()).isEqualTo("📞 Входящий звонок");
        // тема уже называет канал — без хвоста «— WhatsApp»
        assertThat(l.getEvents()).extracting(LeadEvent::getBody).containsExactly("Звонок в WhatsApp");
    }

    /** Исход дописывается к той же строке и не делает её «изменённой»: это не правка текста человеком. */
    @Test
    void callOutcomeCompletesTheSameLine() {
        String chat = personal();
        ChatIngestWriter.Outcome first = write(call(chat, "C2", null));
        ChatIngestWriter.Outcome outcome = write(call(chat, "C2", " — принят"));

        assertThat(outcome.createdLeadId()).isNull();
        assertThat(messagesOf(first.chatId())).singleElement().satisfies(m -> {
            assertThat(m.getBody()).isEqualTo("📞 Входящий звонок — принят");
            assertThat(m.isEdited()).isFalse();
        });
        assertThat(chatRepository.findById(first.chatId()).orElseThrow().getLastMessagePreview())
                .isEqualTo("📞 Входящий звонок — принят");
        assertThat(leadsOf(first)).hasSize(1);
    }

    /** Звонок при открытом обращении — просто в его ленте; в «В работе» не переводит (это не ответ с телефона). */
    @Test
    void callDuringOpenLeadDoesNotStartNewOneOrTakeItIntoWork() {
        String chat = personal();
        ChatIngestWriter.Outcome first = write(in(chat, "Здравствуйте, нужен облучатель"));
        ChatIngestWriter.Outcome c = write(call(chat, "C3", null));

        assertThat(c.createdLeadId()).isNull();
        assertThat(leadsOf(first)).singleElement().extracting(Lead::getStatus).isEqualTo(LeadStatus.NEW);
    }

    @Test
    void groupCallIsStoredWithoutLead() {
        ChatIngestWriter.Outcome o = write(call(group(), "C4", null));

        assertThat(o.createdLeadId()).isNull();
        assertThat(leadsOf(o)).isEmpty();
        assertThat(messageRepository.findById(o.messageId()).orElseThrow().getType()).isEqualTo(ChatMessageType.CALL);
    }

    /** call.received потерялся — исход становится строкой звонка сам, без пометки «изменено». */
    @Test
    void outcomeWithoutReceivedCallBecomesCallLine() {
        ChatIngestWriter.Outcome o = write(call(personal(), "C5", " — отклонён"));

        ChatMessage m = messageRepository.findById(o.messageId()).orElseThrow();
        assertThat(m.getBody()).isEqualTo("📞 Входящий звонок — отклонён");
        assertThat(m.getExternalId()).isEqualTo("call:C5");
        assertThat(m.isEdited()).isFalse();
        assertThat(o.createdLeadId()).isNull();
    }
```

- [ ] **Step 2: Убедиться, что красные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew cleanTest test --tests 'com.vladoose.nir.chat.ChatIngestWriterTest'` (`dangerouslyDisableSandbox: true`)
Expected: FAIL — компиляция: нет `ChatMessageType.CALL`.

- [ ] **Step 3: Реализация**

`entity/ChatMessageType.java`:

```java
package com.vladoose.nir.entity;

/** Вид сообщения чата (спека whatsapp-chats §6.5). CALL — служебная строка о входящем звонке (спека whatsapp-waha §8). */
public enum ChatMessageType { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, STICKER, LOCATION, CONTACT, CALL, OTHER }
```

`service/ChatIngestWriter.java`:
1. После `static final String AUTO_TAKE_NOTE = …;` добавить:

```java
    /** Тема обращения, начатого входящим звонком (спека whatsapp-waha §8). */
    static final String CALL_SUBJECT = "Звонок в WhatsApp";
```

2. В ветке правки строку `edited.setEdited(true);` заменить на:

```java
                // исход звонка («— принят») дописывается к строке звонка — это не правка текста человеком
                if (m.type() != ChatMessageType.CALL) edited.setEdited(true);
```

3. В построении нового сообщения `.sentAt(m.sentAt()).edited(m.isEdit()).build());` заменить на:

```java
                .sentAt(m.sentAt()).edited(m.isEdit() && m.type() != ChatMessageType.CALL).build());
```

4. В `applyLeadRules` блок от `boolean cart = …` до `m.displayText(), …);` заменить на:

```java
        boolean cart = cartItems != null && !cartItems.isEmpty();
        String subject = cart ? "Запрос КП" : m.type() == ChatMessageType.CALL ? CALL_SUBJECT : "WhatsApp";
        IncomingLead in = new IncomingLead(LeadSources.WHATSAPP, "wa:" + m.idMessage(), LeadChannel.WHATSAPP,
                subject, m.sentAt(), m.chatName(), m.phone(), null, null,
                m.displayText(), cart ? cartItems : List.of(), LeadStatus.NEW, null, null);
```

`service/LeadIntakeService.java`, метод `receivedText` — последние две строки заменить:

```java
        String label = LeadSources.label(in.source());
        // тема «WhatsApp» от источника «WhatsApp» дала бы «WhatsApp — WhatsApp», тема «Звонок в WhatsApp» — «… — WhatsApp»
        if (base.equals(label)) return "Сообщение в " + label;
        return base.contains(label) ? base : base + " — " + label;
```

- [ ] **Step 4: Прогнать**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.chat.*' --tests 'com.vladoose.nir.lead.*'`
Expected: PASS (включая прежние `firstMessageOfPersonalChatCreatesChatAndNewLead` — «Сообщение в WhatsApp» — и корзину «Запрос КП, 2 поз. — WhatsApp»).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/entity/ChatMessageType.java \
  src/main/java/com/vladoose/nir/service/ChatIngestWriter.java src/main/java/com/vladoose/nir/service/LeadIntakeService.java \
  src/test/java/com/vladoose/nir/chat/ChatIngestWriterTest.java
git commit -m "feat(chats): звонок WhatsApp — служебная строка переписки и обращение «Звонок в WhatsApp»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Разбор событий WAHA

Чистая функция «событие вебхука → `ParsedNotification`» плюс два помощника очереди: ключ сообщения и время события.

**Files:**
- Create: `integration/waha/WahaEventParser.java`
- Test: `src/test/java/com/vladoose/nir/integration/waha/WahaJson.java` (формы событий), `WahaEventParserTest.java`

**Interfaces:**
- Consumes: `ChatKind.of`, `FileRef`, `ParsedNotification`, `GatewayException` (задача 1); соглашение о звонках (задача 2).
- Produces (пакет `com.vladoose.nir.integration.waha`):
  - `WahaEventParser.parse(JsonNode envelope, String knownAccount) → ParsedNotification` — `knownAccount` нужен, только если в конверте нет `me` (номер сессии из `WahaSessionManager`); оба пусты → `GatewayException` (попытка засчитывается, сообщение ждёт).
  - `WahaEventParser.messageKey(JsonNode envelope)` → `"fromMe_rawId"` у `message.any`, иначе `null`.
  - `WahaEventParser.eventAt(JsonNode envelope)` → `payload.timestamp` (сек; если похоже на мс — делится), иначе `timestamp` конверта (мс), иначе сейчас.
  - `public record WahaEventParser.MessageId(boolean fromMe, String chatId, String rawId, String participant)` + `static MessageId parse(String id)` (`null`, если не id WAHA); `chatId` уже нормализован (`…@s.whatsapp.net` → `…@c.us`).
  - Сообщение с файлом: `FileRef(locator = полный id сообщения WAHA, fileName, mimeType, sizeBytes из fileLength)`.
  - `WahaJson` (тест): `ACCOUNT`, `ME`, `SESSION`, `M`, `envelope`, `messageId`, `incomingText`, `phoneReply`, `apiSent`, `groupText`, `incomingFile`, `groupFile`, `withContent`, `info`, `content`, `edited`, `revoked`, `sessionStatus`, `call`.

- [ ] **Step 1: Формы событий для тестов**

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaJson.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * События WAHA в форме документации (спека whatsapp-waha §2): конверт вебхука {id, timestamp(мс), event, session,
 * metadata, me, payload, engine}; payload message.any — нормализованные поля WAHA + сырые `_data` движка GOWS
 * (Info.PushName / SenderAlt / RecipientAlt, Message.<ключ содержимого>). Форма `_data` сверяется с живой WAHA
 * (задача 13 плана): расхождение — править и здесь, и в парсере.
 */
public final class WahaJson {

    public static final String ACCOUNT = "77000000001";
    public static final String ME = ACCOUNT + "@c.us";
    public static final String SESSION = "westmed";
    static final ObjectMapper M = new ObjectMapper();

    private WahaJson() {}

    public static ObjectNode envelope(String event, ObjectNode payload) {
        ObjectNode e = M.createObjectNode();
        e.put("id", "evt_" + UUID.randomUUID().toString().replace("-", ""));
        e.put("timestamp", System.currentTimeMillis());
        e.put("event", event);
        e.put("session", SESSION);
        e.set("metadata", M.createObjectNode().put("market", "KZ"));
        e.set("me", M.createObjectNode().put("id", ME).put("pushName", "West-Med"));
        e.set("payload", payload);
        e.put("engine", "GOWS");
        return e;
    }

    public static String messageId(boolean fromMe, String chatId, String rawId) { return fromMe + "_" + chatId + "_" + rawId; }

    public static ObjectNode incomingText(String chatId, String pushName, String rawId, long epochSec, String text) {
        ObjectNode p = message(false, chatId, rawId, null, epochSec, text, pushName);
        content(p).put("conversation", text);
        return envelope("message.any", p);
    }

    /** Ответ с телефона: fromMe, source=app. */
    public static ObjectNode phoneReply(String chatId, String rawId, long epochSec, String text) {
        ObjectNode p = message(true, chatId, rawId, null, epochSec, text, null);
        p.put("source", "app");
        content(p).put("conversation", text);
        return envelope("message.any", p);
    }

    /** Отправлено через API WAHA (бот, другая интеграция): fromMe, source=api. */
    public static ObjectNode apiSent(String chatId, String rawId, long epochSec, String text) {
        ObjectNode p = message(true, chatId, rawId, null, epochSec, text, null);
        p.put("source", "api");
        content(p).put("conversation", text);
        return envelope("message.any", p);
    }

    public static ObjectNode groupText(String groupId, String participant, String pushName, String rawId, long epochSec, String text) {
        ObjectNode p = message(false, groupId, rawId, participant, epochSec, text, pushName);
        content(p).put("conversation", text);
        return envelope("message.any", p);
    }

    /** Файл без скачивания (WAHA_EVENTS_DOWNLOAD_MEDIA=false): hasMedia, media без url, размер — в содержимом GOWS. */
    public static ObjectNode incomingFile(String chatId, String pushName, String rawId, long epochSec, String contentKey,
                                          String fileName, String mime, Long fileLength, String caption) {
        ObjectNode p = message(false, chatId, rawId, null, epochSec, caption, pushName);
        return envelope("message.any", file(p, contentKey, fileName, mime, fileLength, caption));
    }

    public static ObjectNode groupFile(String groupId, String participant, String pushName, String rawId, long epochSec,
                                       String contentKey, String fileName, String mime, Long fileLength) {
        ObjectNode p = message(false, groupId, rawId, participant, epochSec, null, pushName);
        return envelope("message.any", file(p, contentKey, fileName, mime, fileLength, null));
    }

    /** Заменить содержимое GOWS (`_data.Message`) — стикер, геоточка, контакт, реакция, служебное. */
    public static ObjectNode withContent(ObjectNode env, String key, JsonNode value) {
        ObjectNode content = M.createObjectNode();
        content.set(key, value);
        ((ObjectNode) env.get("payload").get("_data")).set("Message", content);
        return env;
    }

    /** `_data.Info` события — дописать SenderAlt/RecipientAlt и т.п. */
    public static ObjectNode info(ObjectNode env) { return (ObjectNode) env.get("payload").get("_data").get("Info"); }

    static ObjectNode content(ObjectNode payload) { return (ObjectNode) payload.get("_data").get("Message"); }

    public static ObjectNode edited(String chatId, boolean fromMe, String editRawId, String originalId, long epochSec, String newText) {
        ObjectNode p = message(fromMe, chatId, editRawId, null, epochSec, newText, fromMe ? null : "Айгерим");
        p.put("editedMessageId", originalId);
        return envelope("message.edited", p);
    }

    public static ObjectNode revoked(String chatId, boolean fromMe, String revokeRawId, String revokedId) {
        ObjectNode p = M.createObjectNode();
        p.set("after", message(fromMe, chatId, revokeRawId, null, System.currentTimeMillis() / 1000, null, null));
        p.putNull("before");
        p.put("revokedMessageId", revokedId);
        return envelope("message.revoked", p);
    }

    public static ObjectNode sessionStatus(String status) {
        ObjectNode p = M.createObjectNode();
        p.put("name", SESSION);
        p.put("status", status);
        return envelope("session.status", p);
    }

    /** event — call.received / call.accepted / call.rejected; groupId != null — групповой звонок (jid группы в _data). */
    public static ObjectNode call(String event, String callId, String from, long epochSec, boolean video, String groupId) {
        ObjectNode p = M.createObjectNode();
        p.put("id", callId);
        p.put("from", from);
        p.put("timestamp", epochSec);
        p.put("isVideo", video);
        p.put("isGroup", groupId != null);
        ObjectNode data = M.createObjectNode();
        data.put("CallID", callId);
        data.put("From", toServer(from));
        if (groupId != null) data.put("GroupJID", groupId);
        p.set("_data", data);
        return envelope(event, p);
    }

    private static ObjectNode message(boolean fromMe, String chatId, String rawId, String participant, long epochSec,
                                      String body, String pushName) {
        ObjectNode p = M.createObjectNode();
        p.put("id", messageId(fromMe, chatId, rawId) + (participant == null ? "" : "_" + participant));
        p.put("timestamp", epochSec);
        p.put("from", fromMe ? ME : chatId);
        p.put("fromMe", fromMe);
        p.put("to", fromMe ? chatId : ME);
        if (participant != null) p.put("participant", participant);
        if (body != null) p.put("body", body); else p.putNull("body");
        p.put("hasMedia", false);
        p.putNull("media");
        p.put("ack", fromMe ? 1 : 0);
        ObjectNode info = M.createObjectNode();
        info.put("Chat", toServer(chatId));
        info.put("Sender", toServer(fromMe ? ME : participant != null ? participant : chatId));
        info.put("IsFromMe", fromMe);
        info.put("IsGroup", chatId.endsWith("@g.us"));
        info.put("ID", rawId);
        if (pushName != null) info.put("PushName", pushName);
        ObjectNode data = M.createObjectNode();
        data.set("Info", info);
        data.set("Message", M.createObjectNode());
        p.set("_data", data);
        return p;
    }

    private static ObjectNode file(ObjectNode p, String contentKey, String fileName, String mime, Long fileLength, String caption) {
        p.put("hasMedia", true);
        ObjectNode media = M.createObjectNode();
        media.putNull("url");
        media.put("mimetype", mime);
        if (fileName != null) media.put("filename", fileName); else media.putNull("filename");
        p.set("media", media);
        ObjectNode c = M.createObjectNode();
        c.put("mimetype", mime);
        if (fileName != null) c.put("fileName", fileName);
        if (fileLength != null) c.put("fileLength", fileLength);
        if (caption != null) c.put("caption", caption);
        content(p).set(contentKey, c);
        return p;
    }

    /** Внутренний вид jid у GOWS: «…@s.whatsapp.net» вместо «…@c.us». */
    static String toServer(String jid) {
        return jid != null && jid.endsWith("@c.us") ? jid.substring(0, jid.length() - 5) + "@s.whatsapp.net" : jid;
    }
}
```

- [ ] **Step 2: Тесты парсера (красные)**

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaEventParserTest.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.integration.whatsapp.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

class WahaEventParserTest {

    static final String CLIENT = "77011234567@c.us";
    static final String GROUP = "120363000000000001@g.us";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final long T = 1_790_000_000L;

    private static ParsedNotification.Message msg(ObjectNode env) {
        ParsedNotification p = WahaEventParser.parse(env, null);
        assertThat(p).isInstanceOf(ParsedNotification.Message.class);
        return (ParsedNotification.Message) p;
    }

    @Test
    void incomingTextFromPersonalChat() {
        ParsedNotification.Message m = msg(WahaJson.incomingText(CLIENT, "Айгерим", "3EB0A1", T, "Нужен облучатель"));

        assertThat(m.account()).isEqualTo("77000000001");
        assertThat(m.chatId()).isEqualTo(CLIENT);
        assertThat(m.kind()).isEqualTo(ChatKind.PERSONAL);
        assertThat(m.phone()).isEqualTo("+77011234567");
        assertThat(m.chatName()).isEqualTo("Айгерим");
        assertThat(m.senderName()).isEqualTo("Айгерим");
        assertThat(m.direction()).isEqualTo(LeadDirection.IN);
        assertThat(m.idMessage()).isEqualTo("3EB0A1");      // сырой id WhatsApp — тот же, что idMessage у Green-API
        assertThat(m.sentAt()).isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochSecond(T), ZoneOffset.UTC));
        assertThat(m.type()).isEqualTo(ChatMessageType.TEXT);
        assertThat(m.body()).isEqualTo("Нужен облучатель");
        assertThat(m.file()).isNull();
        assertThat(m.isEdit()).isFalse();
        assertThat(m.viaApi()).isFalse();
    }

    @Test
    void phoneReplyIsOutgoingAndApiSentIsMarked() {
        ParsedNotification.Message phone = msg(WahaJson.phoneReply(CLIENT, "3EB0A2", T, "Подготовим КП"));
        ParsedNotification.Message api = msg(WahaJson.apiSent(CLIENT, "3EB0A3", T, "Спасибо за обращение!"));

        assertThat(phone.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(phone.chatId()).isEqualTo(CLIENT);
        assertThat(phone.chatName()).isNull();
        assertThat(phone.senderName()).isNull();
        assertThat(phone.viaApi()).isFalse();
        assertThat(api.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(api.viaApi()).isTrue();
    }

    /** Чат — из id сообщения (поле from у своих сообщений ненадёжно, issue #2241); внутренний вид GOWS → @c.us. */
    @Test
    void chatComesFromMessageIdAndServerJidIsNormalized() {
        ObjectNode env = WahaJson.phoneReply("77011234567@s.whatsapp.net", "3EB0A4", T, "x");
        ((ObjectNode) env.get("payload")).put("from", "77099999999@c.us");

        ParsedNotification.Message m = msg(env);

        assertThat(m.chatId()).isEqualTo(CLIENT);
        assertThat(m.kind()).isEqualTo(ChatKind.PERSONAL);
    }

    @Test
    void fileWithoutDownloadCarriesMessageIdNameAndSize() {
        ParsedNotification.Message m = msg(WahaJson.incomingFile(CLIENT, "Айгерим", "3EB0A5", T, "documentMessage",
                "Заявка.xlsx", XLSX, 48_213L, "Полный список"));

        assertThat(m.type()).isEqualTo(ChatMessageType.DOCUMENT);
        assertThat(m.body()).isEqualTo("Полный список");
        assertThat(m.file()).isEqualTo(new FileRef(WahaJson.messageId(false, CLIENT, "3EB0A5"), "Заявка.xlsx", XLSX, 48_213L));
    }

    /** media: null — имя и тип берём из содержимого GOWS. */
    @Test
    void mediaNullStillGivesFileFromContent() {
        ObjectNode env = WahaJson.incomingFile(CLIENT, "Айгерим", "3EB0A6", T, "imageMessage", null, "image/jpeg", 120_000L, null);
        ((ObjectNode) env.get("payload")).putNull("media");

        ParsedNotification.Message m = msg(env);

        assertThat(m.type()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(m.displayText()).isEqualTo("[фото]");
        assertThat(m.file().mimeType()).isEqualTo("image/jpeg");
        assertThat(m.file().sizeBytes()).isEqualTo(120_000L);
    }

    /** uint64 бывает в JSON строкой — читаем обе формы. */
    @Test
    void fileLengthAsStringIsRead() {
        ObjectNode env = WahaJson.incomingFile(CLIENT, "Айгерим", "3EB0A7", T, "videoMessage", "clip.mp4", "video/mp4", null, null);
        ((ObjectNode) WahaJson.content((ObjectNode) env.get("payload")).get("videoMessage")).put("fileLength", "41943040");

        assertThat(msg(env).file().sizeBytes()).isEqualTo(41_943_040L);
    }

    @Test
    void groupMessageKeepsAuthor() {
        ParsedNotification.Message m = msg(WahaJson.groupText(GROUP, "77025556677@c.us", "Данияр", "3EB0A8", T, "Кто едет в Уральск?"));

        assertThat(m.kind()).isEqualTo(ChatKind.GROUP);
        assertThat(m.chatId()).isEqualTo(GROUP);
        assertThat(m.phone()).isNull();
        assertThat(m.chatName()).isNull();          // тему группы подставит источник (справочник WAHA)
        assertThat(m.senderName()).isEqualTo("Данияр");
        assertThat(m.idMessage()).isEqualTo("3EB0A8");
    }

    @Test
    void hiddenNumberGetsPhoneFromAltJid() {
        ObjectNode in = WahaJson.incomingText("123456789012345@lid", "Скрытый", "3EB0A9", T, "Добрый день");
        WahaJson.info(in).put("SenderAlt", "77012223344@s.whatsapp.net");
        ObjectNode out = WahaJson.phoneReply("123456789012345@lid", "3EB0AA", T, "Здравствуйте");
        WahaJson.info(out).put("RecipientAlt", "77012223344:12@s.whatsapp.net");
        ObjectNode unknown = WahaJson.incomingText("123456789012345@lid", "Скрытый", "3EB0AB", T, "ещё");

        assertThat(msg(in).kind()).isEqualTo(ChatKind.PERSONAL_HIDDEN);
        assertThat(msg(in).phone()).isEqualTo("+77012223344");
        assertThat(msg(out).phone()).isEqualTo("+77012223344");
        assertThat(msg(unknown).phone()).isNull();
    }

    @Test
    void editPointsToOriginalRawIdInEitherForm() {
        ParsedNotification.Message raw = msg(WahaJson.edited(CLIENT, false, "3EB0B1", "3EB0A1", T, "Нужны два облучателя"));
        ParsedNotification.Message full = msg(WahaJson.edited(CLIENT, false, "3EB0B2",
                WahaJson.messageId(false, CLIENT, "3EB0A1"), T, "Нужны три"));

        assertThat(raw.isEdit()).isTrue();
        assertThat(raw.editOf()).isEqualTo("3EB0A1");
        assertThat(raw.body()).isEqualTo("Нужны два облучателя");
        assertThat(full.editOf()).isEqualTo("3EB0A1");
    }

    @Test
    void revokeBecomesDelete() {
        assertThat(WahaEventParser.parse(WahaJson.revoked(CLIENT, false, "3EB0C1", "3EB0A1"), null))
                .isEqualTo(new ParsedNotification.Delete("77000000001", CLIENT, "3EB0A1"));
    }

    @Test
    void sessionStatusBecomesState() {
        assertThat(WahaEventParser.parse(WahaJson.sessionStatus("SCAN_QR_CODE"), null))
                .isEqualTo(new ParsedNotification.State("SCAN_QR_CODE"));
    }

    @Test
    void callsBecomeCallLinesAndOutcomesPointToThem() {
        ParsedNotification.Message received = msg(WahaJson.call("call.received", "CALL1", CLIENT, T, false, null));
        ParsedNotification.Message video = msg(WahaJson.call("call.received", "CALL2", CLIENT, T, true, null));
        ParsedNotification.Message accepted = msg(WahaJson.call("call.accepted", "CALL1", CLIENT, T + 5, false, null));
        ParsedNotification.Message rejected = msg(WahaJson.call("call.rejected", "CALL2", CLIENT, T + 5, true, null));

        assertThat(received.type()).isEqualTo(ChatMessageType.CALL);
        assertThat(received.idMessage()).isEqualTo("call:CALL1");
        assertThat(received.direction()).isEqualTo(LeadDirection.IN);
        assertThat(received.body()).isEqualTo("📞 Входящий звонок");
        assertThat(received.isEdit()).isFalse();
        assertThat(received.phone()).isEqualTo("+77011234567");
        assertThat(video.body()).isEqualTo("📹 Входящий видеозвонок");
        assertThat(accepted.editOf()).isEqualTo("call:CALL1");
        assertThat(accepted.body()).isEqualTo("📞 Входящий звонок — принят");
        assertThat(rejected.body()).isEqualTo("📹 Входящий видеозвонок — отклонён");
    }

    @Test
    void groupCallGoesToGroupChat() {
        ParsedNotification.Message m = msg(WahaJson.call("call.received", "CALL3", "77025556677@c.us", T, false, GROUP));

        assertThat(m.chatId()).isEqualTo(GROUP);
        assertThat(m.kind()).isEqualTo(ChatKind.GROUP);
    }

    @Test
    void storiesChannelsServiceContentAndUnknownEventsAreSkipped() {
        assertThat(WahaEventParser.parse(WahaJson.incomingText("status@broadcast", "A", "3EB0D1", T, "x"), null))
                .isInstanceOf(ParsedNotification.Skip.class);
        assertThat(WahaEventParser.parse(WahaJson.incomingText("120363000000000002@newsletter", "A", "3EB0D2", T, "x"), null))
                .isInstanceOf(ParsedNotification.Skip.class);
        ObjectNode reaction = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0D3", T, "👍"), "reactionMessage",
                WahaJson.M.createObjectNode().put("text", "👍"));
        assertThat(WahaEventParser.parse(reaction, null)).isInstanceOf(ParsedNotification.Skip.class);
        // правка, продублированная в message.any, не должна стать вторым пузырём — у неё своё событие message.edited
        ObjectNode editInAny = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0D4", T, "новый"), "protocolMessage",
                WahaJson.M.createObjectNode().put("type", "MESSAGE_EDIT"));
        assertThat(WahaEventParser.parse(editInAny, null)).isInstanceOf(ParsedNotification.Skip.class);
        assertThat(WahaEventParser.parse(WahaJson.envelope("presence.update", WahaJson.M.createObjectNode()), null))
                .isInstanceOf(ParsedNotification.Skip.class);
    }

    @Test
    void stickerLocationContactsAndUnknownContent() {
        ObjectNode sticker = WahaJson.withContent(WahaJson.incomingFile(CLIENT, "A", "3EB0E1", T, "stickerMessage", null,
                "image/webp", 9_000L, null), "stickerMessage", WahaJson.M.createObjectNode().put("mimetype", "image/webp"));
        assertThat(msg(sticker)).satisfies(m -> {
            assertThat(m.type()).isEqualTo(ChatMessageType.STICKER);
            assertThat(m.body()).isEqualTo("[стикер]");
            assertThat(m.file()).isNull();
        });
        ObjectNode location = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0E2", T, ""), "locationMessage",
                WahaJson.M.createObjectNode().put("degreesLatitude", 51.2).put("degreesLongitude", 51.37)
                        .put("name", "Клиника").put("address", "Уральск, ул. Ленина 1"));
        assertThat(msg(location).body()).isEqualTo("📍 Клиника, Уральск, ул. Ленина 1");
        ObjectNode contact = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0E3", T, ""), "contactMessage",
                WahaJson.M.createObjectNode().put("displayName", "Иван Поставщик"));
        assertThat(msg(contact).body()).isEqualTo("[контакт: Иван Поставщик]");
        ObjectNode contacts = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0E4", T, ""), "contactsArrayMessage",
                WahaJson.M.createObjectNode().set("contacts", WahaJson.M.createArrayNode().add("a").add("b").add("c")));
        assertThat(msg(contacts).body()).isEqualTo("[контакты: 3]");
        ObjectNode poll = WahaJson.withContent(WahaJson.incomingText(CLIENT, "A", "3EB0E5", T, ""), "pollCreationMessageV3",
                WahaJson.M.createObjectNode().put("name", "Опрос"));
        assertThat(msg(poll)).satisfies(m -> {
            assertThat(m.type()).isEqualTo(ChatMessageType.OTHER);
            assertThat(m.body()).contains("pollCreationMessageV3");
        });
    }

    /** Нет содержимого GOWS (другой движок или урезанное событие) — по нормализованным полям WAHA. */
    @Test
    void withoutGowsContentUsesNormalizedFields() {
        ObjectNode env = WahaJson.incomingFile(CLIENT, "A", "3EB0F1", T, "documentMessage", "ТЗ.pdf", "application/pdf", 10L, "ТЗ");
        ((ObjectNode) env.get("payload")).remove("_data");

        ParsedNotification.Message m = msg(env);

        assertThat(m.type()).isEqualTo(ChatMessageType.DOCUMENT);
        assertThat(m.body()).isEqualTo("ТЗ");
        assertThat(m.file()).isEqualTo(new FileRef(WahaJson.messageId(false, CLIENT, "3EB0F1"), "ТЗ.pdf", "application/pdf", null));
        assertThat(m.chatName()).isNull();          // без _data нет и PushName
    }

    /** Номер — из конверта (me) или известной сессии; наугад — никогда: сообщение ждёт следующей попытки. */
    @Test
    void accountFromEnvelopeOrKnownSessionNeverGuessed() {
        ObjectNode noMe = WahaJson.incomingText(CLIENT, "A", "3EB0G1", T, "x");
        noMe.remove("me");

        assertThat(((ParsedNotification.Message) WahaEventParser.parse(noMe, "77000000009@c.us")).account()).isEqualTo("77000000009");
        assertThatThrownBy(() -> WahaEventParser.parse(noMe, null))
                .isInstanceOf(GatewayException.class).hasMessageContaining("номер");
    }

    @Test
    void queueKeyAndEventTime() {
        ObjectNode in = WahaJson.incomingText(CLIENT, "A", "3EB0H1", T, "x");
        ObjectNode status = WahaJson.sessionStatus("WORKING");

        assertThat(WahaEventParser.messageKey(in)).isEqualTo("false_3EB0H1");
        assertThat(WahaEventParser.messageKey(WahaJson.phoneReply(CLIENT, "3EB0H1", T, "x"))).isEqualTo("true_3EB0H1");
        assertThat(WahaEventParser.messageKey(WahaJson.groupText(GROUP, "77025556677@c.us", "Д", "3EB0H2", T, "x")))
                .isEqualTo("false_3EB0H2");
        assertThat(WahaEventParser.messageKey(status)).isNull();
        assertThat(WahaEventParser.eventAt(in)).isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochSecond(T), ZoneOffset.UTC));
        assertThat(WahaEventParser.eventAt(status)).isEqualTo(
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(status.get("timestamp").asLong()), ZoneOffset.UTC));
    }
}
```

- [ ] **Step 3: Убедиться, что красные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew cleanTest test --tests 'com.vladoose.nir.integration.waha.*'` (`dangerouslyDisableSandbox: true`)
Expected: FAIL — компиляция: нет `WahaEventParser`.

- [ ] **Step 4: Парсер**

Создать `integration/waha/WahaEventParser.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.integration.whatsapp.ChatKind;
import com.vladoose.nir.integration.whatsapp.FileRef;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Событие вебхука WAHA → доменная запись (спека whatsapp-waha §2, §8). Чистая функция: сеть не трогает, на
 * недостающих полях не бросает — кроме случая, когда номер сессии неизвестен вовсе (сообщение ждёт попытки).
 * Форма `_data` движка GOWS в документации WAHA не описана; то, что берём оттуда (Info.PushName / SenderAlt /
 * RecipientAlt, ключ содержимого Message, fileLength), сверено с живой WAHA (фикстуры src/test/resources/waha).
 */
public final class WahaEventParser {

    static final String CALL_PREFIX = "call:";
    /** Содержимое не для человека: реакции, правки и удаления (у них свои события), служебное шифрования. */
    private static final Set<String> SERVICE = Set.of("protocolMessage", "reactionMessage", "encReactionMessage",
            "editedMessage", "senderKeyDistributionMessage", "messageContextInfo", "keepInChatMessage",
            "pinInChatMessage", "pollUpdateMessage");

    private WahaEventParser() {}

    public static ParsedNotification parse(JsonNode env, String knownAccount) {
        String event = env == null ? "" : env.path("event").asText("");
        JsonNode p = env == null ? MissingNode.getInstance() : env.path("payload");
        switch (event) {
            case "message.any":
                return message(env, p, knownAccount);
            case "message.edited":
                return edited(env, p, knownAccount);
            case "message.revoked":
                return revoked(env, p, knownAccount);
            case "session.status":
                return new ParsedNotification.State(p.path("status").asText(""));
            case "call.received":
                return call(env, p, knownAccount, null);
            case "call.accepted":
                return call(env, p, knownAccount, " — принят");
            case "call.rejected":
                return call(env, p, knownAccount, " — отклонён");
            default:
                return new ParsedNotification.Skip(event.isEmpty() ? "без event" : event);
        }
    }

    /** Ключ дедупликации очереди: «fromMe_rawId» у message.any (вебхук и догонка); у прочих событий — null. */
    public static String messageKey(JsonNode env) {
        if (env == null || !"message.any".equals(env.path("event").asText(""))) return null;
        MessageId id = MessageId.parse(env.path("payload").path("id").asText(null));
        return id == null ? null : id.fromMe() + "_" + id.rawId();
    }

    /** Порядок обработки: время из события (сек), иначе время конверта (мс), иначе сейчас. */
    public static OffsetDateTime eventAt(JsonNode env) {
        long sec = env == null ? 0 : env.path("payload").path("timestamp").asLong(0);
        if (sec > 100_000_000_000L) sec /= 1000;      // пришли миллисекунды
        if (sec > 0) return OffsetDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneOffset.UTC);
        long ms = env == null ? 0 : env.path("timestamp").asLong(0);
        if (ms > 0) return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** id сообщения WAHA: {fromMe}_{chatId}_{rawId}[_{participant}] (спека §2). */
    public record MessageId(boolean fromMe, String chatId, String rawId, String participant) {

        /** null — строка не id сообщения WAHA. */
        public static MessageId parse(String id) {
            if (id == null) return null;
            String[] parts = id.strip().split("_", 4);
            if (parts.length < 3 || !(parts[0].equals("true") || parts[0].equals("false"))
                    || parts[1].isEmpty() || parts[2].isEmpty()) {
                return null;
            }
            return new MessageId(parts[0].equals("true"), chat(parts[1]), parts[2], parts.length == 4 ? parts[3] : null);
        }
    }

    private record Body(ChatMessageType type, String text, FileRef file) {}

    private static ParsedNotification message(JsonNode env, JsonNode p, String knownAccount) {
        MessageId id = MessageId.parse(text(p, "id"));
        if (id == null) return new ParsedNotification.Skip("сообщение без id");
        ChatKind kind = ChatKind.of(id.chatId());
        if (kind == null) return new ParsedNotification.Skip("не чат: " + id.chatId());
        JsonNode data = p.path("_data");
        JsonNode content = data.path("Message");
        if (isService(content)) return new ParsedNotification.Skip("служебное сообщение");
        String pushName = id.fromMe() ? null : pushName(data);
        Body b = body(p, content);
        return new ParsedNotification.Message(account(env, knownAccount), id.chatId(), kind,
                phone(kind, id.chatId(), data, id.fromMe()), kind == ChatKind.GROUP ? null : pushName, pushName,
                id.fromMe() ? LeadDirection.OUT : LeadDirection.IN, id.rawId(), eventAt(env), b.type(), b.text(), b.file(),
                null, id.fromMe() && "api".equals(p.path("source").asText("")));
    }

    private static ParsedNotification edited(JsonNode env, JsonNode p, String knownAccount) {
        MessageId id = MessageId.parse(text(p, "id"));
        if (id == null) return new ParsedNotification.Skip("правка без id");
        ChatKind kind = ChatKind.of(id.chatId());
        if (kind == null) return new ParsedNotification.Skip("не чат: " + id.chatId());
        String original = rawOf(p.path("editedMessageId").asText(""));
        if (original.isEmpty()) return new ParsedNotification.Skip("правка без исходного сообщения");
        JsonNode data = p.path("_data");
        String pushName = id.fromMe() ? null : pushName(data);
        return new ParsedNotification.Message(account(env, knownAccount), id.chatId(), kind,
                phone(kind, id.chatId(), data, id.fromMe()), kind == ChatKind.GROUP ? null : pushName, pushName,
                id.fromMe() ? LeadDirection.OUT : LeadDirection.IN, id.rawId(), eventAt(env), ChatMessageType.TEXT,
                text(p, "body"), null, original, false);
    }

    private static ParsedNotification revoked(JsonNode env, JsonNode p, String knownAccount) {
        MessageId id = MessageId.parse(text(p.path("after"), "id"));
        if (id == null) id = MessageId.parse(text(p.path("before"), "id"));
        if (id == null) id = MessageId.parse(text(p, "id"));
        String deleted = rawOf(p.path("revokedMessageId").asText(""));
        if (id == null || deleted.isEmpty()) return new ParsedNotification.Skip("удаление без чата или id");
        if (ChatKind.of(id.chatId()) == null) return new ParsedNotification.Skip("не чат: " + id.chatId());
        return new ParsedNotification.Delete(account(env, knownAccount), id.chatId(), deleted);
    }

    /** Звонок (спека §8): строка «📞 Входящий звонок», исход — правка той же строки. */
    private static ParsedNotification call(JsonNode env, JsonNode p, String knownAccount, String outcome) {
        String callId = text(p, "id");
        if (callId == null) return new ParsedNotification.Skip("звонок без id");
        String chatId = chat(p.path("from").asText(""));
        if (p.path("isGroup").asBoolean(false)) {
            String group = findGroup(p.path("_data"), 0);
            if (group != null) chatId = group;
        }
        ChatKind kind = ChatKind.of(chatId);
        if (kind == null) return new ParsedNotification.Skip("звонок не из чата: " + chatId);
        String text = (p.path("isVideo").asBoolean(false) ? "📹 Входящий видеозвонок" : "📞 Входящий звонок")
                + (outcome == null ? "" : outcome);
        String externalId = CALL_PREFIX + callId;
        return new ParsedNotification.Message(account(env, knownAccount), chatId, kind,
                phone(kind, chatId, MissingNode.getInstance(), false), null, null, LeadDirection.IN, externalId,
                eventAt(env), ChatMessageType.CALL, text, null, outcome == null ? null : externalId, false);
    }

    private static Body body(JsonNode p, JsonNode content) {
        String caption = text(p, "body");
        String locator = text(p, "id");
        JsonNode media = p.path("media");
        String key = contentKey(content);
        if (key != null) {
            JsonNode c = content.path(key);
            switch (key) {
                case "conversation":
                    return new Body(ChatMessageType.TEXT, first(caption, c.isTextual() ? c.asText() : null), null);
                case "extendedTextMessage":
                    return new Body(ChatMessageType.TEXT, first(caption, text(c, "text")), null);
                case "imageMessage":
                    return media(ChatMessageType.IMAGE, caption, c, media, locator);
                case "videoMessage":
                case "ptvMessage":
                    return media(ChatMessageType.VIDEO, caption, c, media, locator);
                case "audioMessage":
                    return media(ChatMessageType.AUDIO, caption, c, media, locator);
                case "documentMessage":
                    return media(ChatMessageType.DOCUMENT, caption, c, media, locator);
                case "documentWithCaptionMessage":
                    return media(ChatMessageType.DOCUMENT, caption, c.path("message").path("documentMessage"), media, locator);
                case "stickerMessage":
                    return new Body(ChatMessageType.STICKER, "[стикер]", null);
                case "locationMessage":
                case "liveLocationMessage":
                    return new Body(ChatMessageType.LOCATION, location(p.path("location"), c), null);
                case "contactMessage":
                    return new Body(ChatMessageType.CONTACT, "[контакт: " + first(text(c, "displayName"), "без имени") + "]", null);
                case "contactsArrayMessage": {
                    int n = c.path("contacts").size();
                    return new Body(ChatMessageType.CONTACT, n > 0 ? "[контакты: " + n + "]" : "[контакты]", null);
                }
                default:
                    return new Body(ChatMessageType.OTHER, "[сообщение типа " + key + " — смотрите в WhatsApp]", null);
            }
        }
        // содержимого GOWS нет — по нормализованным полям WAHA
        if (p.path("hasMedia").asBoolean(false)) {
            String mime = text(media, "mimetype");
            ChatMessageType t = mime == null ? ChatMessageType.DOCUMENT
                    : mime.startsWith("image/") ? ChatMessageType.IMAGE
                    : mime.startsWith("video/") ? ChatMessageType.VIDEO
                    : mime.startsWith("audio/") ? ChatMessageType.AUDIO : ChatMessageType.DOCUMENT;
            return media(t, caption, MissingNode.getInstance(), media, locator);
        }
        if (p.path("location").isObject()) {
            return new Body(ChatMessageType.LOCATION, location(p.path("location"), MissingNode.getInstance()), null);
        }
        JsonNode vcards = p.path("vCards");
        if (vcards.isArray() && vcards.size() > 0) return new Body(ChatMessageType.CONTACT, contacts(vcards), null);
        if (caption != null) return new Body(ChatMessageType.TEXT, caption, null);
        return new Body(ChatMessageType.OTHER, "[сообщение — смотрите в WhatsApp]", null);
    }

    private static Body media(ChatMessageType type, String caption, JsonNode c, JsonNode media, String locator) {
        String mime = first(text(media, "mimetype"), text(c, "mimetype"));
        String name = first(text(media, "filename"), text(c, "fileName"));
        return new Body(type, first(caption, text(c, "caption")), new FileRef(locator, name, mime, size(c)));
    }

    /** Размер, если WhatsApp его сообщил: proto-поле fileLength (число или строка — зависит от сериализатора). */
    static Long size(JsonNode c) {
        JsonNode n = c.path("fileLength");
        if (n.isMissingNode() || n.isNull()) n = c.path("FileLength");
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isTextual() && n.asText().matches("\\d{1,18}")) return Long.parseLong(n.asText());
        return null;
    }

    private static String location(JsonNode l, JsonNode c) {
        List<String> parts = new ArrayList<>();
        String name = first(text(l, "name"), text(c, "name"));
        String address = first(text(l, "address"), text(c, "address"), text(l, "description"));
        if (name != null) parts.add(name);
        if (address != null && !address.equals(name)) parts.add(address);
        if (parts.isEmpty()) {
            double lat = l.path("latitude").isNumber() ? l.path("latitude").asDouble() : c.path("degreesLatitude").asDouble();
            double lon = l.path("longitude").isNumber() ? l.path("longitude").asDouble() : c.path("degreesLongitude").asDouble();
            parts.add(lat + ", " + lon);
        }
        return "📍 " + String.join(", ", parts);
    }

    private static String contacts(JsonNode vcards) {
        if (vcards.size() > 1) return "[контакты: " + vcards.size() + "]";
        String fn = null;
        for (String line : vcards.path(0).asText("").split("\\R")) {
            if (line.startsWith("FN:")) {
                fn = line.substring(3).strip();
                break;
            }
        }
        return "[контакт: " + first(fn, "без имени") + "]";
    }

    /** Первый не служебный ключ содержимого GOWS; null — содержимого нет. */
    private static String contentKey(JsonNode content) {
        if (!content.isObject()) return null;
        for (Iterator<String> it = content.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!SERVICE.contains(k)) return k;
        }
        return null;
    }

    private static boolean isService(JsonNode content) {
        if (!content.isObject() || content.isEmpty()) return false;
        for (Iterator<String> it = content.fieldNames(); it.hasNext(); ) {
            if (!SERVICE.contains(it.next())) return false;
        }
        return true;
    }

    private static String pushName(JsonNode data) {
        return first(text(data.path("Info"), "PushName"), text(data, "notifyName"));
    }

    /** Телефон: у @c.us — из id; у скрытого @lid — из «альтернативного» jid GOWS, если он есть. */
    static String phone(ChatKind kind, String chatId, JsonNode data, boolean fromMe) {
        if (kind == ChatKind.PERSONAL) return "+" + userOf(chatId);
        if (kind != ChatKind.PERSONAL_HIDDEN) return null;
        String alt = text(data.path("Info"), fromMe ? "RecipientAlt" : "SenderAlt");
        if (alt == null || !(alt.endsWith("@s.whatsapp.net") || alt.endsWith("@c.us"))) return null;
        String digits = userOf(alt);
        return digits.matches("\\d{6,15}") ? "+" + digits : null;
    }

    private static String account(JsonNode env, String knownAccount) {
        String me = userOf(env.path("me").path("id").asText(""));
        if (!me.isEmpty()) return me;
        String known = userOf(knownAccount);
        if (!known.isEmpty()) return known;
        throw new GatewayException(0, "WAHA: номер подключённого WhatsApp ещё не известен — сообщение будет разобрано при следующей попытке");
    }

    /** «7701…@s.whatsapp.net» (внутренний вид GOWS) → «7701…@c.us» (вид API WAHA и Green-API): иначе чат раздвоился бы. */
    static String chat(String jid) {
        String j = jid == null ? "" : jid.strip();
        return j.endsWith("@s.whatsapp.net") ? userOf(j) + "@c.us" : j;
    }

    /** «7701…:12@s.whatsapp.net» → «7701…»: часть до «@», без номера устройства и агента. */
    static String userOf(String jid) {
        String j = jid == null ? "" : jid.strip();
        int at = j.indexOf('@');
        String user = at < 0 ? j : j.substring(0, at);
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == ':' || c == '.') return user.substring(0, i);
        }
        return user;
    }

    /** editedMessageId / revokedMessageId бывают и сырыми, и полными — берём сырой id WhatsApp. */
    static String rawOf(String id) {
        MessageId m = MessageId.parse(id);
        return m != null ? m.rawId() : (id == null ? "" : id.strip());
    }

    /** Групповой звонок: jid группы где-то в _data (форма GOWS не описана) — ищем значение «…@g.us». */
    private static String findGroup(JsonNode n, int depth) {
        if (n == null || depth > 4) return null;
        if (n.isTextual()) {
            String v = chat(n.asText());
            return v.endsWith("@g.us") ? v : null;
        }
        for (JsonNode child : n) {
            String g = findGroup(child, depth + 1);
            if (g != null) return g;
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
```

- [ ] **Step 5: Прогнать**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.waha.WahaEventParserTest'`
Expected: PASS (18 тестов).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/waha src/test/java/com/vladoose/nir/integration/waha
git commit -m "feat(waha): разбор событий WAHA — сообщения, файлы с размером, правки, удаления, статус, звонки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Очередь `whatsapp_inbox` и приём вебхука

**Files:**
- Create: `src/main/resources/db/migration/V22__whatsapp_inbox.sql`, `entity/WhatsappInboxStatus.java`, `entity/WhatsappInboxEvent.java`, `repository/WhatsappInboxRepository.java`, `integration/waha/WahaSignature.java`, `integration/waha/WahaInboxWriter.java`, `integration/waha/WahaWebhookController.java`
- Modify: `config/SecurityConfig.java`, `frontend/nginx.conf`, `src/main/resources/application.yaml`, `build.gradle`
- Test: `src/test/java/com/vladoose/nir/integration/waha/WahaSignatureTest.java`, `WahaWebhookTest.java`

**Interfaces:**
- Consumes: `WahaEventParser.messageKey/eventAt` (задача 3), `WhatsappChatSync.isInfrastructureFailure` (задача 1), `WhatsappProviders.WAHA`.
- Produces:
  - `enum WhatsappInboxStatus { PENDING, DONE, DROPPED }`; сущность `WhatsappInboxEvent` (геттеры/сеттеры Lombok: `id, provider, requestId, messageKey, event, eventAt, payload, status, lastError, receivedAt, processedAt`).
  - `WhatsappInboxRepository`: `Optional<WhatsappInboxEvent> findNextPending(String provider, OffsetDateTime settled)`, `int deleteProcessedBefore(WhatsappInboxStatus status, OffsetDateTime before)`, `List<WhatsappInboxEvent> findByRequestId(String)`, `List<WhatsappInboxEvent> findByMessageKey(String)`.
  - `WahaInboxWriter`: `boolean insert(JsonNode env, String requestId, String payload)`, `boolean insert(JsonNode env)` (догонка), `void finish(long id, WhatsappInboxStatus status, String error)`, `int cleanup(OffsetDateTime doneBefore, OffsetDateTime droppedBefore)`.
  - `WahaSignature.sign(byte[] body, String key) → String hex`, `verify(byte[] body, String key, String header) → boolean`.
  - `WahaWebhookController.PATH = "/api/whatsapp/waha/webhook"`; конструктор `(WahaInboxWriter, ObjectMapper, String hmacKey)`; `ResponseEntity<Void> receive(String hmac, String requestId, byte[] body)`.
  - Тестовое свойство `chats.whatsapp.waha.hmac-key = test-hmac-key` (build.gradle) — для всех HTTP-тестов вебхука.

- [ ] **Step 1: Тесты подписи и вебхука (красные)**

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaSignatureTest.java`:

```java
package com.vladoose.nir.integration.waha;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class WahaSignatureTest {

    /** Эталон из документации WAHA (Events → HMAC); сверен openssl при написании плана. */
    static final String DOC_BODY = "{\"event\":\"message\",\"session\":\"default\",\"engine\":\"WEBJS\"}";
    static final String DOC_HMAC = "208f8a55dde9e05519e898b10b89bf0d0b3b0fdf11fdbf09b6b90476301b98d8"
            + "097c462b2b17a6ce93b6b47a136cf2e78a33a63f6752c2c1631777076153fa89";

    @Test
    void documentationVectorMatches() {
        byte[] body = DOC_BODY.getBytes(StandardCharsets.UTF_8);

        assertThat(WahaSignature.sign(body, "my-secret-key")).isEqualTo(DOC_HMAC);
        assertThat(WahaSignature.verify(body, "my-secret-key", DOC_HMAC)).isTrue();
        assertThat(WahaSignature.verify(body, "my-secret-key", DOC_HMAC.toUpperCase())).isTrue();
    }

    @Test
    void anythingElseIsRejected() {
        byte[] body = DOC_BODY.getBytes(StandardCharsets.UTF_8);

        assertThat(WahaSignature.verify(body, "other-key", DOC_HMAC)).isFalse();
        assertThat(WahaSignature.verify((DOC_BODY + " ").getBytes(StandardCharsets.UTF_8), "my-secret-key", DOC_HMAC)).isFalse();
        assertThat(WahaSignature.verify(body, "my-secret-key", "zz-не-hex")).isFalse();
        assertThat(WahaSignature.verify(body, "my-secret-key", DOC_HMAC.substring(2))).isFalse();
        assertThat(WahaSignature.verify(body, "", DOC_HMAC)).isFalse();          // ключ не задан — не принимаем ничего
        assertThat(WahaSignature.verify(body, "my-secret-key", null)).isFalse();
        assertThat(WahaSignature.verify(null, "my-secret-key", DOC_HMAC)).isFalse();
    }
}
```

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaWebhookTest.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Приём вебхука WAHA через настоящую цепочку фильтров (спека whatsapp-waha §5.1, §10). Ключ подписи — системное
 * свойство теста из build.gradle: так тест живёт в ОБЩЕМ контексте, без @TestPropertySource (каждый особый контекст —
 * ещё 10 соединений nirdb, CLAUDE.md §14).
 */
@SpringBootTest
@Transactional
class WahaWebhookTest {

    static final String KEY = "test-hmac-key";
    static final String CLIENT = "77011234567@c.us";
    static final long T = 1_790_000_000L;

    @Autowired WebApplicationContext wac;
    @Autowired WahaInboxWriter writer;
    @Autowired WhatsappInboxRepository repository;
    @Autowired ObjectMapper objectMapper;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    static String raw() { return "3EB0" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(); }

    static byte[] bytes(ObjectNode env) { return env.toString().getBytes(StandardCharsets.UTF_8); }

    private ResultActions post(byte[] body, String signature, String requestId) throws Exception {
        MockHttpServletRequestBuilder r = MockMvcRequestBuilders.post(WahaWebhookController.PATH)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) r.header("X-Webhook-Hmac", signature).header("X-Webhook-Hmac-Algorithm", "sha512");
        if (requestId != null) r.header("X-Webhook-Request-Id", requestId);
        return mvc.perform(r);
    }

    /** Без входа в АИС (его зовёт WAHA внутри сети docker) — но только с верной подписью. */
    @Test
    void signedEventIsQueuedWithoutLogin() throws Exception {
        String rawId = raw();
        byte[] body = bytes(WahaJson.incomingText(CLIENT, "Айгерим", rawId, T, "Здравствуйте"));
        String rid = "req-" + rawId;

        post(body, WahaSignature.sign(body, KEY), rid).andExpect(status().isOk());

        assertThat(repository.findByRequestId(rid)).singleElement().satisfies(e -> {
            assertThat(e.getProvider()).isEqualTo("waha");
            assertThat(e.getEvent()).isEqualTo("message.any");
            assertThat(e.getMessageKey()).isEqualTo("false_" + rawId);
            assertThat(e.getStatus()).isEqualTo(WhatsappInboxStatus.PENDING);
            assertThat(e.getEventAt().toEpochSecond()).isEqualTo(T);
            assertThat(e.getPayload()).isEqualTo(new String(body, StandardCharsets.UTF_8));
        });
    }

    @Test
    void missingOrForeignSignatureIs401AndNothingQueued() throws Exception {
        String rawId = raw();
        byte[] body = bytes(WahaJson.incomingText(CLIENT, "Айгерим", rawId, T, "x"));
        byte[] tampered = bytes(WahaJson.incomingText(CLIENT, "Айгерим", rawId, T, "y"));

        post(body, null, "req-a-" + rawId).andExpect(status().isUnauthorized());
        post(body, WahaSignature.sign(body, "чужой-ключ"), "req-b-" + rawId).andExpect(status().isUnauthorized());
        post(tampered, WahaSignature.sign(body, KEY), "req-c-" + rawId).andExpect(status().isUnauthorized());

        assertThat(repository.findByMessageKey("false_" + rawId)).isEmpty();
    }

    /** Повтор WAHA приходит с тем же X-Webhook-Request-Id. */
    @Test
    void wahaRetryIsStoredOnce() throws Exception {
        byte[] body = bytes(WahaJson.sessionStatus("WORKING"));
        String sig = WahaSignature.sign(body, KEY);
        String rid = "req-" + raw();

        post(body, sig, rid).andExpect(status().isOk());
        post(body, sig, rid).andExpect(status().isOk());

        assertThat(repository.findByRequestId(rid)).singleElement()
                .satisfies(e -> assertThat(e.getMessageKey()).isNull());
    }

    /** Сообщение, пришедшее и вебхуком, и догонкой (другой конверт, без request id), лежит в очереди один раз. */
    @Test
    void sameMessageFromWebhookAndCatchUpIsStoredOnce() throws Exception {
        String rawId = raw();
        ObjectNode env = WahaJson.incomingText(CLIENT, "Айгерим", rawId, T, "x");
        byte[] body = bytes(env);
        post(body, WahaSignature.sign(body, KEY), "req-" + rawId).andExpect(status().isOk());

        ObjectNode fromHistory = WahaJson.envelope("message.any", env.get("payload").deepCopy());

        assertThat(writer.insert(fromHistory)).isFalse();
        assertThat(repository.findByMessageKey("false_" + rawId)).hasSize(1);
    }

    /** Подписано нашим ключом, но не JSON: 4xx WAHA повторяла бы 12 раз впустую — принимаем и пропускаем. */
    @Test
    void signedNonJsonIsAcknowledgedAndSkipped() throws Exception {
        byte[] body = "не json".getBytes(StandardCharsets.UTF_8);
        String rid = "req-" + raw();

        post(body, WahaSignature.sign(body, KEY), rid).andExpect(status().isOk());

        assertThat(repository.findByRequestId(rid)).isEmpty();
    }

    /** БД недоступна — 503: WAHA повторит (её повторы живут в памяти до ~4,5 ч). */
    @Test
    void databaseOutageIs503() {
        WahaInboxWriter down = new WahaInboxWriter(null, null, objectMapper) {
            @Override
            public boolean insert(JsonNode env, String requestId, String payload) {
                throw new CannotCreateTransactionException("нет соединения с базой");
            }
        };
        WahaWebhookController controller = new WahaWebhookController(down, objectMapper, KEY);
        byte[] body = bytes(WahaJson.sessionStatus("WORKING"));

        assertThat(controller.receive(WahaSignature.sign(body, KEY), "req-x", body).getStatusCode().value()).isEqualTo(503);
    }
}
```

- [ ] **Step 2: Убедиться, что красные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava` (`dangerouslyDisableSandbox: true`)
Expected: FAIL — компиляция: нет `WahaSignature`, `WahaInboxWriter`, `WahaWebhookController`, `WhatsappInboxRepository`, `WhatsappInboxStatus`.

- [ ] **Step 3: Миграция и модель**

Создать `src/main/resources/db/migration/V22__whatsapp_inbox.sql`:

```sql
-- Очередь событий шлюза WhatsApp WAHA (спека docs/superpowers/specs/2026-09-28-whatsapp-waha-design.md §4–5).
-- Вебхук только кладёт сырое событие сюда (INSERT … ON CONFLICT DO NOTHING) и сразу отвечает 200; разбирает общий
-- цикл приёма (поток whatsapp-chats) по (event_at, id). Таблица не рыночная: рынок ставится при записи чата.
CREATE TABLE whatsapp_inbox (
    id            BIGSERIAL PRIMARY KEY,
    provider      VARCHAR(20)  NOT NULL,                     -- 'waha'
    request_id    VARCHAR(100),                              -- X-Webhook-Request-Id; у догонки NULL
    message_key   VARCHAR(200),                              -- 'fromMe_rawId' у message.any (вебхук И догонка); у прочих NULL
    event         VARCHAR(40)  NOT NULL,
    event_at      TIMESTAMPTZ  NOT NULL,                     -- payload.timestamp — порядок обработки
    payload       TEXT         NOT NULL,                     -- сырой JSON события целиком
    status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING',   -- PENDING / DONE / DROPPED
    last_error    VARCHAR(500),
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_whatsapp_inbox_request ON whatsapp_inbox (provider, request_id) WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_whatsapp_inbox_message ON whatsapp_inbox (provider, message_key) WHERE message_key IS NOT NULL;
CREATE INDEX idx_whatsapp_inbox_pending ON whatsapp_inbox (event_at, id) WHERE status = 'PENDING';
```

Создать `entity/WhatsappInboxStatus.java`:

```java
package com.vladoose.nir.entity;

/** Событие шлюза WhatsApp в очереди: ждёт разбора / разобрано / пропущено как «ядовитое» (спека whatsapp-waha §5.2). */
public enum WhatsappInboxStatus { PENDING, DONE, DROPPED }
```

Создать `entity/WhatsappInboxEvent.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Сырое событие шлюза WhatsApp в очереди (спека whatsapp-waha §4–5). Вставляется только WahaInboxWriter'ом
 * (INSERT … ON CONFLICT), читается циклом приёма по (event_at, id). Не рыночная: рынок ставится при записи чата.
 */
@Entity
@Table(name = "whatsapp_inbox")
@Getter @Setter @NoArgsConstructor
public class WhatsappInboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String provider;

    /** X-Webhook-Request-Id — одинаков во всех повторах WAHA; у догонки null. */
    @Column(name = "request_id", length = 100)
    private String requestId;

    /** «fromMe_rawId» у message.any (вебхук и догонка); у прочих событий null. */
    @Column(name = "message_key", length = 200)
    private String messageKey;

    @Column(nullable = false, length = 40)
    private String event;

    @Column(name = "event_at", nullable = false)
    private OffsetDateTime eventAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WhatsappInboxStatus status;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
```

Создать `repository/WhatsappInboxRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.WhatsappInboxEvent;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface WhatsappInboxRepository extends JpaRepository<WhatsappInboxEvent, Long> {

    /** Следующее к разбору: самое раннее из «отлежавшихся» до settled (спека §5.2: правка не обгонит оригинал). */
    @Query(value = """
            SELECT * FROM whatsapp_inbox
            WHERE status = 'PENDING' AND provider = :provider AND event_at <= :settled
            ORDER BY event_at, id
            LIMIT 1""", nativeQuery = true)
    Optional<WhatsappInboxEvent> findNextPending(@Param("provider") String provider, @Param("settled") OffsetDateTime settled);

    @Modifying
    @Query("delete from WhatsappInboxEvent e where e.status = :status and e.processedAt < :before")
    int deleteProcessedBefore(@Param("status") WhatsappInboxStatus status, @Param("before") OffsetDateTime before);

    List<WhatsappInboxEvent> findByRequestId(String requestId);

    List<WhatsappInboxEvent> findByMessageKey(String messageKey);
}
```

- [ ] **Step 4: Подпись и запись в очередь**

Создать `integration/waha/WahaSignature.java`:

```java
package com.vladoose.nir.integration.waha;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Подпись вебхука WAHA: hex(HMAC-SHA512(сырое тело, ключ)), заголовки не подписываются (спека whatsapp-waha §2).
 * Сравнение — за постоянное время. Пустой ключ не принимает ничего: неподписанных событий не бывает.
 */
public final class WahaSignature {

    private static final String ALGORITHM = "HmacSHA512";

    private WahaSignature() {}

    public static String sign(byte[] body, String key) {
        return HexFormat.of().formatHex(mac(body, key));
    }

    public static boolean verify(byte[] body, String key, String header) {
        if (body == null || key == null || key.isEmpty() || header == null || header.isBlank()) return false;
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(header.strip().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(mac(body, key), actual);
    }

    private static byte[] mac(byte[] body, String key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA512 недоступен в JDK", e);
        }
    }
}
```

Создать `integration/waha/WahaInboxWriter.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import com.vladoose.nir.integration.whatsapp.WhatsappProviders;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static com.vladoose.nir.util.LeadText.trunc;

/**
 * Запись в очередь whatsapp_inbox (спека whatsapp-waha §4–5). Вставка — одним INSERT … ON CONFLICT DO NOTHING: повтор
 * WAHA (тот же X-Webhook-Request-Id) и то же сообщение из вебхука и догонки (ключ «fromMe_rawId») не дублируются, а
 * гонки «проверил — вставил» нет вовсе. JdbcTemplate, а не нативный запрос JPA: null-параметры (request_id у догонки)
 * Hibernate связывает с неопределённым типом.
 */
@Service
public class WahaInboxWriter {

    private static final String INSERT = """
            INSERT INTO whatsapp_inbox (provider, request_id, message_key, event, event_at, payload)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING""";

    private final JdbcTemplate jdbc;
    private final WhatsappInboxRepository repository;
    private final ObjectMapper objectMapper;

    public WahaInboxWriter(JdbcTemplate jdbc, WhatsappInboxRepository repository, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Событие вебхука; payload — сырое тело как пришло. false — такое уже лежит в очереди. */
    public boolean insert(JsonNode env, String requestId, String payload) {
        String event = env.path("event").asText("");
        String rid = requestId == null || requestId.isBlank() ? null : trunc(requestId.strip(), 100);
        return jdbc.update(INSERT, WhatsappProviders.WAHA, rid, WahaEventParser.messageKey(env),
                trunc(event.isEmpty() ? "?" : event, 40), WahaEventParser.eventAt(env), payload) == 1;
    }

    /** Синтетическое событие догонки: тело — сериализованный конверт, request_id нет. */
    public boolean insert(JsonNode env) {
        try {
            return insert(env, null, objectMapper.writeValueAsString(env));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("событие догонки не сериализовано", e);
        }
    }

    @Transactional
    public void finish(long id, WhatsappInboxStatus status, String error) {
        repository.findById(id).ifPresent(e -> {
            e.setStatus(status);
            e.setLastError(trunc(error, 500));
            e.setProcessedAt(OffsetDateTime.now());
        });
    }

    /** Уборка (спека §5.4): DONE — старше doneBefore, DROPPED — старше droppedBefore; PENDING не трогаем никогда. */
    @Transactional
    public int cleanup(OffsetDateTime doneBefore, OffsetDateTime droppedBefore) {
        return repository.deleteProcessedBefore(WhatsappInboxStatus.DONE, doneBefore)
                + repository.deleteProcessedBefore(WhatsappInboxStatus.DROPPED, droppedBefore);
    }
}
```

- [ ] **Step 5: Контроллер вебхука**

Создать `integration/waha/WahaWebhookController.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.whatsapp.WhatsappChatSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Приём событий WAHA (спека whatsapp-waha §5.1): подпись → очередь → 200. Ни разбора, ни сети: у WAHA нет таймаута,
 * а медленный ответ задерживает её следующие события. Повторы WAHA живут только в её памяти — поэтому пишем сразу в
 * БД. Без входа в АИС (permitAll в SecurityConfig): зовёт только WAHA внутри сети docker; снаружи путь закрыт в nginx.
 */
@RestController
public class WahaWebhookController {

    public static final String PATH = "/api/whatsapp/waha/webhook";
    private static final Logger log = LoggerFactory.getLogger(WahaWebhookController.class);

    private final WahaInboxWriter inbox;
    private final ObjectMapper objectMapper;
    private final String hmacKey;

    public WahaWebhookController(WahaInboxWriter inbox, ObjectMapper objectMapper,
                                 @Value("${chats.whatsapp.waha.hmac-key:}") String hmacKey) {
        this.inbox = inbox;
        this.objectMapper = objectMapper;
        this.hmacKey = hmacKey;
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Webhook-Hmac", required = false) String hmac,
                                        @RequestHeader(value = "X-Webhook-Request-Id", required = false) String requestId,
                                        @RequestBody(required = false) byte[] body) {
        if (!WahaSignature.verify(body, hmacKey, hmac)) {
            log.warn("WAHA: вебхук отклонён — {} ({} байт)", hmac == null ? "нет подписи" : "подпись не сходится",
                    body == null ? 0 : body.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode env;
        try {
            env = objectMapper.readTree(body);
        } catch (IOException e) {
            env = null;
        }
        if (env == null || !env.isObject()) {
            // подписано нашим ключом, но не JSON: 4xx WAHA повторяла бы 12 раз впустую
            log.warn("WAHA: подписанный вебхук — не JSON-объект ({} байт), пропущен", body.length);
            return ResponseEntity.ok().build();
        }
        try {
            inbox.insert(env, requestId, new String(body, StandardCharsets.UTF_8));
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            if (WhatsappChatSync.isInfrastructureFailure(e)) {
                log.warn("WAHA: база недоступна — событие не записано, WAHA повторит ({})", e.getClass().getSimpleName());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            log.error("WAHA: событие не записано в очередь", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
```

- [ ] **Step 6: Доступ, nginx, настройки, тестовый ключ**

`config/SecurityConfig.java` — перед строкой `.requestMatchers("/api/**").authenticated()` вставить (плюс импорт `com.vladoose.nir.integration.waha.WahaWebhookController`):

```java
                        // вебхук WAHA: зовёт только сама WAHA внутри сети docker; защита — подпись HMAC (спека whatsapp-waha §10)
                        .requestMatchers(HttpMethod.POST, WahaWebhookController.PATH).permitAll()
```

`frontend/nginx.conf` — перед `location /api/ {` вставить:

```nginx
    # вебхук WAHA зовёт только сама WAHA внутри сети docker (ais-waha → ais-backend:8080); снаружи — нет такого пути
    location = /api/whatsapp/waha/webhook { return 404; }

```

`src/main/resources/application.yaml` — в конец блока `chats.whatsapp` (после `lead-converted-days: 30`) добавить:

```yaml
    waha:                                          # спека whatsapp-waha §11
      url: ${WHATSAPP_WAHA_URL:http://ais-waha:3000}
      api-key: ${WHATSAPP_WAHA_API_KEY:}           # секрет: только env
      hmac-key: ${WHATSAPP_WAHA_HMAC_KEY:}         # секрет: только env; подпись вебхука
      session: ${WHATSAPP_WAHA_SESSION:westmed}
      settle-ms: 3000                              # событие «отлёживается», чтобы правка не обогнала оригинал
      catch-up-ms: 600000
      catch-up-overlap-min: 10
      status-refresh-ms: 60000
      inbox-done-days: 7
      inbox-dropped-days: 30
```

`build.gradle` — блок `tasks.named('test')` заменить:

```groovy
tasks.named('test') {
    useJUnitPlatform()
    // ключ подписи вебхука WAHA для HTTP-тестов: так они идут в ОБЩЕМ контексте, без @TestPropertySource
    // (каждый особый контекст — ещё 10 соединений к nirdb, CLAUDE.md §14)
    systemProperty 'chats.whatsapp.waha.hmac-key', 'test-hmac-key'
}
```

- [ ] **Step 7: Прогнать**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew cleanTest test --tests 'com.vladoose.nir.integration.waha.*'` (`dangerouslyDisableSandbox: true`)
Expected: PASS (Flyway накатывает V22 на nirdb при первом старте контекста — это нормально, откатывать миграцию не нужно).

- [ ] **Step 8: Мутации (по одной, каждая роняет ровно свой тест)**

1. `WahaSignature.verify` — первой строкой `return true;` → FAIL `missingOrForeignSignatureIs401AndNothingQueued` (и `anythingElseIsRejected`). Вернуть.
2. `WahaEventParser.messageKey` — первой строкой `return null;` → FAIL `sameMessageFromWebhookAndCatchUpIsStoredOnce` (и `queueKeyAndEventTime`). Вернуть.
3. В `WahaInboxWriter.INSERT` убрать строку `ON CONFLICT DO NOTHING` → FAIL `wahaRetryIsStoredOnce` (второй POST — 500). Вернуть.

После каждой: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.waha.*'`; после возврата — PASS.

- [ ] **Step 9: nginx фронта не сломан**

Run: `cd /Users/vlad/IdeaProjects/AIS && docker run --rm -v "$PWD/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" --add-host ais-backend:127.0.0.1 nginx:1.27-alpine nginx -t` (`dangerouslyDisableSandbox: true`)
Expected: `syntax is ok` / `test is successful`. Docker не отвечает — `colima start --cpu 1 --memory 1` и `DOCKER_CONTEXT=colima` (CLAUDE.md §14; файл уже в домашнем каталоге — монтируется).

- [ ] **Step 10: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V22__whatsapp_inbox.sql \
  src/main/java/com/vladoose/nir/entity/WhatsappInboxStatus.java src/main/java/com/vladoose/nir/entity/WhatsappInboxEvent.java \
  src/main/java/com/vladoose/nir/repository/WhatsappInboxRepository.java src/main/java/com/vladoose/nir/integration/waha \
  src/main/java/com/vladoose/nir/config/SecurityConfig.java frontend/nginx.conf src/main/resources/application.yaml build.gradle \
  src/test/java/com/vladoose/nir/integration/waha
git commit -m "feat(waha): очередь whatsapp_inbox (V22) и вебхук с подписью HMAC — запись без разбора, дубли отсекает ON CONFLICT

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: HTTP-клиент WAHA

Общий для обоих шлюзов обмен с дедлайном на весь ответ и поток байтов с обрывом на пределе выносятся из `GreenApiHttpClient` в пакет `whatsapp`; поверх них — клиент WAHA.

**Files:**
- Create: `integration/whatsapp/GatewayHttp.java`, `integration/whatsapp/LimitedBytes.java`, `integration/waha/WahaClient.java`, `WahaSession.java`, `WahaContact.java`, `WahaHttpClient.java`
- Modify: `integration/greenapi/GreenApiHttpClient.java`
- Test: `src/test/java/com/vladoose/nir/integration/waha/WahaHttpClientTest.java` (Green-API — существующий `GreenApiHttpClientTest` должен остаться зелёным)

**Interfaces:**
- Consumes: `GatewayException`, `GatewayAuthException`, `FileTooLargeException` (задача 1), `WahaEventParser.userOf` (задача 3).
- Produces:
  - `GatewayHttp.exchange(HttpClient http, HttpRequest req, HttpResponse.BodyHandler<T> handler, Duration deadline, String gateway, String what) → HttpResponse<T>` — тексты «{gateway} не ответил за N с при {what}», «{gateway} недоступен при {what}: {Класс}», «{gateway}: запрос не отправлен…», «{gateway}: запрос прерван…»; `FileTooLargeException` пробрасывается как есть.
  - `public final class LimitedBytes implements HttpResponse.BodySubscriber<byte[]>` (`new LimitedBytes(long maxBytes)`).
  - `public record WahaSession(String name, String status, String meId, String mePushName)`; `public record WahaContact(String id, String name, String pushname)`.
  - `public interface WahaClient { boolean isConfigured(); WahaSession session(String name); void createSession(String name, String market); void startSession(String name); void restartSession(String name); void logoutSession(String name); byte[] qrPng(String session); JsonNode message(String session, String chatId, String messageId, boolean downloadMedia); byte[] downloadFile(String url, long maxBytes); List<JsonNode> history(String session, long fromEpochSec, int limit, int offset); WahaContact contact(String session, String contactId); String groupSubject(String session, String groupId); String lidPhone(String session, String lid); }` — `session`/`contact`/`groupSubject`/`lidPhone` отдают `null` на 404.
  - `WahaHttpClient(ObjectMapper, String url, String apiKey)` (+ пакетный конструктор с дедлайнами `(…, Duration callDeadline, Duration longDeadline)` для тестов).

- [ ] **Step 1: Тесты клиента (красные)**

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaHttpClientTest.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.whatsapp.FileTooLargeException;
import com.vladoose.nir.integration.whatsapp.GatewayAuthException;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/** Стаб WAHA на JDK HttpServer, без сети (спека whatsapp-waha §2, §12). */
class WahaHttpClientTest {

    static final String KEY = "waha-key-secret-123";
    static HttpServer server;
    static int port;
    /** "METHOD path?query" */
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final List<String> keys = new CopyOnWriteArrayList<>();
    static final List<String> bodies = new CopyOnWriteArrayList<>();
    /** "METHOD path" (без query) → ответ; нет — 404. */
    static final Map<String, Resp> routes = new ConcurrentHashMap<>();
    /** Заголовки и начало тела — и тишина. */
    static volatile boolean stall;

    record Resp(int status, String contentType, byte[] body) {
        static Resp json(int status, String json) {
            return new Resp(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());   // «зависший» ответ не держит остальные тесты
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            calls.add(ex.getRequestMethod() + " " + path + (query != null ? "?" + query : ""));
            keys.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Api-Key")));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (stall) {
                ex.sendResponseHeaders(200, 100);
                OutputStream os = ex.getResponseBody();
                os.write("{\"na".getBytes(StandardCharsets.UTF_8));
                os.flush();
                try { Thread.sleep(8000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                ex.close();
                return;
            }
            Resp r = routes.getOrDefault(ex.getRequestMethod() + " " + path, new Resp(404, "application/json", new byte[0]));
            if (r.contentType() != null) ex.getResponseHeaders().add("Content-Type", r.contentType());
            ex.sendResponseHeaders(r.status(), r.body().length == 0 ? -1 : r.body().length);
            if (r.body().length > 0) {
                try (OutputStream os = ex.getResponseBody()) { os.write(r.body()); }
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
        keys.clear();
        bodies.clear();
        routes.clear();
        stall = false;
    }

    static String base() { return "http://localhost:" + port; }

    static WahaHttpClient client() { return new WahaHttpClient(new ObjectMapper(), base() + "/", KEY); }

    @Test
    void sessionStatusAndMeWithKeyInHeaderOnly() {
        routes.put("GET /api/sessions/westmed", Resp.json(200,
                "{\"name\":\"westmed\",\"status\":\"WORKING\",\"me\":{\"id\":\"77000000001@c.us\",\"pushName\":\"West-Med\"}}"));

        assertThat(client().session("westmed")).isEqualTo(new WahaSession("westmed", "WORKING", "77000000001@c.us", "West-Med"));
        assertThat(keys).containsExactly(KEY);
        assertThat(calls).singleElement().asString().doesNotContain(KEY);
    }

    @Test
    void missingSessionIsNull() {
        assertThat(client().session("westmed")).isNull();
    }

    @Test
    void createSessionSendsMarketAndIgnoreConfig() throws Exception {
        routes.put("POST /api/sessions", Resp.json(201, "{\"name\":\"westmed\",\"status\":\"STARTING\"}"));

        client().createSession("westmed", "KZ");

        JsonNode body = new ObjectMapper().readTree(bodies.get(0));
        assertThat(body.path("name").asText()).isEqualTo("westmed");
        assertThat(body.path("start").asBoolean()).isTrue();
        assertThat(body.at("/config/metadata/market").asText()).isEqualTo("KZ");
        assertThat(body.at("/config/ignore/status").asBoolean()).isTrue();
        assertThat(body.at("/config/ignore/channels").asBoolean()).isTrue();
        assertThat(body.at("/config/ignore/broadcast").asBoolean()).isTrue();
        assertThat(body.at("/config/ignore/groups").asBoolean()).isFalse();
    }

    /** Сессию уже создал параллельный старт АИС — не ошибка. */
    @Test
    void createSessionToleratesExisting() {
        routes.put("POST /api/sessions", Resp.json(422, "{\"message\":\"Session already exists\"}"));

        assertThatCode(() -> client().createSession("westmed", "KZ")).doesNotThrowAnyException();
    }

    @Test
    void startRestartLogoutHitTheirPaths() {
        routes.put("POST /api/sessions/westmed/start", Resp.json(201, "{}"));
        routes.put("POST /api/sessions/westmed/restart", Resp.json(201, "{}"));
        routes.put("POST /api/sessions/westmed/logout", Resp.json(201, "{}"));
        WahaHttpClient c = client();

        c.startSession("westmed");
        c.restartSession("westmed");
        c.logoutSession("westmed");

        assertThat(calls).containsExactly("POST /api/sessions/westmed/start", "POST /api/sessions/westmed/restart",
                "POST /api/sessions/westmed/logout");
    }

    @Test
    void qrComesAsPngOrBase64Json() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        routes.put("GET /api/westmed/auth/qr", new Resp(200, "image/png", png));
        assertThat(client().qrPng("westmed")).containsExactly(png);

        routes.put("GET /api/westmed/auth/qr", Resp.json(200,
                "{\"mimetype\":\"image/png\",\"data\":\"" + Base64.getEncoder().encodeToString(png) + "\"}"));
        assertThat(client().qrPng("westmed")).containsExactly(png);
    }

    @Test
    void messageByIdEncodesSegmentsAndAsksForMedia() {
        String id = "false_77011234567@c.us_3EB0A1";
        routes.put("GET /api/westmed/chats/77011234567%40c.us/messages/false_77011234567%40c.us_3EB0A1",
                Resp.json(200, "{\"id\":\"" + id + "\",\"media\":{\"url\":\"" + base() + "/api/files/westmed/a.pdf\"}}"));

        JsonNode m = client().message("westmed", "77011234567@c.us", id, true);

        assertThat(m.at("/media/url").asText()).endsWith("/api/files/westmed/a.pdf");
        assertThat(calls).singleElement().asString().endsWith("?downloadMedia=true");
    }

    @Test
    void historyPagesFromTimestamp() {
        routes.put("GET /api/westmed/chats/all/messages", Resp.json(200, "[{\"id\":\"a\"},{\"id\":\"b\"}]"));

        List<JsonNode> page = client().history("westmed", 1_790_000_000L, 100, 200);

        assertThat(page).extracting(n -> n.path("id").asText()).containsExactly("a", "b");
        assertThat(calls.get(0)).contains("filter.timestamp.gte=1790000000").contains("sortBy=timestamp")
                .contains("sortOrder=asc").contains("limit=100").contains("offset=200").contains("downloadMedia=false");
    }

    /** Файл — только с адреса самой WAHA: ключ API уходит в заголовке, чужому хосту его не отдаём. */
    @Test
    void fileOnlyFromWahaItselfAndCutAtLimit() {
        routes.put("GET /api/files/westmed/a.pdf", new Resp(200, "application/pdf", new byte[]{1, 2, 3, 4, 5}));
        WahaHttpClient c = client();

        assertThat(c.downloadFile(base() + "/api/files/westmed/a.pdf", 10)).containsExactly(1, 2, 3, 4, 5);
        assertThat(keys).containsExactly(KEY);
        assertThatThrownBy(() -> c.downloadFile(base() + "/api/files/westmed/a.pdf", 4)).isInstanceOf(FileTooLargeException.class);
        calls.clear();
        assertThatThrownBy(() -> c.downloadFile("http://files.example/a.pdf", 10))
                .isInstanceOf(GatewayException.class).hasMessageContaining("не на WAHA");
        assertThat(calls).isEmpty();
    }

    @Test
    void contactsGroupsAndHiddenNumbers() {
        routes.put("GET /api/contacts", Resp.json(200,
                "{\"id\":\"77011234567@c.us\",\"number\":\"77011234567\",\"name\":\"Айгерим (клиника)\",\"pushname\":\"Aigerim\"}"));
        routes.put("GET /api/westmed/groups/120363000000000001%40g.us",
                Resp.json(200, "{\"id\":\"120363000000000001@g.us\",\"subject\":\"Коллеги\"}"));
        routes.put("GET /api/westmed/lids/123456789012345%40lid",
                Resp.json(200, "{\"lid\":\"123456789012345@lid\",\"pn\":\"77012223344@c.us\"}"));
        WahaHttpClient c = client();

        assertThat(c.contact("westmed", "77011234567@c.us"))
                .isEqualTo(new WahaContact("77011234567@c.us", "Айгерим (клиника)", "Aigerim"));
        assertThat(calls.get(0)).isEqualTo("GET /api/contacts?contactId=77011234567%40c.us&session=westmed");
        assertThat(c.groupSubject("westmed", "120363000000000001@g.us")).isEqualTo("Коллеги");
        assertThat(c.lidPhone("westmed", "123456789012345@lid")).isEqualTo("+77012223344");
        assertThat(c.groupSubject("westmed", "120363000000000009@g.us")).isNull();
        assertThat(c.lidPhone("westmed", "999@lid")).isNull();
    }

    @Test
    void rejectedKeyIsAuthErrorWithoutKeyOrAddress() {
        routes.put("GET /api/sessions/westmed", Resp.json(401, "{\"message\":\"Unauthorized\"}"));

        assertThatThrownBy(() -> client().session("westmed"))
                .isInstanceOf(GatewayAuthException.class)
                .hasMessageContaining("401").hasMessageNotContaining(KEY).hasMessageNotContaining("localhost");
    }

    @Test
    void serverErrorKeepsStatusWithoutAddress() {
        routes.put("POST /api/sessions/westmed/restart", Resp.json(500, "{}"));

        assertThatThrownBy(() -> client().restartSession("westmed"))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("localhost");
                });
    }

    @Test
    void unreachableIsStatusZeroWithoutKeyOrAddress() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) { free = s.getLocalPort(); }
        WahaHttpClient c = new WahaHttpClient(new ObjectMapper(), "http://localhost:" + free, KEY);

        assertThatThrownBy(() -> c.session("westmed"))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.status()).isZero();
                    assertThat(e.getMessage()).startsWith("WAHA недоступен").doesNotContain(KEY).doesNotContain(String.valueOf(free));
                });
    }

    /** Таймаут HttpRequest в JDK 17 снимается на заголовках: вставшее тело держало бы поток приёма вечно. */
    @Test
    void stalledBodyIsCutOffByDeadline() {
        stall = true;
        WahaHttpClient c = new WahaHttpClient(new ObjectMapper(), base(), KEY, Duration.ofSeconds(1), Duration.ofSeconds(2));
        long t0 = System.nanoTime();

        assertThatThrownBy(() -> c.session("westmed")).isInstanceOf(GatewayException.class).hasMessageContaining("не ответил");
        assertThatThrownBy(() -> c.downloadFile(base() + "/api/files/westmed/a.pdf", 1000)).isInstanceOf(GatewayException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void missingKeyIsNotConfiguredAndNoNetwork() {
        WahaHttpClient c = new WahaHttpClient(new ObjectMapper(), base(), "");

        assertThat(c.isConfigured()).isFalse();
        assertThatThrownBy(() -> c.session("westmed")).isInstanceOf(GatewayAuthException.class).hasMessageContaining("не заданы");
        assertThat(calls).isEmpty();
    }

    /** Ключ с переводом строки (кривой .env): текст исключения JDK мог бы нести значение заголовка — наружу только своё. */
    @Test
    void malformedKeyNeverLeaksIntoErrorText() {
        WahaHttpClient c = new WahaHttpClient(new ObjectMapper(), base(), "waha-secret\nX-Evil: 1");

        assertThatThrownBy(() -> c.session("westmed"))
                .isInstanceOf(GatewayAuthException.class).hasMessageNotContaining("waha-secret");
        assertThat(calls).isEmpty();
    }
}
```

- [ ] **Step 2: Убедиться, что красные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava` (`dangerouslyDisableSandbox: true`)
Expected: FAIL — нет `WahaHttpClient`, `WahaSession`, `WahaContact`.

- [ ] **Step 3: Общий обмен и поток с пределом**

Создать `integration/whatsapp/GatewayHttp.java`:

```java
package com.vladoose.nir.integration.whatsapp;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP-обмен со шлюзом WhatsApp с дедлайном на ВЕСЬ ответ (заголовки И тело): таймаут HttpRequest в JDK 17 снимается,
 * как только пришли заголовки, и тело, вставшее посередине, держало бы единственный поток приёма вечно. Тексты ошибок —
 * только название операции и класс исключения: текст части исключений JDK содержит адрес (у Green-API в нём токен).
 */
public final class GatewayHttp {

    private GatewayHttp() {}

    public static <T> HttpResponse<T> exchange(HttpClient http, HttpRequest req, HttpResponse.BodyHandler<T> handler,
                                               Duration deadline, String gateway, String what) {
        CompletableFuture<HttpResponse<T>> f;
        try {
            f = http.sendAsync(req, handler);
        } catch (RuntimeException e) {
            throw new GatewayException(0, gateway + ": запрос не отправлен при " + what + ": " + e.getClass().getSimpleName());
        }
        try {
            return f.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new GatewayException(0, gateway + " не ответил за " + deadline.toSeconds() + " с при " + what);
        } catch (ExecutionException e) {
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                if (c instanceof FileTooLargeException tooLarge) throw tooLarge;
            }
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new GatewayException(0, gateway + " недоступен при " + what + ": " + c.getClass().getSimpleName());
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new GatewayException(0, gateway + ": запрос прерван при " + what);
        }
    }
}
```

Создать `integration/whatsapp/LimitedBytes.java`:

```java
package com.vladoose.nir.integration.whatsapp;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Байты тела с обрывом на пределе — внутри обмена, чтобы дедлайн GatewayHttp покрывал и чтение тела. */
public final class LimitedBytes implements HttpResponse.BodySubscriber<byte[]> {

    private final long maxBytes;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    private long total;

    public LimitedBytes(long maxBytes) { this.maxBytes = maxBytes; }

    @Override
    public CompletionStage<byte[]> getBody() { return result; }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (result.isDone()) return;
        for (ByteBuffer b : items) {
            total += b.remaining();
            if (total > maxBytes) {
                // сперва исход, потом отмена: onError от отмены не должен успеть превратить «больше предела»
                // в «не скачался» (тогда были бы три лишних скачивания и DOWNLOAD_FAILED вместо TOO_LARGE)
                result.completeExceptionally(new FileTooLargeException(maxBytes));
                subscription.cancel();
                return;
            }
            byte[] chunk = new byte[b.remaining()];
            b.get(chunk);
            out.write(chunk, 0, chunk.length);
        }
    }

    @Override
    public void onError(Throwable t) { result.completeExceptionally(t); }

    @Override
    public void onComplete() { result.complete(out.toByteArray()); }
}
```

В `integration/greenapi/GreenApiHttpClient.java`:
- тело приватного метода `exchange(HttpRequest req, HttpResponse.BodyHandler<T> handler, Duration deadline, String what)` заменить одной строкой `return GatewayHttp.exchange(http, req, handler, deadline, "Green-API", what);` (сигнатура и javadoc метода остаются);
- удалить вложенный класс `LimitedBytes` целиком (`new LimitedBytes(maxBytes)` в `download` теперь берёт класс из `integration.whatsapp` — импорт `whatsapp.*` уже есть с задачи 1);
- удалить ставшие лишними импорты: `java.io.ByteArrayOutputStream`, `java.nio.ByteBuffer`, `java.util.List`, `java.util.concurrent.CompletableFuture`, `CompletionStage`, `ExecutionException`, `Flow`, `TimeUnit`, `TimeoutException`.

- [ ] **Step 4: Клиент WAHA**

Создать `integration/waha/WahaSession.java`:

```java
package com.vladoose.nir.integration.waha;

/** Сессия WAHA: status — STOPPED / STARTING / SCAN_QR_CODE / WORKING / FAILED / PASSKEY_*; meId — «7700…@c.us» после привязки. */
public record WahaSession(String name, String status, String meId, String mePushName) {}
```

Создать `integration/waha/WahaContact.java`:

```java
package com.vladoose.nir.integration.waha;

/** Контакт WAHA: name — из записной книжки рабочего телефона, pushname — из профиля WhatsApp самого человека. */
public record WahaContact(String id, String name, String pushname) {}
```

Создать `integration/waha/WahaClient.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** WAHA (спека whatsapp-waha §2). Интерфейс — ради фейка в тестах (как GreenApiClient, WestmedClient). */
public interface WahaClient {

    boolean isConfigured();

    /** null — такой сессии нет. */
    WahaSession session(String name);

    /** Создать и сразу запустить: config.metadata.market, ignore историй/каналов/рассылок (спека §7). */
    void createSession(String name, String market);

    void startSession(String name);

    void restartSession(String name);

    /** Отвязать номер: авторизация удаляется, сессия стартует заново с новым QR. */
    void logoutSession(String name);

    /** QR-код привязки (PNG). Сессия не ждёт привязки — GatewayException. */
    byte[] qrPng(String session);

    /** Одно сообщение; downloadMedia=true — WAHA скачивает файл к себе и кладёт ссылку в media.url. */
    JsonNode message(String session, String chatId, String messageId, boolean downloadMedia);

    /** Байты по media.url — только с адреса самой WAHA; больше maxBytes → FileTooLargeException. */
    byte[] downloadFile(String url, long maxBytes);

    /** Сообщения всех чатов не раньше fromEpochSec, по времени, страницей (спека §5.3). */
    List<JsonNode> history(String session, long fromEpochSec, int limit, int offset);

    /** Контакт; null — WAHA его не знает. */
    WahaContact contact(String session, String contactId);

    /** Тема группы; null — неизвестна. */
    String groupSubject(String session, String groupId);

    /** Телефон «+7…» за скрытым номером …@lid; null — WAHA его не знает. */
    String lidPhone(String session, String lid);
}
```

Создать `integration/waha/WahaHttpClient.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.integration.whatsapp.GatewayAuthException;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.GatewayHttp;
import com.vladoose.nir.integration.whatsapp.LimitedBytes;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * HTTP к WAHA внутри сети docker (спека whatsapp-waha §2). Ключ — в заголовке X-Api-Key; тексты ошибок — только название
 * операции и класс исключения: ни ключа, ни адреса (в адресе бывают номера клиентов). Каждый обмен — с дедлайном на весь
 * ответ (GatewayHttp). Файл берём только с адреса самой WAHA: ключ уходит в заголовке.
 */
@Component
public class WahaHttpClient implements WahaClient {

    private static final String GATEWAY = "WAHA";
    private static final int QR_MAX_BYTES = 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)     // ключ не должен уйти за перенаправлением
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    /** Обычный вызов API — не дольше; скачивание файла WAHA'ой, сам файл и страница истории — не дольше longDeadline. */
    private final Duration callDeadline;
    private final Duration longDeadline;

    @Autowired
    public WahaHttpClient(ObjectMapper objectMapper,
                          @Value("${chats.whatsapp.waha.url:http://ais-waha:3000}") String url,
                          @Value("${chats.whatsapp.waha.api-key:}") String apiKey) {
        this(objectMapper, url, apiKey, Duration.ofSeconds(20), Duration.ofSeconds(90));
    }

    /** Короткие дедлайны — для тестов «зависшего» ответа. */
    WahaHttpClient(ObjectMapper objectMapper, String url, String apiKey, Duration callDeadline, Duration longDeadline) {
        this.objectMapper = objectMapper;
        String u = url == null ? "" : url.strip();
        this.baseUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.callDeadline = callDeadline;
        this.longDeadline = longDeadline;
    }

    @Override
    public boolean isConfigured() { return !baseUrl.isEmpty() && !apiKey.isEmpty(); }

    @Override
    public WahaSession session(String name) {
        String what = "запросе состояния сессии";
        HttpResponse<String> r = call("GET", "/api/sessions/" + seg(name), null, callDeadline, what, 404);
        if (r.statusCode() == 404) return null;
        JsonNode s = json(r.body(), what);
        JsonNode me = s.path("me");
        return new WahaSession(s.path("name").asText(name), s.path("status").asText(""), text(me, "id"), text(me, "pushName"));
    }

    @Override
    public void createSession(String name, String market) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("start", true);
        ObjectNode config = body.putObject("config");
        config.putObject("metadata").put("market", market);
        // истории, каналы и рассылки — не переписка; группы нужны (фильтр «Группы» на «Чатах»)
        config.putObject("ignore").put("status", true).put("channels", true).put("broadcast", true).put("groups", false);
        // 409/422 — сессия уже есть (гонка двух стартов АИС) — это нас устраивает
        call("POST", "/api/sessions", body.toString(), callDeadline, "создании сессии", 409, 422);
    }

    @Override
    public void startSession(String name) {
        call("POST", "/api/sessions/" + seg(name) + "/start", null, callDeadline, "запуске сессии");
    }

    @Override
    public void restartSession(String name) {
        call("POST", "/api/sessions/" + seg(name) + "/restart", null, callDeadline, "перезапуске сессии");
    }

    @Override
    public void logoutSession(String name) {
        call("POST", "/api/sessions/" + seg(name) + "/logout", null, callDeadline, "отвязке номера");
    }

    @Override
    public byte[] qrPng(String session) {
        String what = "получении QR-кода";
        HttpRequest req = request("/api/" + seg(session) + "/auth/qr?format=image", callDeadline, "image/png").GET().build();
        HttpResponse<byte[]> r = GatewayHttp.exchange(http, req, info -> info.statusCode() / 100 == 2
                ? new LimitedBytes(QR_MAX_BYTES) : HttpResponse.BodySubscribers.replacing(new byte[0]), callDeadline, GATEWAY, what);
        check(r.statusCode(), what);
        String type = r.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/json")) return r.body();
        // часть версий отдаёт {mimetype, data: base64}
        String data = json(new String(r.body(), StandardCharsets.UTF_8), what).path("data").asText("");
        if (data.isEmpty()) throw new GatewayException(200, "WAHA: QR-код не пришёл");
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new GatewayException(200, "WAHA: QR-код не разобран");
        }
    }

    @Override
    public JsonNode message(String session, String chatId, String messageId, boolean downloadMedia) {
        String what = downloadMedia ? "подготовке файла сообщения" : "запросе сообщения";
        HttpResponse<String> r = call("GET", "/api/" + seg(session) + "/chats/" + seg(chatId) + "/messages/" + seg(messageId)
                + "?downloadMedia=" + downloadMedia, null, downloadMedia ? longDeadline : callDeadline, what);
        return json(r.body(), what);
    }

    @Override
    public byte[] downloadFile(String url, long maxBytes) {
        String what = "скачивании файла";
        requireConfigured();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new GatewayException(0, "WAHA: некорректная ссылка на файл");
        }
        if (!sameOrigin(uri)) throw new GatewayException(0, "WAHA: ссылка на файл ведёт не на WAHA — не скачиваем");
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(uri).timeout(longDeadline).header("X-Api-Key", apiKey).GET().build();
        } catch (IllegalArgumentException e) {
            throw new GatewayException(0, "WAHA: некорректная ссылка на файл");
        }
        HttpResponse<byte[]> r = GatewayHttp.exchange(http, req, info -> info.statusCode() / 100 == 2
                ? new LimitedBytes(maxBytes) : HttpResponse.BodySubscribers.replacing(new byte[0]), longDeadline, GATEWAY, what);
        check(r.statusCode(), what);
        return r.body();
    }

    @Override
    public List<JsonNode> history(String session, long fromEpochSec, int limit, int offset) {
        String what = "догонке по истории";
        HttpResponse<String> r = call("GET", "/api/" + seg(session) + "/chats/all/messages?filter.timestamp.gte=" + fromEpochSec
                + "&sortBy=timestamp&sortOrder=asc&limit=" + limit + "&offset=" + offset + "&downloadMedia=false",
                null, longDeadline, what);
        JsonNode arr = json(r.body(), what);
        if (!arr.isArray()) throw new GatewayException(200, "WAHA: история пришла не списком");
        List<JsonNode> out = new ArrayList<>(arr.size());
        arr.forEach(out::add);
        return out;
    }

    @Override
    public WahaContact contact(String session, String contactId) {
        String what = "запросе контакта";
        HttpResponse<String> r = call("GET", "/api/contacts?contactId=" + seg(contactId) + "&session=" + seg(session),
                null, callDeadline, what, 404);
        if (r.statusCode() == 404) return null;
        JsonNode c = json(r.body(), what);
        if (!c.isObject() || c.isEmpty()) return null;
        return new WahaContact(text(c, "id"), text(c, "name"), text(c, "pushname"));
    }

    @Override
    public String groupSubject(String session, String groupId) {
        String what = "запросе группы";
        HttpResponse<String> r = call("GET", "/api/" + seg(session) + "/groups/" + seg(groupId), null, callDeadline, what, 404);
        return r.statusCode() == 404 ? null : text(json(r.body(), what), "subject");
    }

    @Override
    public String lidPhone(String session, String lid) {
        String what = "запросе скрытого номера";
        HttpResponse<String> r = call("GET", "/api/" + seg(session) + "/lids/" + seg(lid), null, callDeadline, what, 404);
        if (r.statusCode() == 404) return null;
        String pn = text(json(r.body(), what), "pn");
        if (pn == null) return null;
        String digits = WahaEventParser.userOf(pn);
        return digits.matches("\\d{6,15}") ? "+" + digits : null;
    }

    private HttpResponse<String> call(String method, String pathAndQuery, String jsonBody, Duration deadline, String what,
                                      int... alsoOk) {
        HttpRequest.Builder b = request(pathAndQuery, deadline, "application/json");
        if (jsonBody != null) b.header("Content-Type", "application/json");
        HttpRequest req = b.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
        HttpResponse<String> r = GatewayHttp.exchange(http, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                deadline, GATEWAY, what);
        check(r.statusCode(), what, alsoOk);
        return r;
    }

    private HttpRequest.Builder request(String pathAndQuery, Duration deadline, String accept) {
        requireConfigured();
        try {
            return HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).timeout(deadline)
                    .header("X-Api-Key", apiKey).header("Accept", accept);
        } catch (IllegalArgumentException e) {
            // текст исключения JDK может нести адрес или значение заголовка — наружу только своё
            throw new GatewayAuthException(0, "WAHA: запрос не собирается — проверьте WHATSAPP_WAHA_URL и WHATSAPP_WAHA_API_KEY");
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new GatewayAuthException(0, "не заданы адрес или ключ WAHA (WHATSAPP_WAHA_URL / WHATSAPP_WAHA_API_KEY)");
        }
    }

    private static void check(int status, String what, int... alsoOk) {
        if (status / 100 == 2) return;
        for (int ok : alsoOk) {
            if (status == ok) return;
        }
        if (status == 401 || status == 403) {
            throw new GatewayAuthException(status, "WAHA отклонил ключ API (HTTP " + status + ") при " + what
                    + " — проверьте WHATSAPP_WAHA_API_KEY");
        }
        throw new GatewayException(status, "WAHA: HTTP " + status + " при " + what);
    }

    private JsonNode json(String body, String what) {
        if (body == null || body.isBlank()) return MissingNode.getInstance();
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new GatewayException(200, "WAHA: ответ не разобран при " + what);
        }
    }

    /** Ссылку на файл собирает сама WAHA из WAHA_BASE_URL — она должна вести туда же, куда ходим мы. */
    private boolean sameOrigin(URI u) {
        try {
            URI base = URI.create(baseUrl);
            return u.getScheme() != null && u.getHost() != null && base.getScheme() != null && base.getHost() != null
                    && u.getScheme().equalsIgnoreCase(base.getScheme()) && u.getHost().equalsIgnoreCase(base.getHost())
                    && port(u) == port(base);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int port(URI u) {
        if (u.getPort() != -1) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static String seg(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }
}
```

- [ ] **Step 5: Прогнать оба клиента**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.waha.WahaHttpClientTest' --tests 'com.vladoose.nir.integration.greenapi.*'`
Expected: PASS (тексты Green-API прежние: «Green-API недоступен…», «Green-API не ответил…»).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration src/test/java/com/vladoose/nir/integration/waha/WahaHttpClientTest.java
git commit -m "feat(waha): HTTP-клиент WAHA (сессии, QR, сообщение с файлом, история, справочники); общий обмен с дедлайном

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Сессия WAHA — создание, статус, номер

**Files:**
- Create: `integration/waha/WahaSessionManager.java`
- Test: `src/test/java/com/vladoose/nir/integration/waha/FakeWahaClient.java`, `WahaSessionManagerTest.java`

**Interfaces:**
- Consumes: `WahaClient`, `WahaSession` (задача 5), `WhatsappStatusHolder.setState/setNumber` (задача 1), `WahaEventParser.userOf` (задача 3).
- Produces:
  - `WahaSessionManager(WahaClient client, String session, String market)`; константы `WORKING`, `SCAN_QR_CODE`; `String session()`, `String account()`, `static String number(WahaSession s)`, `boolean refresh(WhatsappStatusHolder status)` (true — только что стала WORKING), `WahaSession live()`, `byte[] qr()`, `void restart()`, `void logout()`.
  - Тестовый `FakeWahaClient` (публичные поля: `configured`, `session`, `calls`, `messages`, `files`, `history`, `contacts`, `groups`, `lids`, `qr`, `failWith`, `failHistoryWith`; метод `long count(String prefix)`; формат `calls`: `session <name>`, `create <name> <market>`, `start <name>`, `restart <name>`, `logout <name>`, `qr <name>`, `message <chatId> <messageId> <downloadMedia>`, `download <url>`, `history <from> <limit> <offset>`, `contact <id>`, `group <id>`, `lid <lid>`).

- [ ] **Step 1: Фейк WAHA и тесты (красные)**

Создать `src/test/java/com/vladoose/nir/integration/waha/FakeWahaClient.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladoose.nir.integration.whatsapp.FileTooLargeException;
import com.vladoose.nir.integration.whatsapp.GatewayException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Управляемый фейк WAHA без сети: сессия, сообщения по id, файлы, история «телефона», справочники. */
public class FakeWahaClient implements WahaClient {

    public boolean configured = true;
    /** null — сессии нет. */
    public WahaSession session;
    public final List<String> calls = new ArrayList<>();
    /** messageId → сообщение (с media.url у файлов). */
    public final Map<String, JsonNode> messages = new HashMap<>();
    /** url → байты. */
    public final Map<String, byte[]> files = new HashMap<>();
    /** Сообщения «телефона» для догонки: отдаются с timestamp ≥ from, по возрастанию, страницами. */
    public final List<JsonNode> history = new ArrayList<>();
    public final Map<String, WahaContact> contacts = new HashMap<>();
    public final Map<String, String> groups = new HashMap<>();
    public final Map<String, String> lids = new HashMap<>();
    public byte[] qr = {(byte) 0x89, 'P', 'N', 'G'};
    /** Любой вызов бросает это (WAHA недоступна, ключ отклонён). */
    public RuntimeException failWith;
    /** Только история бросает это (догонка не удалась, остальное работает). */
    public RuntimeException failHistoryWith;

    public long count(String prefix) { return calls.stream().filter(c -> c.startsWith(prefix)).count(); }

    private void call(String c) {
        calls.add(c);
        if (failWith != null) throw failWith;
    }

    @Override public boolean isConfigured() { return configured; }

    @Override
    public WahaSession session(String name) {
        call("session " + name);
        return session;
    }

    @Override
    public void createSession(String name, String market) {
        call("create " + name + " " + market);
        session = new WahaSession(name, "SCAN_QR_CODE", null, null);
    }

    @Override
    public void startSession(String name) {
        call("start " + name);
        session = new WahaSession(name, "STARTING", session == null ? null : session.meId(),
                session == null ? null : session.mePushName());
    }

    @Override public void restartSession(String name) { call("restart " + name); }

    @Override
    public void logoutSession(String name) {
        call("logout " + name);
        session = new WahaSession(name, "SCAN_QR_CODE", null, null);
    }

    @Override
    public byte[] qrPng(String name) {
        call("qr " + name);
        return qr;
    }

    @Override
    public JsonNode message(String s, String chatId, String messageId, boolean downloadMedia) {
        call("message " + chatId + " " + messageId + " " + downloadMedia);
        JsonNode m = messages.get(messageId);
        if (m == null) throw new GatewayException(404, "WAHA: HTTP 404 при подготовке файла сообщения");
        return m;
    }

    @Override
    public byte[] downloadFile(String url, long maxBytes) {
        call("download " + url);
        byte[] b = files.get(url);
        if (b == null) throw new GatewayException(404, "WAHA: HTTP 404 при скачивании файла");
        if (b.length > maxBytes) throw new FileTooLargeException(maxBytes);
        return b;
    }

    @Override
    public List<JsonNode> history(String s, long fromEpochSec, int limit, int offset) {
        call("history " + fromEpochSec + " " + limit + " " + offset);
        if (failHistoryWith != null) throw failHistoryWith;
        List<JsonNode> matching = history.stream()
                .filter(m -> m.path("timestamp").asLong() >= fromEpochSec)
                .sorted(Comparator.comparingLong((JsonNode m) -> m.path("timestamp").asLong()))
                .toList();
        return matching.subList(Math.min(offset, matching.size()), Math.min(offset + limit, matching.size()));
    }

    @Override
    public WahaContact contact(String s, String contactId) {
        call("contact " + contactId);
        return contacts.get(contactId);
    }

    @Override
    public String groupSubject(String s, String groupId) {
        call("group " + groupId);
        return groups.get(groupId);
    }

    @Override
    public String lidPhone(String s, String lid) {
        call("lid " + lid);
        return lids.get(lid);
    }
}
```

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaSessionManagerTest.java`:

```java
package com.vladoose.nir.integration.waha;

import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.WhatsappStatusHolder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WahaSessionManagerTest {

    final FakeWahaClient fake = new FakeWahaClient();
    final WhatsappStatusHolder status = new WhatsappStatusHolder();
    final WahaSessionManager sessions = new WahaSessionManager(fake, "westmed", "kz");

    static WahaSession working() { return new WahaSession("westmed", "WORKING", "77000000001@c.us", "West-Med"); }

    @Test
    void missingSessionIsCreatedWithMarketAtFirstRefresh() {
        sessions.refresh(status);

        assertThat(fake.calls).contains("create westmed KZ");
        assertThat(status.snapshot(true, true).getState()).isEqualTo("SCAN_QR_CODE");
    }

    /** Остановленную сессию запускаем только при старте АИС: остановку потом мог сделать человек. */
    @Test
    void stoppedSessionIsStartedOnlyOnce() {
        fake.session = new WahaSession("westmed", "STOPPED", null, null);
        sessions.refresh(status);
        fake.session = new WahaSession("westmed", "STOPPED", null, null);
        sessions.refresh(status);

        assertThat(fake.count("start ")).isEqualTo(1);
    }

    @Test
    void workingSessionPublishesNumberAndAccount() {
        fake.session = working();

        sessions.refresh(status);

        assertThat(status.snapshot(true, true).getNumber()).isEqualTo("77000000001");
        assertThat(sessions.account()).isEqualTo("77000000001");
    }

    @Test
    void becomingWorkingIsReportedOncePerTransition() {
        fake.session = new WahaSession("westmed", "SCAN_QR_CODE", null, null);
        assertThat(sessions.refresh(status)).isFalse();
        fake.session = working();
        assertThat(sessions.refresh(status)).isTrue();
        assertThat(sessions.refresh(status)).isFalse();
        fake.session = new WahaSession("westmed", "FAILED", "77000000001@c.us", "West-Med");
        assertThat(sessions.refresh(status)).isFalse();
        fake.session = working();
        assertThat(sessions.refresh(status)).isTrue();
    }

    /** Номер отвязали — старый номер не должен оставаться ни в строке состояния, ни в account чатов. */
    @Test
    void unlinkedSessionClearsNumber() {
        fake.session = working();
        sessions.refresh(status);
        fake.session = new WahaSession("westmed", "SCAN_QR_CODE", null, null);
        sessions.refresh(status);

        assertThat(status.snapshot(true, true).getNumber()).isNull();
        assertThat(sessions.account()).isNull();
    }

    /** WAHA поднялась позже АИС: сессия создаётся при первом успешном обращении, а не теряется. */
    @Test
    void unreachableWahaPropagatesAndCreationIsRetried() {
        fake.failWith = new GatewayException(0, "WAHA недоступен при запросе состояния сессии: ConnectException");
        assertThatThrownBy(() -> sessions.refresh(status)).isInstanceOf(GatewayException.class);

        fake.failWith = null;
        sessions.refresh(status);

        assertThat(fake.calls).contains("create westmed KZ");
    }
}
```

- [ ] **Step 2: Убедиться, что красные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava` (`dangerouslyDisableSandbox: true`)
Expected: FAIL — нет `WahaSessionManager`.

- [ ] **Step 3: Реализация**

Создать `integration/waha/WahaSessionManager.java`:

```java
package com.vladoose.nir.integration.waha;

import com.vladoose.nir.integration.whatsapp.WhatsappStatusHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Сессия WAHA = рабочий номер (спека whatsapp-waha §7). Создаёт или запускает её сама при первом успешном обращении
 * после старта АИС (WAHA может подняться позже бэкенда), раз в минуту из потока приёма обновляет статус и номер.
 * Страница «Система → WhatsApp» зовёт live/qr/restart/logout из потоков HTTP: last — volatile, ensured — только поток приёма.
 */
@Component
public class WahaSessionManager {

    public static final String WORKING = "WORKING";
    public static final String SCAN_QR_CODE = "SCAN_QR_CODE";
    static final String STOPPED = "STOPPED";

    private final WahaClient client;
    private final String session;
    private final String market;
    private volatile WahaSession last;
    private boolean ensured;

    public WahaSessionManager(WahaClient client,
                              @Value("${chats.whatsapp.waha.session:westmed}") String session,
                              @Value("${chats.whatsapp.market:KZ}") String market) {
        this.client = client;
        this.session = session == null || session.isBlank() ? "westmed" : session.strip();
        this.market = market == null ? "KZ" : market.strip().toUpperCase(Locale.ROOT);
    }

    public String session() { return session; }

    /** Номер подключённого WhatsApp без «@c.us» — account чатов; null — не привязан или ещё не спрашивали. */
    public String account() { return number(last); }

    /** «77000000001@c.us» → «77000000001»; номера нет → null. */
    public static String number(WahaSession s) {
        if (s == null || s.meId() == null) return null;
        String digits = WahaEventParser.userOf(s.meId());
        return digits.isEmpty() ? null : digits;
    }

    /**
     * Из потока приёма, раз в минуту. Первый успешный вызов после старта АИС создаёт сессию (её нет) или запускает
     * (остановлена). true — сессия только что стала WORKING: повод догнать пропущенное (спека §5.3).
     */
    public boolean refresh(WhatsappStatusHolder status) {
        WahaSession s = client.session(session);
        if (!ensured) {
            if (s == null) {
                client.createSession(session, market);
                s = client.session(session);
            } else if (STOPPED.equals(s.status())) {
                client.startSession(session);
                s = client.session(session);
            }
            ensured = true;
        }
        boolean wasWorking = last != null && WORKING.equals(last.status());
        last = s;
        status.setState(s == null ? null : s.status());
        status.setNumber(s == null ? null : s.meId());
        return s != null && WORKING.equals(s.status()) && !wasWorking;
    }

    /** Свежий статус для страницы (не ждёт минутного обновления); last не трогает — им владеет поток приёма. */
    public WahaSession live() { return client.session(session); }

    public byte[] qr() { return client.qrPng(session); }

    public void restart() { client.restartSession(session); }

    public void logout() { client.logoutSession(session); }
}
```

- [ ] **Step 4: Прогнать**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.waha.WahaSessionManagerTest'`
Expected: PASS (6 тестов).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/waha/WahaSessionManager.java \
  src/test/java/com/vladoose/nir/integration/waha/FakeWahaClient.java src/test/java/com/vladoose/nir/integration/waha/WahaSessionManagerTest.java
git commit -m "feat(waha): сессия WAHA — сама создаётся и запускается, статус и номер в строку состояния

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Источник WAHA — очередь в общий цикл, имена чатов, файлы

**Files:**
- Create: `integration/waha/WahaChatNames.java`, `integration/waha/WahaInboxSource.java`
- Modify: `integration/whatsapp/ParsedNotification.java` (`withContact`), `integration/whatsapp/WhatsappStatusHolder.java` (`sourceError`, `state()`, `number()`), `integration/whatsapp/WhatsappSourceConfig.java` (WAHA — провайдер по умолчанию), `src/main/resources/application.yaml`
- Test: `src/test/java/com/vladoose/nir/integration/waha/WahaInboxSourceTest.java`, `WahaIntakeTest.java`; modify `integration/whatsapp/WhatsappSourceConfigTest.java`

**Interfaces:**
- Consumes: `WhatsappSource`, `WhatsappNotification`, `WhatsappChatSync` (задача 1); `WahaEventParser` (3); `WhatsappInboxRepository.findNextPending`, `WahaInboxWriter.finish` (4); `WahaClient` (5); `WahaSessionManager` (6).
- Produces:
  - `ParsedNotification.Message.withContact(String chatName, String phone) → Message`.
  - `WhatsappStatusHolder.setSourceError(String)`, `String state()`, `String number()`; `snapshot` отдаёт `lastError ?: sourceError`.
  - `WahaChatNames(WahaClient client)`: `Message enrich(String session, Message m)`.
  - `WahaInboxSource(WhatsappInboxRepository repository, WahaInboxWriter inbox, WahaClient client, WahaSessionManager sessions, WahaChatNames names, ObjectMapper objectMapper, String hmacKey, long settleMs, long statusRefreshMs)` — в задаче 8 конструктор расширится.
  - `WhatsappSourceConfig.select(String provider, GreenApiSource greenApi, WahaInboxSource waha)`; провайдер по умолчанию — `waha`.

- [ ] **Step 1: Тесты источника и сквозного приёма (красные)**

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaInboxSourceTest.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.WhatsappInboxEvent;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import com.vladoose.nir.integration.whatsapp.*;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class WahaInboxSourceTest {

    @Autowired WhatsappInboxRepository repository;
    @Autowired WahaInboxWriter inbox;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    FakeWahaClient fake;
    WahaSessionManager sessions;
    final long now = Instant.now().getEpochSecond();

    /** Чужие PENDING из dev-базы не должны мешать порядку: в транзакции теста (она откатится) считаем их разобранными. */
    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE whatsapp_inbox SET status = 'DONE' WHERE status = 'PENDING'");
        fake = new FakeWahaClient();
        sessions = new WahaSessionManager(fake, "westmed", "KZ");
    }

    WahaInboxSource source(long settleMs) {
        return new WahaInboxSource(repository, inbox, fake, sessions, new WahaChatNames(fake), objectMapper,
                "test-hmac-key", settleMs, 60_000);
    }

    long queue(ObjectNode env) {
        String rid = "req-" + env.get("id").asText();
        inbox.insert(env, rid, env.toString());
        return repository.findByRequestId(rid).get(0).getId();
    }

    static WhatsappNotification note(ObjectNode env) { return new WhatsappNotification(1, env); }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }

    static String raw() { return "3EB0" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(); }

    /** Порядок — по времени события; моложе задержки (3 с) — не берём: правка не должна обогнать оригинал. */
    @Test
    void nextIsOldestSettledPendingEvent() {
        long later = queue(WahaJson.incomingText(personal(), "Айгерим", raw(), now - 60, "второе"));
        long earlier = queue(WahaJson.incomingText(personal(), "Ерлан", raw(), now - 120, "первое"));
        queue(WahaJson.incomingText(personal(), "Сауле", raw(), now - 1, "ещё отлёживается"));
        WahaInboxSource s = source(3000);

        WhatsappNotification first = s.next();
        assertThat(first.id()).isEqualTo(earlier);
        assertThat(first.body().path("payload").path("body").asText()).isEqualTo("первое");
        s.ack(first, null);
        assertThat(s.next().id()).isEqualTo(later);
        s.ack(new WhatsappNotification(later, null), null);
        assertThat(s.next()).isNull();
    }

    @Test
    void ackMarksDoneOrDroppedWithReason() {
        long ok = queue(WahaJson.incomingText(personal(), "А", raw(), now - 60, "x"));
        long bad = queue(WahaJson.incomingText(personal(), "Б", raw(), now - 60, "y"));
        WahaInboxSource s = source(0);

        s.ack(new WhatsappNotification(ok, null), null);
        s.ack(new WhatsappNotification(bad, null), "внутренняя ошибка (IllegalStateException) — подробности в логе сервера");

        WhatsappInboxEvent done = repository.findById(ok).orElseThrow();
        WhatsappInboxEvent dropped = repository.findById(bad).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(WhatsappInboxStatus.DONE);
        assertThat(done.getLastError()).isNull();
        assertThat(done.getProcessedAt()).isNotNull();
        assertThat(dropped.getStatus()).isEqualTo(WhatsappInboxStatus.DROPPED);
        assertThat(dropped.getLastError()).contains("IllegalStateException");
    }

    @Test
    void fileIsFetchedThroughMessageMediaUrl() {
        String chat = personal();
        String id = WahaJson.messageId(false, chat, "3EB0F00D");
        String url = "http://ais-waha:3000/api/files/westmed/" + id + ".pdf";
        fake.messages.put(id, WahaJson.M.createObjectNode().put("id", id).set("media", WahaJson.M.createObjectNode().put("url", url)));
        fake.files.put(url, new byte[]{7, 7});

        byte[] bytes = source(0).download(new FileRef(id, "ТЗ.pdf", "application/pdf", 2L), 1024);

        assertThat(bytes).containsExactly(7, 7);
        assertThat(fake.calls).containsExactly("message " + chat + " " + id + " true", "download " + url);
    }

    @Test
    void messageWithoutMediaUrlIsDownloadFailure() {
        String id = WahaJson.messageId(false, personal(), "3EB0F00E");
        fake.messages.put(id, WahaJson.M.createObjectNode().put("id", id).set("media", WahaJson.M.createObjectNode().putNull("url")));

        assertThatThrownBy(() -> source(0).download(new FileRef(id, "a.pdf", "application/pdf"), 1024))
                .isInstanceOf(GatewayException.class);
    }

    /** Имя чата — из записной книжки телефона (как у Green-API), тема группы — из справочника; с кешем. */
    @Test
    void namesComeFromWahaDirectoriesAndAreCached() {
        String chat = personal();
        fake.contacts.put(chat, new WahaContact(chat, "Айгерим (клиника «Шипагер»)", "Aigerim"));
        fake.groups.put("120363000000000001@g.us", "Коллеги West-Med");
        WahaInboxSource s = source(0);

        ParsedNotification.Message first = (ParsedNotification.Message) s.parse(note(WahaJson.incomingText(chat, "Aigerim", raw(), now, "x")));
        ParsedNotification.Message second = (ParsedNotification.Message) s.parse(note(WahaJson.phoneReply(chat, raw(), now, "y")));
        ParsedNotification.Message group = (ParsedNotification.Message) s.parse(
                note(WahaJson.groupText("120363000000000001@g.us", "77025556677@c.us", "Данияр", raw(), now, "z")));

        assertThat(first.chatName()).isEqualTo("Айгерим (клиника «Шипагер»)");
        assertThat(second.chatName()).isEqualTo("Айгерим (клиника «Шипагер»)");
        assertThat(fake.count("contact ")).isEqualTo(1);
        assertThat(group.chatName()).isEqualTo("Коллеги West-Med");
        assertThat(group.senderName()).isEqualTo("Данияр");
    }

    /** Справочник WAHA упал — сообщение не ждёт: имя профиля из события, телефон как есть. */
    @Test
    void directoryOutageDoesNotHoldMessages() {
        fake.failWith = new GatewayException(0, "WAHA недоступен при запросе контакта: ConnectException");
        String chat = personal();

        ParsedNotification.Message m = (ParsedNotification.Message) source(0).parse(note(WahaJson.incomingText(chat, "Aigerim", raw(), now, "x")));

        assertThat(m.chatName()).isEqualTo("Aigerim");
        assertThat(m.phone()).isEqualTo("+" + chat.substring(0, 11));
    }

    @Test
    void hiddenNumberGetsPhoneFromLidDirectory() {
        fake.lids.put("123456789012345@lid", "+77012223344");

        ParsedNotification.Message m = (ParsedNotification.Message) source(0).parse(
                note(WahaJson.incomingText("123456789012345@lid", "Скрытый", raw(), now, "x")));

        assertThat(m.phone()).isEqualTo("+77012223344");
    }

    /** Номер сессии неизвестен (в событии нет me, сессию ещё не спрашивали) — не гадаем: попытка не удалась. */
    @Test
    void unknownAccountIsGatewayFailure() {
        ObjectNode env = WahaJson.incomingText(personal(), "А", raw(), now, "x");
        env.remove("me");

        assertThatThrownBy(() -> source(0).parse(note(env))).isInstanceOf(GatewayException.class);
    }

    /** WAHA не отвечает — красная строка, но уже принятые события разбираются: housekeeping не бросает. */
    @Test
    void unreachableWahaIsRedLineNotStop() {
        fake.failWith = new GatewayException(0, "WAHA недоступен при запросе состояния сессии: ConnectException");
        WhatsappStatusHolder status = new WhatsappStatusHolder();

        source(0).housekeeping(status);

        assertThat(status.snapshot(true, true).getLastError()).contains("WAHA недоступен").contains("принятые сообщения разбираются");
    }
}
```

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaIntakeTest.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.westmed.FakeWestmedClient;
import com.vladoose.nir.integration.whatsapp.*;
import com.vladoose.nir.repository.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/** Вебхук WAHA → очередь → общий цикл → чаты и обращения (спека whatsapp-waha §5, §6, §8) на реальной базе. */
@SpringBootTest
@Transactional
class WahaIntakeTest {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired ChatIngestWriter writer;
    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired ChatLeadRules rules;
    @Autowired LeadIntakeService intake;
    @Autowired LeadService leadService;
    @Autowired WhatsappInboxRepository repository;
    @Autowired WahaInboxWriter inbox;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    FakeWahaClient fake;
    WhatsappStatusHolder status;
    long clock = Instant.now().getEpochSecond() - 3600;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE whatsapp_inbox SET status = 'DONE' WHERE status = 'PENDING'");
        MarketContext.set(Market.KZ);
        fake = new FakeWahaClient();
        status = new WhatsappStatusHolder();
    }

    @AfterEach void tearDown() { MarketContext.clear(); }

    /** Предел файла — 1 МБ; задержка «отлёживания» — 0, события в прошлом. */
    private WhatsappChatSync sync(ChatIngestWriter w) {
        WahaInboxSource source = new WahaInboxSource(repository, inbox, fake, new WahaSessionManager(fake, "westmed", "KZ"),
                new WahaChatNames(fake), objectMapper, "test-hmac-key", 0, 60_000);
        return new WhatsappChatSync(source, w, new FakeWestmedClient(), status, "https://westmed.kz", 1);
    }

    private WhatsappChatSync sync() { return sync(writer); }

    private long queue(ObjectNode env) {
        String rid = "req-" + env.get("id").asText();
        inbox.insert(env, rid, env.toString());
        return repository.findByRequestId(rid).get(0).getId();
    }

    private WhatsappInboxEvent row(long id) { return repository.findById(id).orElseThrow(); }

    private Chat chat(String externalChatId) {
        return chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, WahaJson.ACCOUNT, externalChatId).orElseThrow();
    }

    private ChatMessage lastMessage(String externalChatId) {
        return messageRepository.findLatest(chat(externalChatId).getId(), PageRequest.of(0, 1)).get(0);
    }

    private ChatIngestWriter writerThrowing(RuntimeException e) {
        return new ChatIngestWriter(chatRepository, messageRepository, attachmentRepository, rules, intake, leadService) {
            @Override
            public Outcome write(ParsedNotification.Message m, IncomingFile file, List<IncomingLead.Item> cart) {
                throw e;
            }
        };
    }

    static String personal() { return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us"; }

    static String group() { return "120363" + ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L) + "@g.us"; }

    static String raw() { return "3EB0" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(); }

    @Test
    void queuedEventsBecomeChatsAndLeadsAndAreDone() {
        String c = personal();
        long in = queue(WahaJson.incomingText(c, "Айгерим", raw(), clock += 60, "Здравствуйте, нужен облучатель"));
        long out = queue(WahaJson.phoneReply(c, raw(), clock += 60, "Добрый день! Подготовим КП"));

        assertThat(sync().drain(10)).isTrue();

        assertThat(row(in).getStatus()).isEqualTo(WhatsappInboxStatus.DONE);
        assertThat(row(out).getStatus()).isEqualTo(WhatsappInboxStatus.DONE);
        assertThat(chat(c).getTitle()).isEqualTo("Айгерим");
        assertThat(lastMessage(c).getDirection()).isEqualTo(LeadDirection.OUT);
        assertThat(leadRepository.findByChatIdIn(List.of(chat(c).getId()))).singleElement()
                .extracting(Lead::getStatus).isEqualTo(LeadStatus.IN_WORK);
    }

    @Test
    void poisonEventIsDroppedAfterThreeAttemptsWithReason() {
        long id = queue(WahaJson.incomingText(personal(), "Айгерим", raw(), clock += 60, "x"));
        WhatsappChatSync s = sync(writerThrowing(new IllegalStateException("значение 'Айгерим' не влезло")));

        assertThat(s.drain(10)).isFalse();
        assertThat(s.drain(10)).isFalse();
        assertThat(row(id).getStatus()).isEqualTo(WhatsappInboxStatus.PENDING);
        assertThat(s.drain(10)).isTrue();

        assertThat(row(id).getStatus()).isEqualTo(WhatsappInboxStatus.DROPPED);
        assertThat(row(id).getLastError()).contains("IllegalStateException").doesNotContain("Айгерим");
        assertThat(status.snapshot(true, true).getWarnings()).contains(WhatsappStatusHolder.MESSAGE_DROPPED);
    }

    @Test
    void databaseOutageKeepsEventPending() {
        long id = queue(WahaJson.incomingText(personal(), "Айгерим", raw(), clock += 60, "x"));
        WhatsappChatSync s = sync(writerThrowing(new CannotCreateTransactionException("Could not open JPA EntityManager")));

        for (int i = 0; i < 6; i++) assertThat(s.drain(10)).isFalse();

        assertThat(row(id).getStatus()).isEqualTo(WhatsappInboxStatus.PENDING);
        assertThat(status.lastError()).contains("база данных").contains("очереди АИС");
    }

    /** Предохранитель общего цикла на очереди WAHA: после 3 пропусков за сутки дальше не пропускаем — стоим с красной строкой. */
    @Test
    void fuseStopsDroppingAfterThreeADay() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) ids.add(queue(WahaJson.incomingText(personal(), "Айгерим", raw(), clock += 60, "x" + i)));
        WhatsappChatSync s = sync(writerThrowing(new IllegalStateException("регрессия записи")));

        for (int i = 0; i < 30; i++) s.drain(10);

        assertThat(ids.subList(0, 3)).allSatisfy(id -> assertThat(row(id).getStatus()).isEqualTo(WhatsappInboxStatus.DROPPED));
        assertThat(row(ids.get(3)).getStatus()).isEqualTo(WhatsappInboxStatus.PENDING);
        assertThat(status.lastError()).contains("приём остановлен").contains("очереди АИС");
    }

    /** Размер из события больше предела — WAHA не трогаем вовсе: она держала бы весь файл в памяти (спека §6). */
    @Test
    void knownSizeOverLimitIsNotDownloaded() {
        String c = personal();
        queue(WahaJson.incomingFile(c, "Айгерим", raw(), clock += 60, "documentMessage", "Каталог.pdf", "application/pdf",
                40L * 1024 * 1024, "каталог"));

        sync().drain(10);

        assertThat(fake.calls).noneMatch(x -> x.startsWith("message ") || x.startsWith("download "));
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(lastMessage(c).getId())))
                .singleElement().extracting(ChatAttachmentMeta::notStoredReason).isEqualTo(AttachmentNotStoredReason.TOO_LARGE);
    }

    @Test
    void personalFileIsFetchedGroupFileIsNot() {
        String c = personal();
        String g = group();
        String rawP = raw();
        String id = WahaJson.messageId(false, c, rawP);
        String url = "http://ais-waha:3000/api/files/westmed/" + id + ".xlsx";
        fake.messages.put(id, WahaJson.M.createObjectNode().put("id", id).set("media", WahaJson.M.createObjectNode().put("url", url)));
        fake.files.put(url, new byte[]{1, 2, 3});
        queue(WahaJson.incomingFile(c, "Айгерим", rawP, clock += 60, "documentMessage", "Заявка.xlsx", XLSX, 3L, "список"));
        queue(WahaJson.groupFile(g, "77025556677@c.us", "Данияр", raw(), clock += 60, "imageMessage", null, "image/jpeg", 5_000L));

        sync().drain(10);

        assertThat(fake.calls).filteredOn(x -> x.startsWith("message ")).containsExactly("message " + c + " " + id + " true");
        assertThat(attachmentRepository.findMetaByMessageIds(List.of(lastMessage(c).getId(), lastMessage(g).getId())))
                .extracting(ChatAttachmentMeta::fileName, ChatAttachmentMeta::notStoredReason)
                .containsExactlyInAnyOrder(tuple("Заявка.xlsx", null), tuple(null, AttachmentNotStoredReason.GROUP));
    }

    @Test
    void incomingCallStartsCallLeadAndOutcomeCompletesTheLine() {
        String c = personal();
        String callId = "CALL" + raw();
        queue(WahaJson.call("call.received", callId, c, clock += 60, false, null));
        queue(WahaJson.call("call.accepted", callId, c, clock += 5, false, null));

        assertThat(sync().drain(10)).isTrue();

        assertThat(messageRepository.findLatest(chat(c).getId(), PageRequest.of(0, 5))).singleElement().satisfies(m -> {
            assertThat(m.getType()).isEqualTo(ChatMessageType.CALL);
            assertThat(m.getBody()).isEqualTo("📞 Входящий звонок — принят");
            assertThat(m.isEdited()).isFalse();
        });
        assertThat(leadRepository.findByChatIdIn(List.of(chat(c).getId()))).singleElement()
                .extracting(Lead::getSubject).isEqualTo("Звонок в WhatsApp");
    }

    @Test
    void editAndRevokeFromWaha() {
        String c = personal();
        String original = raw();
        queue(WahaJson.incomingText(c, "Айгерим", original, clock += 60, "Нужен УЗИ"));
        queue(WahaJson.edited(c, false, raw(), original, clock += 60, "Нужен УЗИ экспертного класса"));
        queue(WahaJson.revoked(c, false, raw(), original));        // время события — «сейчас», позже правки

        sync().drain(10);

        ChatMessage m = lastMessage(c);
        assertThat(m.getBody()).isEqualTo("Нужен УЗИ экспертного класса");
        assertThat(m.isEdited()).isTrue();
        assertThat(m.isDeleted()).isTrue();
    }
}
```

В `integration/whatsapp/WhatsappSourceConfigTest.java`: добавить импорты `com.vladoose.nir.integration.waha.WahaInboxSource` и `org.mockito.Mockito`, поле `final WahaInboxSource waha = Mockito.mock(WahaInboxSource.class);`, во всех вызовах `select(x, green)` → `select(x, green, waha)` и тест:

```java
    @Test
    void wahaIsChosenByName() {
        assertThat(WhatsappSourceConfig.select("waha", green, waha)).isSameAs(waha);
    }
```

- [ ] **Step 2: Убедиться, что красные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava` (`dangerouslyDisableSandbox: true`)
Expected: FAIL — нет `WahaInboxSource`, `WahaChatNames`.

- [ ] **Step 3: Расширения общих классов**

В `integration/whatsapp/ParsedNotification.java`, в запись `Message` после метода `isEdit()` добавить:

```java
        /** Копия с именем чата и телефоном из справочников шлюза (WAHA: контакты, группы, скрытые номера). */
        public Message withContact(String newChatName, String newPhone) {
            return new Message(account, chatId, kind, newPhone, newChatName, senderName, direction, idMessage, sentAt,
                    type, body, file, editOf, viaApi);
        }
```

В `integration/whatsapp/WhatsappStatusHolder.java`:
- после поля `lastError` добавить:

```java
    /** Беда источника, не мешающая разбирать уже принятое (WAHA не отвечает): видна, пока нет ошибки самого цикла. */
    private volatile String sourceError;
```

- после метода `setState` добавить:

```java
    public String state() { return state; }

    public String number() { return number; }

    public void setSourceError(String e) { sourceError = e; }
```

- в `snapshot` строку `r.setLastError(lastError);` заменить на `r.setLastError(lastError != null ? lastError : sourceError);`.

- [ ] **Step 4: Имена чатов**

Создать `integration/waha/WahaChatNames.java`:

```java
package com.vladoose.nir.integration.waha;

import com.vladoose.nir.integration.whatsapp.ChatKind;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Имена чатов для WAHA (спека whatsapp-waha §3). В событии WAHA есть только имя профиля отправителя (PushName), а
 * Green-API давал имя из контактов рабочего телефона — его берём из справочников WAHA: контакт (name — записная книжка,
 * pushname — профиль), тема группы, телефон за скрытым @lid. Кеш на 30 мин; сбой справочника сообщение не задерживает —
 * имя подтянется со следующим входящим.
 */
@Component
public class WahaChatNames {

    static final long TTL_MS = 30 * 60_000L;
    static final long FAILURE_TTL_MS = 2 * 60_000L;
    private static final int MAX_ENTRIES = 5_000;

    private final WahaClient client;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(String value, long until) {}

    public WahaChatNames(WahaClient client) { this.client = client; }

    public ParsedNotification.Message enrich(String session, ParsedNotification.Message m) {
        String name = m.kind() == ChatKind.GROUP
                ? lookup("group:" + m.chatId(), () -> client.groupSubject(session, m.chatId()))
                : lookup("contact:" + m.chatId(), () -> contactName(session, m.chatId()));
        String phone = m.phone();
        if (phone == null && m.kind() == ChatKind.PERSONAL_HIDDEN) {
            phone = lookup("lid:" + m.chatId(), () -> client.lidPhone(session, m.chatId()));
        }
        String chatName = name != null ? name : m.chatName();
        if (Objects.equals(chatName, m.chatName()) && Objects.equals(phone, m.phone())) return m;
        return m.withContact(chatName, phone);
    }

    private String contactName(String session, String chatId) {
        WahaContact c = client.contact(session, chatId);
        if (c == null) return null;
        if (c.name() != null && !c.name().isBlank()) return c.name().strip();
        return c.pushname() != null && !c.pushname().isBlank() ? c.pushname().strip() : null;
    }

    private String lookup(String key, Supplier<String> load) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && c.until() > now) return c.value();
        String value;
        long ttl = TTL_MS;
        try {
            value = load.get();
        } catch (GatewayException e) {
            value = c == null ? null : c.value();     // прежнее имя лучше, чем никакое
            ttl = FAILURE_TTL_MS;
        }
        if (cache.size() > MAX_ENTRIES) cache.clear();
        cache.put(key, new Cached(value, now + ttl));
        return value;
    }
}
```

- [ ] **Step 5: Источник WAHA**

Создать `integration/waha/WahaInboxSource.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import com.vladoose.nir.integration.whatsapp.*;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * WAHA как источник (спека whatsapp-waha §3, §5): очередь — таблица whatsapp_inbox, которую наполняет вебхук.
 * Событие берётся, «отлежавшись» settle-ms (правка, пришедшая чуть раньше оригинала, встанет после него), и
 * подтверждается как DONE или DROPPED с причиной. Раз в минуту — статус сессии; WAHA не отвечает — красная строка,
 * но уже принятое разбирается дальше. Все методы, кроме name/isConfigured, зовёт поток приёма.
 */
@Component
public class WahaInboxSource implements WhatsappSource {

    private static final Logger log = LoggerFactory.getLogger(WahaInboxSource.class);

    private final WhatsappInboxRepository repository;
    private final WahaInboxWriter inbox;
    private final WahaClient client;
    private final WahaSessionManager sessions;
    private final WahaChatNames names;
    private final ObjectMapper objectMapper;
    private final String hmacKey;
    private final long settleMs;
    private final long statusRefreshMs;
    private long nextStatusCheck;
    private String loggedSourceError;

    public WahaInboxSource(WhatsappInboxRepository repository, WahaInboxWriter inbox, WahaClient client,
                           WahaSessionManager sessions, WahaChatNames names, ObjectMapper objectMapper,
                           @Value("${chats.whatsapp.waha.hmac-key:}") String hmacKey,
                           @Value("${chats.whatsapp.waha.settle-ms:3000}") long settleMs,
                           @Value("${chats.whatsapp.waha.status-refresh-ms:60000}") long statusRefreshMs) {
        this.repository = repository;
        this.inbox = inbox;
        this.client = client;
        this.sessions = sessions;
        this.names = names;
        this.objectMapper = objectMapper;
        this.hmacKey = hmacKey;
        this.settleMs = settleMs;
        this.statusRefreshMs = statusRefreshMs;
    }

    @Override public String name() { return WhatsappProviders.WAHA; }

    @Override public boolean isConfigured() { return client.isConfigured() && hmacKey != null && !hmacKey.isEmpty(); }

    @Override public String configHint() {
        return "не заданы ключи шлюза WAHA (WHATSAPP_WAHA_API_KEY / WHATSAPP_WAHA_HMAC_KEY)";
    }

    @Override public String waitingNote() { return "сообщения ждут в очереди АИС"; }

    @Override
    public void housekeeping(WhatsappStatusHolder status) {
        long now = System.currentTimeMillis();
        if (now < nextStatusCheck) return;
        nextStatusCheck = now + statusRefreshMs;
        refreshSession(status);
    }

    private void refreshSession(WhatsappStatusHolder status) {
        try {
            sessions.refresh(status);
            status.setSourceError(null);
            loggedSourceError = null;
        } catch (GatewayException e) {
            // принятое вебхуком разбирается и без WAHA: цикл не останавливаем, только красная строка
            status.setSourceError(e.getMessage() + " — принятые сообщения разбираются, файлы и догонка ждут");
            if (!Objects.equals(loggedSourceError, e.getMessage())) {
                log.warn("WhatsApp: {}", e.getMessage());
                loggedSourceError = e.getMessage();
            }
        }
    }

    @Override
    public WhatsappNotification next() {
        OffsetDateTime settled = OffsetDateTime.now().minus(Duration.ofMillis(settleMs));
        return repository.findNextPending(WhatsappProviders.WAHA, settled)
                .map(e -> new WhatsappNotification(e.getId(), read(e.getPayload())))
                .orElse(null);
    }

    private JsonNode read(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException e) {
            return MissingNode.getInstance();     // вебхук кладёт только JSON; битое — парсер пропустит
        }
    }

    @Override
    public ParsedNotification parse(WhatsappNotification n) {
        ParsedNotification p = WahaEventParser.parse(n.body(), sessions.account());
        if (p instanceof ParsedNotification.Message m) return names.enrich(sessions.session(), m);
        return p;
    }

    @Override
    public void ack(WhatsappNotification n, String droppedReason) {
        inbox.finish(n.id(), droppedReason == null ? WhatsappInboxStatus.DONE : WhatsappInboxStatus.DROPPED, droppedReason);
    }

    /** Файл: WAHA сама не качает (WAHA_EVENTS_DOWNLOAD_MEDIA=false) — просим сообщение с файлом, берём по media.url. */
    @Override
    public byte[] download(FileRef ref, long maxBytes) {
        WahaEventParser.MessageId id = WahaEventParser.MessageId.parse(ref.locator());
        if (id == null) throw new GatewayException(0, "WAHA: у файла нет id сообщения");
        JsonNode msg = client.message(sessions.session(), id.chatId(), ref.locator(), true);
        JsonNode url = msg == null ? null : msg.path("media").path("url");
        if (url == null || !url.isTextual() || url.asText().isBlank()) {
            throw new GatewayException(0, "WAHA: файл сообщения не скачался");
        }
        return client.downloadFile(url.asText(), maxBytes);
    }
}
```

- [ ] **Step 6: WAHA — провайдер по умолчанию**

`integration/whatsapp/WhatsappSourceConfig.java` — заменить метод бина и `select`:

```java
    /** destroyMethod = "": бин — тот же объект, что источник-компонент, закрывать его вторым именем незачем. */
    @Bean(destroyMethod = "")
    @Primary
    public WhatsappSource whatsappSource(@Value("${chats.whatsapp.provider:waha}") String provider,
                                         GreenApiSource greenApi, WahaInboxSource waha) {
        return select(provider, greenApi, waha);
    }

    static WhatsappSource select(String provider, GreenApiSource greenApi, WahaInboxSource waha) {
        String p = WhatsappProviders.normalize(provider);
        if (p.equals(WhatsappProviders.WAHA)) return waha;
        if (p.equals(WhatsappProviders.GREENAPI)) return greenApi;
        return new UnknownProviderSource(provider);
    }
```

(импорт `com.vladoose.nir.integration.waha.WahaInboxSource`). В `application.yaml` строку провайдера заменить на

```yaml
    provider: ${WHATSAPP_PROVIDER:waha}            # waha | greenapi (запасной, спека whatsapp-waha §1)
```

- [ ] **Step 7: Прогнать**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.waha.*' --tests 'com.vladoose.nir.integration.whatsapp.*'`
Expected: PASS.

- [ ] **Step 8: Мутации (по одной)**

1. `WahaInboxSource.next()` — `OffsetDateTime settled = OffsetDateTime.now();` (без задержки) → FAIL `nextIsOldestSettledPendingEvent` (третьим приходит «ещё отлёживается»). Вернуть.
2. `WhatsappChatSync.fetchFile` — закомментировать строку `if (m.kind() == ChatKind.GROUP) …` → FAIL `personalFileIsFetchedGroupFileIsNot`. Вернуть.
3. `WhatsappChatSync.fetchFile` — закомментировать блок проверки `ref.sizeBytes()` → FAIL `knownSizeOverLimitIsNotDownloaded` (и `fileWithKnownSizeOverLimitIsNotDownloaded`). Вернуть.

После возврата — PASS.

- [ ] **Step 9: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration src/main/resources/application.yaml \
  src/test/java/com/vladoose/nir/integration
git commit -m "feat(waha): источник WAHA для общего цикла — очередь по времени с задержкой, имена из справочников, файлы по требованию; WAHA по умолчанию

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Догонка по истории и уборка очереди

**Files:**
- Create: `integration/waha/WahaCatchUp.java`
- Modify: `repository/ChatMessageRepository.java` (`findLatestSentAt`), `integration/whatsapp/WhatsappStatusHolder.java` (`CATCH_UP_FAILED`), `integration/waha/WahaInboxSource.java` (полная замена)
- Test: `src/test/java/com/vladoose/nir/integration/waha/WahaCatchUpTest.java`; modify `WahaInboxSourceTest.java`, `WahaIntakeTest.java` (фабрики источника)

**Interfaces:**
- Consumes: `WahaClient.history` (5), `WahaInboxWriter.insert(JsonNode)`, `cleanup` (4), `WahaSessionManager.refresh/account/session` (6), `WhatsappStatusHolder.state()/setSourceWarnings` (7).
- Produces:
  - `ChatMessageRepository.findLatestSentAt(LeadChannel channel, String account) → OffsetDateTime`.
  - `WahaCatchUp(WahaClient, WahaInboxWriter, ChatMessageRepository, ObjectMapper, long overlapMin)`; `int run(String session, String account)`; `PAGE = 100`, `MAX_PAGES = 50`.
  - `WhatsappStatusHolder.CATCH_UP_FAILED = "CATCH_UP_FAILED"`.
  - `WahaInboxSource(WhatsappInboxRepository, WahaInboxWriter, WahaClient, WahaSessionManager, WahaChatNames, WahaCatchUp, ObjectMapper, String hmacKey, long settleMs, long statusRefreshMs, long catchUpMs, int doneDays, int droppedDays)`.

- [ ] **Step 1: Тесты (красные)**

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaCatchUpTest.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.whatsapp.ChatKind;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import com.vladoose.nir.service.ChatIngestWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

/** Догонка (спека whatsapp-waha §5.3) на реальной базе, WAHA — фейк. */
@SpringBootTest
@Transactional
class WahaCatchUpTest {

    @Autowired ChatIngestWriter writer;
    @Autowired ChatMessageRepository messages;
    @Autowired WahaInboxWriter inbox;
    @Autowired WhatsappInboxRepository repository;
    @Autowired ObjectMapper objectMapper;

    final FakeWahaClient fake = new FakeWahaClient();
    /** Свой номер на тест: отметка догонки считается по сообщениям номера — данные dev-базы не мешают. */
    final String account = "7799" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private WahaCatchUp catchUp() { return new WahaCatchUp(fake, inbox, messages, objectMapper, 10); }

    /** Сообщение этого номера со временем sentAtSec — как будто уже принятое. */
    private void stored(long sentAtSec) {
        String chat = "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us";
        writer.write(new ParsedNotification.Message(account, chat, ChatKind.PERSONAL, "+" + chat.substring(0, 11),
                "Айгерим", "Айгерим", LeadDirection.IN, "HIST" + sentAtSec + chat.substring(4, 11),
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(sentAtSec), ZoneOffset.UTC), ChatMessageType.TEXT, "x",
                null, null, false), null, List.of());
    }

    private static ObjectNode historyMessage(String rawId, long ts) {
        return (ObjectNode) WahaJson.incomingText("77015550000@c.us", "Айгерим", rawId, ts, "из истории").get("payload");
    }

    /** Первая привязка: сообщений номера ещё нет — отсчёт с привязки, старую переписку не тянем (решение 9). */
    @Test
    void firstLinkWithoutMessagesDoesNothing() {
        assertThat(catchUp().run("westmed", account)).isZero();
        assertThat(fake.count("history ")).isZero();
    }

    @Test
    void startsTenMinutesBeforeLatestMessageAndPagesToTheEnd() {
        long t = Instant.now().getEpochSecond() - 3600;
        stored(t - 500);
        stored(t);
        for (int i = 0; i < 250; i++) fake.history.add(historyMessage("3EB0" + account + "N" + i, t + i));

        assertThat(catchUp().run("westmed", account)).isEqualTo(250);
        long from = t - 600;
        assertThat(fake.calls).filteredOn(c -> c.startsWith("history "))
                .containsExactly("history " + from + " 100 0", "history " + from + " 100 100", "history " + from + " 100 200");
    }

    @Test
    void messageAlreadyQueuedByWebhookIsNotDuplicated() {
        long t = Instant.now().getEpochSecond() - 3600;
        stored(t);
        String rawId = "3EB0" + account + "W";
        ObjectNode webhook = WahaJson.incomingText("77015550000@c.us", "Айгерим", rawId, t + 5, "из вебхука");
        inbox.insert(webhook, "req-" + rawId, webhook.toString());
        fake.history.add(webhook.get("payload").deepCopy());

        assertThat(catchUp().run("westmed", account)).isZero();
        assertThat(repository.findByMessageKey("false_" + rawId)).hasSize(1);
    }
}
```

В `WahaInboxSourceTest`:
1. Импорты: `com.vladoose.nir.repository.ChatMessageRepository`, `java.util.List`.
2. Поле `@Autowired ChatMessageRepository messages;` и вложенный класс-счётчик:

```java
    /** Догонка-счётчик: сама история проверена в WahaCatchUpTest, здесь — когда её зовут. */
    class CountingCatchUp extends WahaCatchUp {
        int runs;
        RuntimeException failWith;

        CountingCatchUp() { super(fake, inbox, messages, objectMapper, 10); }

        @Override
        public int run(String session, String account) {
            runs++;
            if (failWith != null) throw failWith;
            return 0;
        }
    }
```

3. Фабрику заменить:

```java
    WahaInboxSource source(long settleMs) { return source(settleMs, new CountingCatchUp()); }

    WahaInboxSource source(long settleMs, WahaCatchUp catchUp) {
        return new WahaInboxSource(repository, inbox, fake, sessions, new WahaChatNames(fake), catchUp, objectMapper,
                "test-hmac-key", settleMs, 60_000, 600_000, 7, 30);
    }
```

4. Тесты в конец класса:

```java
    @Test
    void workingSessionIsCaughtUpOnceUntilInterval() {
        fake.session = new WahaSession("westmed", "WORKING", WahaJson.ME, "West-Med");
        CountingCatchUp catchUp = new CountingCatchUp();
        WahaInboxSource s = source(0, catchUp);
        WhatsappStatusHolder status = new WhatsappStatusHolder();

        s.housekeeping(status);
        s.housekeeping(status);

        assertThat(catchUp.runs).isEqualTo(1);
    }

    /** Пока номер не привязан, истории нет — догонку не зовём. */
    @Test
    void notWorkingSessionIsNotCaughtUp() {
        fake.session = new WahaSession("westmed", "SCAN_QR_CODE", null, null);
        CountingCatchUp catchUp = new CountingCatchUp();

        source(0, catchUp).housekeeping(new WhatsappStatusHolder());

        assertThat(catchUp.runs).isZero();
    }

    /** Сессия снова WORKING (событие session.status) — догоняем сразу, не дожидаясь 10 мин. */
    @Test
    void workingEventTriggersCatchUpAgain() {
        fake.session = new WahaSession("westmed", "WORKING", WahaJson.ME, "West-Med");
        CountingCatchUp catchUp = new CountingCatchUp();
        WahaInboxSource s = source(0, catchUp);
        WhatsappStatusHolder status = new WhatsappStatusHolder();
        s.housekeeping(status);

        s.parse(note(WahaJson.sessionStatus("WORKING")));
        s.housekeeping(status);

        assertThat(catchUp.runs).isEqualTo(2);
    }

    /** Догонка упала — предупреждение в строке, приём идёт; удалась — предупреждение снято. */
    @Test
    void catchUpFailureIsWarningNotStop() {
        fake.session = new WahaSession("westmed", "WORKING", WahaJson.ME, "West-Med");
        CountingCatchUp catchUp = new CountingCatchUp();
        catchUp.failWith = new GatewayException(0, "WAHA не ответил за 90 с при догонке по истории");
        WahaInboxSource s = source(0, catchUp);
        WhatsappStatusHolder status = new WhatsappStatusHolder();

        assertThatCode(() -> s.housekeeping(status)).doesNotThrowAnyException();
        assertThat(status.snapshot(true, true).getWarnings()).contains(WhatsappStatusHolder.CATCH_UP_FAILED);

        catchUp.failWith = null;
        s.parse(note(WahaJson.sessionStatus("WORKING")));
        s.housekeeping(status);
        assertThat(status.snapshot(true, true).getWarnings()).doesNotContain(WhatsappStatusHolder.CATCH_UP_FAILED);
    }

    /** Уборка (спека §5.4): DONE старше 7 дней, DROPPED старше 30; PENDING — никогда. */
    @Test
    void cleanupRemovesOnlyOldProcessedEvents() {
        long doneOld = queue(WahaJson.sessionStatus("WORKING"));
        long doneNew = queue(WahaJson.sessionStatus("WORKING"));
        long droppedOld = queue(WahaJson.sessionStatus("WORKING"));
        long droppedNew = queue(WahaJson.sessionStatus("WORKING"));
        long pendingOld = queue(WahaJson.sessionStatus("WORKING"));
        String set = "UPDATE whatsapp_inbox SET status = ?, processed_at = now() - make_interval(days => ?) WHERE id = ?";
        jdbc.update(set, "DONE", 8, doneOld);
        jdbc.update(set, "DONE", 1, doneNew);
        jdbc.update(set, "DROPPED", 31, droppedOld);
        jdbc.update(set, "DROPPED", 8, droppedNew);
        jdbc.update("UPDATE whatsapp_inbox SET received_at = now() - interval '40 days' WHERE id = ?", pendingOld);

        source(0).housekeeping(new WhatsappStatusHolder());

        assertThat(repository.findAllById(List.of(doneOld, doneNew, droppedOld, droppedNew, pendingOld)))
                .extracting(WhatsappInboxEvent::getId).containsExactlyInAnyOrder(doneNew, droppedNew, pendingOld);
    }
```

В `WahaIntakeTest.sync(ChatIngestWriter w)` строку создания источника заменить на

```java
        WahaInboxSource source = new WahaInboxSource(repository, inbox, fake, new WahaSessionManager(fake, "westmed", "KZ"),
                new WahaChatNames(fake), new WahaCatchUp(fake, inbox, messageRepository, objectMapper, 10), objectMapper,
                "test-hmac-key", 0, 60_000, 600_000, 7, 30);
```

- [ ] **Step 2: Убедиться, что красные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava` (`dangerouslyDisableSandbox: true`)
Expected: FAIL — нет `WahaCatchUp`, конструктор `WahaInboxSource` не тот, нет `CATCH_UP_FAILED`.

- [ ] **Step 3: Отметка догонки и предупреждение**

В `repository/ChatMessageRepository.java` (импорт `com.vladoose.nir.entity.LeadChannel`) добавить:

```java
    /** Самое позднее сообщение номера — отметка догонки WAHA (спека whatsapp-waha §5.3). Зовётся в потоке приёма с его рынком. */
    @Query("select max(m.sentAt) from ChatMessage m where m.chat.channel = :channel and m.chat.account = :account")
    OffsetDateTime findLatestSentAt(@Param("channel") LeadChannel channel, @Param("account") String account);
```

В `WhatsappStatusHolder` после `MESSAGE_DROPPED` добавить:

```java
    public static final String CATCH_UP_FAILED = "CATCH_UP_FAILED";
```

- [ ] **Step 4: Догонка**

Создать `integration/waha/WahaCatchUp.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.repository.ChatMessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Догонка пропущенного (спека whatsapp-waha §5.3): история WAHA от «самое позднее сообщение номера − 10 мин» ложится в
 * очередь синтетическими message.any с тем же ключом «fromMe_rawId» — принятое вебхуком не дублируется, а запись в чаты
 * и так отсекает дубль по (chat, external_id). Сообщений номера ещё нет (первая привязка) — догонки нет (решение 9).
 * Правки, удаления и звонки история не отдаёт — они приходят только вебхуком.
 */
@Component
public class WahaCatchUp {

    static final int PAGE = 100;
    /** 5000 сообщений за проход; остальное подберёт следующая догонка (через 10 мин). */
    static final int MAX_PAGES = 50;

    private final WahaClient client;
    private final WahaInboxWriter inbox;
    private final ChatMessageRepository messages;
    private final ObjectMapper objectMapper;
    private final long overlapMin;

    public WahaCatchUp(WahaClient client, WahaInboxWriter inbox, ChatMessageRepository messages, ObjectMapper objectMapper,
                       @Value("${chats.whatsapp.waha.catch-up-overlap-min:10}") long overlapMin) {
        this.client = client;
        this.inbox = inbox;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.overlapMin = overlapMin;
    }

    /** Сколько новых событий легло в очередь. account — номер сессии без «@c.us» (null — не привязан). */
    public int run(String session, String account) {
        if (account == null) return 0;
        OffsetDateTime latest = messages.findLatestSentAt(LeadChannel.WHATSAPP, account);
        if (latest == null) return 0;
        long from = latest.minusMinutes(overlapMin).toEpochSecond();
        int added = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<JsonNode> batch = client.history(session, from, PAGE, page * PAGE);
            for (JsonNode m : batch) {
                if (inbox.insert(envelope(session, account, m))) added++;
            }
            if (batch.size() < PAGE) break;
        }
        return added;
    }

    private ObjectNode envelope(String session, String account, JsonNode message) {
        ObjectNode env = objectMapper.createObjectNode();
        env.put("event", "message.any");
        env.put("session", session);
        env.put("timestamp", System.currentTimeMillis());
        env.put("origin", "catch-up");                  // в журнале очереди видно, что пришло не вебхуком
        env.set("me", objectMapper.createObjectNode().put("id", account + "@c.us"));
        env.set("payload", message);
        return env;
    }
}
```

- [ ] **Step 5: Источник — догонка и уборка в обслуживании**

Полностью заменить `integration/waha/WahaInboxSource.java`:

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.vladoose.nir.entity.WhatsappInboxStatus;
import com.vladoose.nir.integration.whatsapp.*;
import com.vladoose.nir.repository.WhatsappInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * WAHA как источник (спека whatsapp-waha §3, §5): очередь — таблица whatsapp_inbox, которую наполняет вебхук.
 * Событие берётся, «отлежавшись» settle-ms (правка, пришедшая чуть раньше оригинала, встанет после него), и
 * подтверждается как DONE или DROPPED с причиной. Обслуживание: раз в минуту — статус сессии; при старте, при переходе
 * в WORKING и раз в 10 мин — догонка по истории; раз в сутки — уборка очереди. WAHA не отвечает — красная строка, но
 * уже принятое разбирается дальше. Все методы, кроме name/isConfigured, зовёт поток приёма.
 */
@Component
public class WahaInboxSource implements WhatsappSource {

    private static final Logger log = LoggerFactory.getLogger(WahaInboxSource.class);
    static final long CLEANUP_EVERY_MS = 24 * 3600_000L;

    private final WhatsappInboxRepository repository;
    private final WahaInboxWriter inbox;
    private final WahaClient client;
    private final WahaSessionManager sessions;
    private final WahaChatNames names;
    private final WahaCatchUp catchUp;
    private final ObjectMapper objectMapper;
    private final String hmacKey;
    private final long settleMs;
    private final long statusRefreshMs;
    private final long catchUpMs;
    private final int doneDays;
    private final int droppedDays;
    private long nextStatusCheck;
    private long nextCatchUp;
    private long nextCleanup;
    private boolean catchUpDue;
    private boolean catchUpFailed;
    private String loggedSourceError;

    public WahaInboxSource(WhatsappInboxRepository repository, WahaInboxWriter inbox, WahaClient client,
                           WahaSessionManager sessions, WahaChatNames names, WahaCatchUp catchUp, ObjectMapper objectMapper,
                           @Value("${chats.whatsapp.waha.hmac-key:}") String hmacKey,
                           @Value("${chats.whatsapp.waha.settle-ms:3000}") long settleMs,
                           @Value("${chats.whatsapp.waha.status-refresh-ms:60000}") long statusRefreshMs,
                           @Value("${chats.whatsapp.waha.catch-up-ms:600000}") long catchUpMs,
                           @Value("${chats.whatsapp.waha.inbox-done-days:7}") int doneDays,
                           @Value("${chats.whatsapp.waha.inbox-dropped-days:30}") int droppedDays) {
        this.repository = repository;
        this.inbox = inbox;
        this.client = client;
        this.sessions = sessions;
        this.names = names;
        this.catchUp = catchUp;
        this.objectMapper = objectMapper;
        this.hmacKey = hmacKey;
        this.settleMs = settleMs;
        this.statusRefreshMs = statusRefreshMs;
        this.catchUpMs = catchUpMs;
        this.doneDays = doneDays;
        this.droppedDays = droppedDays;
    }

    @Override public String name() { return WhatsappProviders.WAHA; }

    @Override public boolean isConfigured() { return client.isConfigured() && hmacKey != null && !hmacKey.isEmpty(); }

    @Override public String configHint() {
        return "не заданы ключи шлюза WAHA (WHATSAPP_WAHA_API_KEY / WHATSAPP_WAHA_HMAC_KEY)";
    }

    @Override public String waitingNote() { return "сообщения ждут в очереди АИС"; }

    @Override
    public void housekeeping(WhatsappStatusHolder status) {
        long now = System.currentTimeMillis();
        if (now >= nextStatusCheck) {
            nextStatusCheck = now + statusRefreshMs;
            refreshSession(status);
        }
        if ((catchUpDue || now >= nextCatchUp) && WahaSessionManager.WORKING.equals(status.state())) {
            catchUpDue = false;
            nextCatchUp = now + catchUpMs;
            runCatchUp(status);
        }
        if (now >= nextCleanup) {
            nextCleanup = now + CLEANUP_EVERY_MS;
            OffsetDateTime t = OffsetDateTime.now();
            int removed = inbox.cleanup(t.minusDays(doneDays), t.minusDays(droppedDays));
            if (removed > 0) log.info("WhatsApp: из очереди убрано {} разобранных событий", removed);
        }
    }

    private void refreshSession(WhatsappStatusHolder status) {
        try {
            if (sessions.refresh(status)) catchUpDue = true;
            status.setSourceError(null);
            loggedSourceError = null;
        } catch (GatewayException e) {
            // принятое вебхуком разбирается и без WAHA: цикл не останавливаем, только красная строка
            status.setSourceError(e.getMessage() + " — принятые сообщения разбираются, файлы и догонка ждут");
            if (!Objects.equals(loggedSourceError, e.getMessage())) {
                log.warn("WhatsApp: {}", e.getMessage());
                loggedSourceError = e.getMessage();
            }
        }
    }

    private void runCatchUp(WhatsappStatusHolder status) {
        try {
            int added = catchUp.run(sessions.session(), sessions.account());
            if (added > 0) log.info("WhatsApp: догонка положила в очередь {} сообщений", added);
            if (catchUpFailed) {
                status.setSourceWarnings(List.of());
                catchUpFailed = false;
            }
        } catch (GatewayException e) {
            // приём вебхуков догонка не останавливает (спека §5.3): предупреждение и повтор в следующий раз
            status.setSourceWarnings(List.of(WhatsappStatusHolder.CATCH_UP_FAILED));
            if (!catchUpFailed) log.warn("WhatsApp: догонка не удалась — {}", e.getMessage());
            catchUpFailed = true;
        }
    }

    @Override
    public WhatsappNotification next() {
        OffsetDateTime settled = OffsetDateTime.now().minus(Duration.ofMillis(settleMs));
        return repository.findNextPending(WhatsappProviders.WAHA, settled)
                .map(e -> new WhatsappNotification(e.getId(), read(e.getPayload())))
                .orElse(null);
    }

    private JsonNode read(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException e) {
            return MissingNode.getInstance();     // вебхук кладёт только JSON; битое — парсер пропустит
        }
    }

    @Override
    public ParsedNotification parse(WhatsappNotification n) {
        ParsedNotification p = WahaEventParser.parse(n.body(), sessions.account());
        if (p instanceof ParsedNotification.State s && WahaSessionManager.WORKING.equals(s.state())) catchUpDue = true;
        if (p instanceof ParsedNotification.Message m) return names.enrich(sessions.session(), m);
        return p;
    }

    @Override
    public void ack(WhatsappNotification n, String droppedReason) {
        inbox.finish(n.id(), droppedReason == null ? WhatsappInboxStatus.DONE : WhatsappInboxStatus.DROPPED, droppedReason);
    }

    /** Файл: WAHA сама не качает (WAHA_EVENTS_DOWNLOAD_MEDIA=false) — просим сообщение с файлом, берём по media.url. */
    @Override
    public byte[] download(FileRef ref, long maxBytes) {
        WahaEventParser.MessageId id = WahaEventParser.MessageId.parse(ref.locator());
        if (id == null) throw new GatewayException(0, "WAHA: у файла нет id сообщения");
        JsonNode msg = client.message(sessions.session(), id.chatId(), ref.locator(), true);
        JsonNode url = msg == null ? null : msg.path("media").path("url");
        if (url == null || !url.isTextual() || url.asText().isBlank()) {
            throw new GatewayException(0, "WAHA: файл сообщения не скачался");
        }
        return client.downloadFile(url.asText(), maxBytes);
    }
}
```

- [ ] **Step 6: Прогнать**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.waha.*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration src/main/java/com/vladoose/nir/repository/ChatMessageRepository.java \
  src/test/java/com/vladoose/nir/integration/waha
git commit -m "feat(waha): догонка пропущенного по истории WAHA и суточная уборка очереди

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: REST «Система → WhatsApp»

**Files:**
- Create: `dto/response/WhatsappSessionResponse.java`, `service/WhatsappSessionService.java`, `controller/WhatsappSessionController.java`
- Test: `src/test/java/com/vladoose/nir/chat/WhatsappSessionServiceTest.java`, `WhatsappSessionSecurityTest.java`

**Interfaces:**
- Consumes: `WhatsappSource` (бин `@Primary`), `WhatsappStatusHolder.state()/number()`, `WahaSessionManager.live/qr/restart/logout/number`, `WhatsappProviders`, `UpstreamException` (502), `ConflictException` (409).
- Produces (для фронта задачи 11):
  - `GET /api/whatsapp/session` → `{provider, enabled, configured, status, number, name, qrAvailable, error}`;
  - `GET /api/whatsapp/session/qr` → `image/png`, `Cache-Control: no-store`; не WAHA / выключено → 409, WAHA ответила ошибкой → 502;
  - `POST /api/whatsapp/session/restart`, `POST /api/whatsapp/session/logout` → тот же ответ, что GET;
  - всё — только ADMIN.

- [ ] **Step 1: Тесты (красные)**

Создать `src/test/java/com/vladoose/nir/chat/WhatsappSessionServiceTest.java`:

```java
package com.vladoose.nir.chat;

import com.vladoose.nir.dto.response.WhatsappSessionResponse;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.waha.FakeWahaClient;
import com.vladoose.nir.integration.waha.WahaSession;
import com.vladoose.nir.integration.waha.WahaSessionManager;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.WhatsappSource;
import com.vladoose.nir.integration.whatsapp.WhatsappStatusHolder;
import com.vladoose.nir.service.WhatsappSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.*;

class WhatsappSessionServiceTest {

    final FakeWahaClient fake = new FakeWahaClient();
    final WhatsappStatusHolder status = new WhatsappStatusHolder();
    final WahaSessionManager sessions = new WahaSessionManager(fake, "westmed", "KZ");
    final WhatsappSource waha = source("waha", true);

    static WhatsappSource source(String name, boolean configured) {
        WhatsappSource s = Mockito.mock(WhatsappSource.class);
        Mockito.when(s.name()).thenReturn(name);
        Mockito.when(s.isConfigured()).thenReturn(configured);
        return s;
    }

    WhatsappSessionService service(WhatsappSource source, boolean enabled) {
        return new WhatsappSessionService(source, status, sessions, enabled);
    }

    @Test
    void waitingForLinkShowsQr() {
        fake.session = new WahaSession("westmed", "SCAN_QR_CODE", null, null);

        WhatsappSessionResponse r = service(waha, true).info();

        assertThat(r.getProvider()).isEqualTo("waha");
        assertThat(r.getStatus()).isEqualTo("SCAN_QR_CODE");
        assertThat(r.isQrAvailable()).isTrue();
        assertThat(r.getNumber()).isNull();
    }

    @Test
    void linkedNumberAndNameAreShown() {
        fake.session = new WahaSession("westmed", "WORKING", "77000000001@c.us", "West-Med");

        WhatsappSessionResponse r = service(waha, true).info();

        assertThat(r.getStatus()).isEqualTo("WORKING");
        assertThat(r.getNumber()).isEqualTo("77000000001");
        assertThat(r.getName()).isEqualTo("West-Med");
        assertThat(r.isQrAvailable()).isFalse();
    }

    /** WAHA не ответила — страница показывает причину, а не падает. */
    @Test
    void unreachableWahaIsErrorTextNotFailure() {
        fake.failWith = new GatewayException(0, "WAHA недоступен при запросе состояния сессии: ConnectException");

        assertThat(service(waha, true).info().getError()).contains("WAHA недоступен");
    }

    @Test
    void actionsNeedEnabledWaha() {
        assertThatThrownBy(() -> service(waha, false).restart()).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service(source("greenapi", true), true).logout()).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service(source("waha", false), true).qr()).isInstanceOf(ConflictException.class);
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void restartLogoutAndQrGoToWaha() {
        fake.session = new WahaSession("westmed", "FAILED", "77000000001@c.us", "West-Med");
        WhatsappSessionService s = service(waha, true);

        s.restart();
        s.logout();
        byte[] qr = s.qr();

        assertThat(fake.calls).contains("restart westmed", "logout westmed", "qr westmed");
        assertThat(qr).isEqualTo(fake.qr);
    }

    @Test
    void wahaErrorOnActionIsBadGateway() {
        fake.failWith = new GatewayException(422, "WAHA: HTTP 422 при получении QR-кода");

        assertThatThrownBy(() -> service(waha, true).qr()).isInstanceOf(UpstreamException.class).hasMessageContaining("422");
    }

    /** Green-API: привязка — в его кабинете; страница показывает последнее известное состояние и в WAHA не ходит. */
    @Test
    void greenApiShowsLastKnownState() {
        status.setState("authorized");
        status.setNumber("77000000001@c.us");

        WhatsappSessionResponse r = service(source("greenapi", true), true).info();

        assertThat(r.getStatus()).isEqualTo("authorized");
        assertThat(r.getNumber()).isEqualTo("77000000001");
        assertThat(fake.calls).isEmpty();
    }
}
```

Создать `src/test/java/com/vladoose/nir/chat/WhatsappSessionSecurityTest.java`:

```java
package com.vladoose.nir.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Права «Система → WhatsApp» через настоящую цепочку фильтров; MockMvc — из общего контекста (CLAUDE.md §14). */
@SpringBootTest
@Transactional
class WhatsappSessionSecurityTest {

    @Autowired WebApplicationContext wac;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/whatsapp/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/whatsapp/session/qr")).andExpect(status().isUnauthorized());
    }

    /** QR — это доступ к рабочему WhatsApp: оператор не видит и не трогает. */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotSeeOrManageTheNumber() throws Exception {
        mvc.perform(get("/api/whatsapp/session")).andExpect(status().isForbidden());
        mvc.perform(get("/api/whatsapp/session/qr")).andExpect(status().isForbidden());
        mvc.perform(post("/api/whatsapp/session/restart")).andExpect(status().isForbidden());
        mvc.perform(post("/api/whatsapp/session/logout")).andExpect(status().isForbidden());
    }

    /** В тестах приём выключен: страница честно говорит «выключено», в WAHA не ходит, действия — 409. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminSeesDisabledIntake() throws Exception {
        mvc.perform(get("/api/whatsapp/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("waha"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.qrAvailable").value(false));
        mvc.perform(post("/api/whatsapp/session/restart")).andExpect(status().isConflict());
        mvc.perform(get("/api/chats/status")).andExpect(jsonPath("$.provider").value("waha"));
    }
}
```

- [ ] **Step 2: Убедиться, что красные**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava` (`dangerouslyDisableSandbox: true`)
Expected: FAIL — нет `WhatsappSessionService`, `WhatsappSessionResponse`.

- [ ] **Step 3: Реализация**

Создать `dto/response/WhatsappSessionResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

/** «Система → WhatsApp» (спека whatsapp-waha §7, §9). */
@Data
public class WhatsappSessionResponse {
    /** waha / greenapi. */
    private String provider;
    private boolean enabled;
    private boolean configured;
    /** WAHA: STOPPED / STARTING / SCAN_QR_CODE / WORKING / FAILED / PASSKEY_*; Green-API: stateInstance. */
    private String status;
    private String number;
    /** Имя профиля привязанного номера. */
    private String name;
    /** Номер ждёт привязки — страница показывает QR. */
    private boolean qrAvailable;
    /** WAHA не ответила на запрос статуса — текст без адреса и ключа. */
    private String error;
}
```

Создать `service/WhatsappSessionService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.WhatsappSessionResponse;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.waha.WahaSession;
import com.vladoose.nir.integration.waha.WahaSessionManager;
import com.vladoose.nir.integration.whatsapp.GatewayException;
import com.vladoose.nir.integration.whatsapp.WhatsappProviders;
import com.vladoose.nir.integration.whatsapp.WhatsappSource;
import com.vladoose.nir.integration.whatsapp.WhatsappStatusHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Страница «Система → WhatsApp» (спека whatsapp-waha §7): у WAHA — живой статус сессии (не ждёт минутного обновления),
 * QR, перезапуск и отвязка; у Green-API — последнее известное состояние (привязка — в его кабинете).
 */
@Service
public class WhatsappSessionService {

    private final WhatsappSource source;
    private final WhatsappStatusHolder status;
    private final WahaSessionManager sessions;
    private final boolean enabled;

    public WhatsappSessionService(WhatsappSource source, WhatsappStatusHolder status, WahaSessionManager sessions,
                                  @Value("${chats.whatsapp.enabled:false}") boolean enabled) {
        this.source = source;
        this.status = status;
        this.sessions = sessions;
        this.enabled = enabled;
    }

    public WhatsappSessionResponse info() {
        WhatsappSessionResponse r = new WhatsappSessionResponse();
        r.setProvider(source.name());
        r.setEnabled(enabled);
        r.setConfigured(source.isConfigured());
        r.setStatus(status.state());
        r.setNumber(status.number());
        if (!wahaActive()) return r;
        try {
            WahaSession s = sessions.live();
            r.setStatus(s == null ? null : s.status());
            r.setNumber(WahaSessionManager.number(s));
            r.setName(s == null ? null : s.mePushName());
            r.setQrAvailable(s != null && WahaSessionManager.SCAN_QR_CODE.equals(s.status()));
        } catch (GatewayException e) {
            r.setError(e.getMessage());
        }
        return r;
    }

    public byte[] qr() {
        requireWaha();
        try {
            return sessions.qr();
        } catch (GatewayException e) {
            throw new UpstreamException(e.getMessage());
        }
    }

    public WhatsappSessionResponse restart() {
        requireWaha();
        try {
            sessions.restart();
        } catch (GatewayException e) {
            throw new UpstreamException(e.getMessage());
        }
        return info();
    }

    public WhatsappSessionResponse logout() {
        requireWaha();
        try {
            sessions.logout();
        } catch (GatewayException e) {
            throw new UpstreamException(e.getMessage());
        }
        return info();
    }

    private boolean wahaActive() {
        return enabled && WhatsappProviders.WAHA.equals(source.name()) && source.isConfigured();
    }

    private void requireWaha() {
        if (!wahaActive()) {
            throw new ConflictException("Управление номером доступно, когда приём WhatsApp включён через WAHA");
        }
    }
}
```

Создать `controller/WhatsappSessionController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.WhatsappSessionResponse;
import com.vladoose.nir.service.WhatsappSessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** «Система → WhatsApp» (спека whatsapp-waha §7, §9): только администратор — QR даёт доступ к рабочему WhatsApp. */
@RestController
@RequestMapping("/api/whatsapp/session")
public class WhatsappSessionController {

    private final WhatsappSessionService service;

    public WhatsappSessionController(WhatsappSessionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public WhatsappSessionResponse get() {
        return service.info();
    }

    /** Картинку отдаёт АИС, получив у WAHA внутри сервера; не кешировать — код живёт 20–60 с. */
    @GetMapping("/qr")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> qr() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(service.qr());
    }

    @PostMapping("/restart")
    @PreAuthorize("hasRole('ADMIN')")
    public WhatsappSessionResponse restart() {
        return service.restart();
    }

    @PostMapping("/logout")
    @PreAuthorize("hasRole('ADMIN')")
    public WhatsappSessionResponse logout() {
        return service.logout();
    }
}
```

- [ ] **Step 4: Прогнать**

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.chat.WhatsappSession*'`
Expected: PASS.

- [ ] **Step 5: Полный гейт бэкенда**

Run: `./gradlew cleanTest test` (`dangerouslyDisableSandbox: true`)
Expected: 0 падений.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/response/WhatsappSessionResponse.java \
  src/main/java/com/vladoose/nir/service/WhatsappSessionService.java src/main/java/com/vladoose/nir/controller/WhatsappSessionController.java \
  src/test/java/com/vladoose/nir/chat/WhatsappSessionServiceTest.java src/test/java/com/vladoose/nir/chat/WhatsappSessionSecurityTest.java
git commit -m "feat(waha): REST «Система → WhatsApp» — статус, QR, перезапуск, отвязка (только администратор)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Dev-стаб WAHA и живая проверка приёма без телефона

**Files:**
- Create: `scripts/stub-files.mjs` (вынесено из стаба Green-API), `scripts/waha-stub.mjs`
- Modify: `scripts/greenapi-stub.mjs` (берёт файлы из `stub-files.mjs`)

**Interfaces:**
- Consumes: весь бэкенд задач 1–9.
- Produces: стаб на `:7708` — API сессии (создание, статусы, QR), сообщение по id с файлом, файлы, история для догонки, справочники; служебные ручки `POST /__link`, `/__status/{STATUS}`, `/__scenario/basic`, `/__scenario/offline`, `/__send`, `GET /__messages`; вебхуки подписаны HMAC-SHA512 ключом `dev-hmac` и уходят на `http://localhost:8080/api/whatsapp/waha/webhook`. Ключ API стаба — `dev-key`.

- [ ] **Step 1: Файлы стабов — в общий модуль**

```bash
cd /Users/vlad/IdeaProjects/AIS
{ echo "// Файлы для dev-стабов шлюзов WhatsApp (Green-API, WAHA): PNG и XLSX собираются на лету (zlib.crc32 — Node ≥ 20.15)."
  echo "import { crc32, deflateSync } from 'node:zlib';"
  echo
  sed -n '31,151p;159,172p' scripts/greenapi-stub.mjs
} > scripts/stub-files.mjs
perl -pi -e 's/^function (png|xlsx)\(/export function $1(/; s/^const (XLSX_ROWS|XLSX_MIME|BIG_ROWS|FILES) =/export const $1 =/' scripts/stub-files.mjs
perl -ni -e 'print unless ($. >= 29 && $. <= 151) || ($. >= 159 && $. <= 172)' scripts/greenapi-stub.mjs
perl -pi -e "s#^import \{ crc32, deflateSync \} from 'node:zlib';#import { xlsx, XLSX_ROWS, FILES } from './stub-files.mjs';#" scripts/greenapi-stub.mjs
node --check scripts/stub-files.mjs && node --check scripts/greenapi-stub.mjs
node -e "import('./scripts/stub-files.mjs').then(m => console.log(Object.keys(m).sort().join(' ')))"
node scripts/greenapi-stub.mjs --write-xlsx /private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/c947d9ac-6874-4d66-8090-31f80e0e2615/scratchpad/check.xlsx
```

Expected: `BIG_ROWS FILES XLSX_MIME XLSX_ROWS png xlsx`, затем `записан …/check.xlsx`. (Номера строк сняты с файла на момент написания плана; если `grep -n "^// ---------- файлы" scripts/greenapi-stub.mjs` показывает не 29 — стаб правили, сдвинуть диапазоны соответственно.)

- [ ] **Step 2: Стаб WAHA**

Создать `scripts/waha-stub.mjs`:

```js
#!/usr/bin/env node
// Стаб WAHA для живых проверок «Чатов», карточки обращения и «Система → WhatsApp» без WhatsApp
// (спека docs/superpowers/specs/2026-09-28-whatsapp-waha-design.md §12). Только для разработки.
//
// Запуск:    node scripts/waha-stub.mjs                                       (порт 7708; другой — STUB_PORT=…)
// Бэкенд:    WHATSAPP_ENABLED=true WHATSAPP_PROVIDER=waha WHATSAPP_WAHA_URL=http://localhost:7708 \
//            WHATSAPP_WAHA_API_KEY=dev-key WHATSAPP_WAHA_HMAC_KEY=dev-hmac WHATSAPP_INITIAL_DELAY_MS=3000 ./gradlew bootRun
// Привязка:  сессию создаёт сама АИС → «Система → WhatsApp» показывает QR; «отсканировать» телефоном:
//            curl -s -X POST localhost:7708/__link
// Сценарий:  curl -s -X POST localhost:7708/__scenario/basic    — корзина сайта, Excel, фото, ответ с телефона, PDF, HTML,
//                                                                  правка, группа (фото, удаление), звонки, файл 40 МБ, скрытый номер
//            curl -s -X POST localhost:7708/__scenario/offline  — два сообщения БЕЗ вебхука: их найдёт догонка
//                                                                  (перезапуск бэкенда, ≤10 мин или POST /__status/WORKING)
// Статус:    curl -s -X POST localhost:7708/__status/FAILED     (STOPPED / STARTING / SCAN_QR_CODE / WORKING / PASSKEY_REQUIRED)
// Своё:      curl -s localhost:7708/__send -H 'Content-Type: application/json' -d '{"event":"message.any","payload":{…}}'
// Хранилище: curl -s localhost:7708/__messages
//
// Ключ API (STUB_KEY, по умолчанию dev-key) проверяется, как у настоящей WAHA: чужой — 401. Вебхуки подписываются
// HMAC-SHA512 ключом STUB_HMAC (dev-hmac) и уходят на STUB_HOOK (http://localhost:8080/api/whatsapp/waha/webhook).

import http from 'node:http';
import { createHmac, randomUUID } from 'node:crypto';
import { png, FILES } from './stub-files.mjs';

const PORT = Number(process.env.STUB_PORT || 7708);
const KEY = process.env.STUB_KEY || 'dev-key';
const HMAC = process.env.STUB_HMAC || 'dev-hmac';
const HOOK = process.env.STUB_HOOK || 'http://localhost:8080/api/whatsapp/waha/webhook';
const BASE = `http://localhost:${PORT}`;
const ME = { id: '77000000001@c.us', pushName: 'West-Med (стаб)' };

// ---------- сессия ----------

let session = null;          // { name, status }
let linked = false;
const sessionView = () => session && { name: session.name, status: session.status, me: linked ? ME : null, engine: { engine: 'GOWS' } };

// ---------- справочники «телефона» ----------

const CONTACTS = {
  '77011234567@c.us': { name: 'Айгерим (тест)', pushname: 'Aigerim' },
  '77029876543@c.us': { name: 'Ерлан (тест)', pushname: 'Erlan' },
  '77051112233@c.us': { name: null, pushname: 'Сауле' },
  '77019998877@c.us': { name: 'Марат (тест)', pushname: 'Marat' },
};
const GROUPS = { '120363000000000001@g.us': 'Коллеги West-Med (тест)' };
const LIDS = { '123456789012345@lid': '77071234567@c.us' };

// ---------- сообщения «телефона»: всё, что пришло, — для файлов и догонки ----------

const store = new Map();     // id сообщения WAHA → payload message.any
const fileOf = new Map();    // id сообщения → имя файла в FILES
let seq = 0;
const now = () => Math.floor(Date.now() / 1000);
const rawId = () => 'STUB' + Date.now().toString(16).toUpperCase() + (++seq);
const serverJid = (jid) => (jid.endsWith('@c.us') ? jid.replace('@c.us', '@s.whatsapp.net') : jid);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function message({ chatId, fromMe = false, participant = null, pushName = null, body = null, content = {}, media = null, source }) {
  const raw = rawId();
  const id = `${fromMe}_${chatId}_${raw}` + (participant ? `_${participant}` : '');
  const p = {
    id, timestamp: now(), from: fromMe ? ME.id : chatId, fromMe, to: fromMe ? chatId : ME.id,
    ...(participant ? { participant } : {}),
    ...(source ? { source } : {}),
    body, hasMedia: !!media, media: media ? { url: null, mimetype: media.mimetype, filename: media.filename } : null,
    ack: fromMe ? 1 : 0,
    _data: {
      Info: {
        Chat: serverJid(chatId), Sender: serverJid(fromMe ? ME.id : participant || chatId), IsFromMe: fromMe,
        IsGroup: chatId.endsWith('@g.us'), ID: raw, ...(pushName ? { PushName: pushName } : {}),
      },
      Message: content,
    },
  };
  store.set(id, p);
  return p;
}

const text = (t) => ({ conversation: t });
const incoming = (chatId, pushName, t) => message({ chatId, pushName, body: t, content: text(t) });
const phoneReply = (chatId, t) => message({ chatId, fromMe: true, source: 'app', body: t, content: text(t) });

/** Файл из FILES: размер — в содержимом GOWS (fileLength), байты — по media.url после downloadMedia=true. */
function withFile({ chatId, pushName, participant = null, key, name, caption = null }) {
  const f = FILES[name];
  const p = message({
    chatId, pushName, participant, body: caption,
    content: { [key]: { mimetype: f.mime, fileName: name, fileLength: f.data.length, ...(caption ? { caption } : {}) } },
    media: { mimetype: f.mime, filename: key === 'imageMessage' ? null : name },
  });
  fileOf.set(p.id, name);
  return p;
}

const rawOf = (p) => p._data.Info.ID;

function edit(original, newText) {
  const chatId = original.fromMe ? original.to : original.from;
  return {
    id: `${original.fromMe}_${chatId}_${rawId()}`, timestamp: now(), from: original.from, fromMe: original.fromMe, to: original.to,
    body: newText, editedMessageId: rawOf(original), _data: { Info: { ...original._data.Info, ID: rawId() } },
  };
}

function revoke(original, chatId) {
  return {
    before: null, revokedMessageId: rawOf(original),
    after: { id: `${original.fromMe}_${chatId}_${rawId()}`, timestamp: now(), from: original.from, fromMe: original.fromMe, body: null, _data: {} },
  };
}

const call = (id, from, isVideo) => ({ id, from, timestamp: now(), isVideo, isGroup: false, _data: { CallID: id, From: serverJid(from) } });

// ---------- вебхук: подпись HMAC-SHA512 сырого тела, как у WAHA ----------

async function hook(event, payload) {
  const body = JSON.stringify({
    id: 'evt_' + randomUUID().replaceAll('-', ''), timestamp: Date.now(), event, session: session?.name || 'westmed',
    metadata: { market: 'KZ' }, me: linked ? ME : null, payload, engine: 'GOWS', environment: { version: 'stub' },
  });
  try {
    const r = await fetch(HOOK, {
      method: 'POST', body,
      headers: {
        'Content-Type': 'application/json', 'X-Webhook-Request-Id': 'req_' + randomUUID(), 'X-Webhook-Timestamp': String(Date.now()),
        'X-Webhook-Hmac': createHmac('sha512', HMAC).update(body).digest('hex'), 'X-Webhook-Hmac-Algorithm': 'sha512',
      },
    });
    console.log(`вебхук ${event} → ${r.status}`);
    return r.status;
  } catch (e) {
    console.log(`вебхук ${event} → не доставлен (${e.cause?.code || e.message})`);
    return 0;
  }
}

async function basicScenario() {
  const aigerim = '77011234567@c.us';
  const erlan = '77029876543@c.us';
  const saule = '77051112233@c.us';
  const group = '120363000000000001@g.us';
  // без привязки у событий нет me, а номер сессии АИС ещё не знает — сообщения ушли бы в попытки и пропуск
  if (!linked) return ['сначала «привяжите» номер: curl -X POST localhost:7708/__link'];
  const out = [];
  const go = async (event, payload) => { out.push(`${event}: ${await hook(event, payload)}`); await sleep(200); };

  await go('message.any', incoming(aigerim, 'Aigerim', 'Здравствуйте! Интересует следующее оборудование:\n\n'
    + '1. Облучатель ОБН-150 (x2)\n2. Рециркулятор СН-111-130\n\nПрошу подготовить коммерческое предложение.'));
  await go('message.any', withFile({ chatId: aigerim, pushName: 'Aigerim', key: 'documentMessage', name: 'Заявка клиники.xlsx', caption: 'Полный список во вложении' }));
  await go('message.any', withFile({ chatId: aigerim, pushName: 'Aigerim', key: 'imageMessage', name: 'аппарат.png', caption: 'Вот такой стоит сейчас' }));
  await go('message.any', phoneReply(aigerim, 'Добрый день! Подготовим КП сегодня.'));
  await go('message.any', withFile({ chatId: aigerim, pushName: 'Aigerim', key: 'documentMessage', name: 'ТЗ.pdf' }));
  await go('message.any', withFile({ chatId: aigerim, pushName: 'Aigerim', key: 'documentMessage', name: 'страница.html',
    caption: 'проверка: должно скачиваться, а не открываться' }));
  const uzi = incoming(erlan, 'Erlan', 'Нужен аппарат УЗИ, какие есть?');
  await go('message.any', uzi);
  await go('message.edited', edit(uzi, 'Нужен аппарат УЗИ экспертного класса'));
  const g = message({ chatId: group, participant: '77025556677@c.us', pushName: 'Данияр', body: 'Кто завтра едет в Уральск?',
    content: text('Кто завтра едет в Уральск?') });
  await go('message.any', g);
  await go('message.any', withFile({ chatId: group, participant: '77025556677@c.us', pushName: 'Данияр', key: 'imageMessage',
    name: 'аппарат.png', caption: 'фото из группы' }));
  await go('message.revoked', revoke(g, group));
  const voice = 'CALL' + rawId();
  await go('call.received', call(voice, saule, false));
  await sleep(1500);
  await go('call.accepted', call(voice, saule, false));
  const video = 'CALL' + rawId();
  await go('call.received', call(video, aigerim, true));
  await sleep(1500);
  await go('call.rejected', call(video, aigerim, true));
  const big = message({ chatId: erlan, pushName: 'Erlan', body: 'каталог целиком',
    content: { documentMessage: { mimetype: 'application/pdf', fileName: 'Каталог 40 МБ.pdf', fileLength: 40 * 1024 * 1024 } },
    media: { mimetype: 'application/pdf', filename: 'Каталог 40 МБ.pdf' } });
  await go('message.any', big);
  const hidden = message({ chatId: '123456789012345@lid', pushName: 'Скрытый номер (тест)', body: 'Добрый день, по поводу стерилизатора',
    content: text('Добрый день, по поводу стерилизатора') });
  hidden._data.Info.SenderAlt = '77071234567@s.whatsapp.net';
  await go('message.any', hidden);
  return out;
}

/** Пришло, пока АИС лежала: только на «телефоне», без вебхука — найдёт догонка. */
function offlineScenario() {
  incoming('77019998877@c.us', 'Marat', 'Добрый день! Пишу, пока АИС была выключена.');
  incoming('77019998877@c.us', 'Marat', 'Нужен дефибриллятор, есть в наличии?');
  return 2;
}

// ---------- сервер ----------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, BASE);
  const path = url.pathname;
  const reply = (status, payload, type = 'application/json') => {
    const b = Buffer.isBuffer(payload) ? payload : JSON.stringify(payload);
    res.writeHead(status, { 'Content-Type': type });
    res.end(b);
  };
  const readBody = async () => { let s = ''; for await (const c of req) s += c; return s; };
  let m;

  // служебные ручки стаба — без ключа
  if (req.method === 'POST' && path === '/__link') {
    linked = true;
    if (session) session.status = 'WORKING';
    await hook('session.status', { name: session?.name || 'westmed', status: 'WORKING' });
    return reply(200, sessionView());
  }
  if (req.method === 'POST' && path.startsWith('/__status/')) {
    const s = decodeURIComponent(path.slice('/__status/'.length));
    if (session) session.status = s;
    await hook('session.status', { name: session?.name || 'westmed', status: s });
    return reply(200, sessionView());
  }
  if (req.method === 'POST' && path === '/__scenario/basic') return reply(200, { sent: await basicScenario() });
  if (req.method === 'POST' && path === '/__scenario/offline') return reply(200, { stored: offlineScenario() });
  if (req.method === 'POST' && path === '/__send') {
    const e = JSON.parse((await readBody()) || '{}');
    return reply(200, { status: await hook(e.event, e.payload) });
  }
  if (req.method === 'GET' && path === '/__messages') return reply(200, [...store.values()]);
  if (path === '/ping') return reply(200, { message: 'pong' });

  // API WAHA — только с ключом
  if (!path.startsWith('/api/')) return reply(404, {});
  if (req.headers['x-api-key'] !== KEY) return reply(401, { message: 'Unauthorized' });

  if (req.method === 'GET' && (m = path.match(/^\/api\/sessions\/([^/]+)$/))) {
    return session ? reply(200, sessionView()) : reply(404, { message: 'Session not found' });
  }
  if (req.method === 'POST' && path === '/api/sessions') {
    const b = JSON.parse((await readBody()) || '{}');
    if (session) return reply(422, { message: 'Session already exists' });
    session = { name: b.name, status: linked ? 'WORKING' : 'SCAN_QR_CODE' };
    console.log('сессия создана, config:', JSON.stringify(b.config));
    await hook('session.status', { name: session.name, status: session.status });
    return reply(201, sessionView());
  }
  if (req.method === 'POST' && (m = path.match(/^\/api\/sessions\/([^/]+)\/(start|restart|logout)$/))) {
    if (!session) return reply(404, {});
    if (m[2] === 'logout') linked = false;
    session.status = linked ? 'WORKING' : 'SCAN_QR_CODE';
    await hook('session.status', { name: session.name, status: session.status });
    return reply(201, sessionView());
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/auth\/qr$/))) {
    if (!session || session.status !== 'SCAN_QR_CODE') return reply(422, { message: 'Session status is not SCAN_QR_CODE' });
    return reply(200, png(240, 240), 'image/png');        // не QR — сканировать нечего, проверяется показ и обновление
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/chats\/all\/messages$/))) {
    const from = Number(url.searchParams.get('filter.timestamp.gte') || 0);
    const limit = Number(url.searchParams.get('limit') || 100);
    const offset = Number(url.searchParams.get('offset') || 0);
    const list = [...store.values()].filter((x) => x.timestamp >= from).sort((a, b) => a.timestamp - b.timestamp);
    return reply(200, list.slice(offset, offset + limit));
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/chats\/([^/]+)\/messages\/([^/]+)$/))) {
    const id = decodeURIComponent(m[3]);
    const p = store.get(id);
    if (!p) return reply(404, { message: 'Message not found' });
    const copy = structuredClone(p);
    if (url.searchParams.get('downloadMedia') === 'true' && fileOf.has(id)) {
      copy.media = { ...copy.media, url: `${BASE}/api/files/${m[1]}/${encodeURIComponent(id)}` };
    }
    return reply(200, copy);
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/files\/([^/]+)\/(.+)$/))) {
    const f = FILES[fileOf.get(decodeURIComponent(m[2]))];
    return f ? reply(200, f.data, f.mime) : reply(404, {});
  }
  if (req.method === 'GET' && path === '/api/contacts') {
    const id = url.searchParams.get('contactId');
    const c = CONTACTS[id];
    return c ? reply(200, { id, number: id.split('@')[0], ...c }) : reply(404, {});
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/groups\/([^/]+)$/))) {
    const id = decodeURIComponent(m[2]);
    return GROUPS[id] ? reply(200, { id, subject: GROUPS[id] }) : reply(404, {});
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/lids\/([^/]+)$/))) {
    const lid = decodeURIComponent(m[2]);
    return LIDS[lid] ? reply(200, { lid, pn: LIDS[lid] }) : reply(404, {});
  }
  return reply(404, {});
});

server.listen(PORT, '127.0.0.1', () => console.log(`Стаб WAHA: ${BASE} (ключ API ${KEY === 'dev-key' ? 'dev-key' : 'из STUB_KEY'}, вебхуки → ${HOOK})`));
```

Run: `node --check scripts/waha-stub.mjs`
Expected: без вывода (синтаксис в порядке).

- [ ] **Step 3: Живой прогон приёма (бэкенд + стаб, без фронта)**

Перед стартом: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill` (если бэкенд запущен). Затем в фоне (`run_in_background`, `dangerouslyDisableSandbox: true`):

```bash
cd /Users/vlad/IdeaProjects/AIS && node scripts/waha-stub.mjs
cd /Users/vlad/IdeaProjects/AIS && WHATSAPP_ENABLED=true WHATSAPP_PROVIDER=waha WHATSAPP_WAHA_URL=http://localhost:7708 \
  WHATSAPP_WAHA_API_KEY=dev-key WHATSAPP_WAHA_HMAC_KEY=dev-hmac WHATSAPP_INITIAL_DELAY_MS=3000 ./gradlew bootRun
```

Когда бэкенд поднялся (`curl -s localhost:8080/api/auth/passkey-config` → 200):

```bash
S=/private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/c947d9ac-6874-4d66-8090-31f80e0e2615/scratchpad
curl -s -c $S/ck -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' localhost:8080/api/auth/login >/dev/null
curl -s -b $S/ck localhost:8080/api/whatsapp/session            # АИС создала сессию: "status":"SCAN_QR_CODE","qrAvailable":true
curl -s -b $S/ck -o $S/qr.png -w '%{http_code} %{content_type}\n' localhost:8080/api/whatsapp/session/qr   # 200 image/png
curl -s -X POST localhost:7708/__link >/dev/null; sleep 5
curl -s -b $S/ck localhost:8080/api/whatsapp/session            # "WORKING", "number":"77000000001"
curl -s -X POST localhost:7708/__scenario/basic                 # все вебхуки → 200
sleep 15
curl -s -b $S/ck -H 'X-Market: KZ' localhost:8080/api/chats | head -c 1500
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "select event, status, count(*) from whatsapp_inbox group by 1,2 order by 1,2"
```

Expected:
- в `/api/chats` — «Айгерим (тест)» (имя из записной книжки стаба), «Ерлан (тест)», «Коллеги West-Med (тест)», чат Сауле с превью «📞 Входящий звонок — принят», чат со скрытым номером (телефон +77071234567);
- в очереди все события `DONE`, `PENDING` нет;
- `GET /api/chats/{id Айгерим}/messages` — Excel и фото сохранены, HTML — вложением, у Ерлана «Каталог 40 МБ.pdf» с `notStoredReason: TOO_LARGE`, у группы фото `GROUP`.

Догонка:

```bash
curl -s -X POST localhost:7708/__scenario/offline
curl -s -X POST localhost:7708/__status/WORKING; sleep 10
curl -s -b $S/ck -H 'X-Market: KZ' "localhost:8080/api/chats?q=Марат" | head -c 600       # чат «Марат (тест)» с двумя сообщениями
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "select count(*) from whatsapp_inbox where payload like '%\"origin\":\"catch-up\"%'"
```

Expected: чат Марата есть; синтетических событий догонки > 0; дублей сообщений basic-сценария нет (`select chat_id, external_id, count(*) from chat_message group by 1,2 having count(*) > 1` — пусто).

Сбой WAHA: остановить стаб (`lsof -ti tcp:7708 -sTCP:LISTEN | xargs kill`), через ~70 с `curl -s -b $S/ck localhost:8080/api/chats/status` → `lastError` содержит «WAHA недоступен» и «принятые сообщения разбираются». Запустить стаб снова — строка очищается за ≤1 мин (сессия в стабе создастся заново: он хранит всё в памяти).

Остановить бэкенд и стаб (только слушающие процессы, §14).

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add scripts/stub-files.mjs scripts/waha-stub.mjs scripts/greenapi-stub.mjs
git commit -m "chore(waha): dev-стаб WAHA (сессия, QR, файлы, история, подписанные вебхуки); файлы стабов — в общий модуль

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Фронт — «Система → WhatsApp», тексты состояния WAHA, строка звонка

**Files:**
- Create: `frontend/src/app/pages/whatsapp/whatsapp.component.ts`
- Modify: `frontend/src/app/services/api.service.ts`, `frontend/src/app/shared/whatsapp-status.ts`, `frontend/src/app/shared/chat-message.component.ts`, `frontend/src/app/app.routes.ts`, `frontend/src/app/app.config.ts`, `frontend/src/app/layout/layout.component.ts`

**Interfaces:**
- Consumes: REST задачи 9 (`/api/whatsapp/session*`), `/api/chats/status` (+ `provider`), сообщения `type: 'CALL'` (задача 2).
- Produces: маршрут `/whatsapp` (только администратор), пункт меню «WhatsApp» в группе «Система» с иконкой `qr-code`.

- [ ] **Step 1: API**

В `frontend/src/app/services/api.service.ts` сразу после метода `getWhatsappStatus()` добавить:

```ts
  // === WhatsApp: сессия шлюза — «Система → WhatsApp» (только ADMIN; спека 2026-09-28-whatsapp-waha §7) ===
  getWhatsappSession(): Observable<any> {
    return this.http.get<any>(`${this.base}/whatsapp/session`);
  }
  /** QR — только blob через HttpClient: голый <img src> на /api не несёт ни сессии перехватчиков, ни обработки ошибок. */
  getWhatsappQr(): Observable<Blob> {
    return this.http.get(`${this.base}/whatsapp/session/qr`, { responseType: 'blob' });
  }
  restartWhatsappSession(): Observable<any> {
    return this.http.post<any>(`${this.base}/whatsapp/session/restart`, {});
  }
  logoutWhatsappSession(): Observable<any> {
    return this.http.post<any>(`${this.base}/whatsapp/session/logout`, {});
  }
```

- [ ] **Step 2: Тексты строки состояния для WAHA**

Полностью заменить `frontend/src/app/shared/whatsapp-status.ts`:

```ts
import { relativeTime } from './relative-time';

/** Ответ GET /api/chats/status (спеки whatsapp-chats §7, whatsapp-waha §7). */
export interface WhatsappStatus {
  enabled: boolean;
  configured: boolean;
  /** Шлюз: waha / greenapi; опечатка в WHATSAPP_PROVIDER приходит как есть (configured=false, lastError объясняет). */
  provider: string | null;
  state: string | null;
  number: string | null;
  lastMessageAt: string | null;
  warnings: string[] | null;
  lastError: string | null;
  /** Сколько сообщений за сутки пропущено как «ядовитые». */
  droppedCount: number;
  /** Рынок, в который пишет зеркало (KZ/RF). */
  market: string | null;
}

export interface StatusLine { text: string; error: boolean; }

const WARNING_TEXT: Record<string, string> = {
  WEBHOOK_URL_SET: 'в инстансе указан адрес вебхука — сообщения уходят туда, а не в АИС. Очистите поле в кабинете Green-API',
  INCOMING_OFF: 'не включены уведомления о входящих',
  OUTGOING_PHONE_OFF: 'не включены уведомления об ответах с телефона — ваши ответы не попадут в АИС',
  QUOTA_EXCEEDED: 'исчерпан лимит бесплатного тарифа — сообщения из новых чатов не приходят',
  CATCH_UP_FAILED: 'не удалось догнать сообщения, пришедшие без АИС, — повтор через 10 мин',
};

/** Подписи — как в селекторе рынка слева вверху. */
const MARKET_LABEL: Record<string, string> = { KZ: 'West-Med (KZ)', RF: 'Регион-Мед (РФ)' };

function droppedText(n: number): string {
  return n > 1
    ? `не удалось принять сообщений: ${n} — посмотрите их в телефоне`
    : 'одно сообщение не удалось принять — посмотрите его в телефоне';
}

const STATE_TEXT: Record<string, StatusLine> = {
  // Green-API
  notAuthorized: { text: 'WhatsApp: номер не подключён — отсканируйте QR-код в кабинете Green-API', error: true },
  blocked: { text: 'WhatsApp: номер заблокирован WhatsApp', error: true },
  suspended: { text: 'WhatsApp: временные ограничения WhatsApp на номере', error: true },
  sleepMode: { text: 'WhatsApp: телефон выключен — сообщения придут, когда он появится в сети', error: true },
  starting: { text: 'WhatsApp: инстанс запускается', error: false },
  // WAHA (спека whatsapp-waha §7)
  SCAN_QR_CODE: { text: 'WhatsApp: номер не подключён — привяжите в «Система → WhatsApp»', error: true },
  FAILED: { text: 'WhatsApp: сессия WhatsApp упала — перезапустите в «Система → WhatsApp»', error: true },
  STOPPED: { text: 'WhatsApp: сессия остановлена — запустите в «Система → WhatsApp»', error: true },
  STARTING: { text: 'WhatsApp: подключается…', error: false },
  PASSKEY_REQUIRED: { text: 'WhatsApp: нужно подтверждение на рабочем телефоне', error: true },
  PASSKEY_CONFIRMATION_REQUIRED: { text: 'WhatsApp: нужно подтверждение на рабочем телефоне', error: true },
};

/** «Подключён»: у Green-API — authorized, у WAHA — WORKING. */
const CONNECTED = ['authorized', 'WORKING'];

function notConfigured(provider: string | null): string {
  return provider === 'greenapi' ? 'не заданы учётные данные Green-API' : 'не заданы ключи шлюза WAHA';
}

/** Одна строка: выключено → не настроено → ошибка → состояние → предупреждения → «подключён». */
export function whatsappStatusLine(s: WhatsappStatus | null): StatusLine | null {
  if (!s) return null;
  if (!s.enabled) return { text: 'WhatsApp: приём выключен', error: false };
  if (!s.configured) return { text: 'WhatsApp: ' + (s.lastError || notConfigured(s.provider)), error: true };
  if (s.lastError) return { text: 'WhatsApp: ' + s.lastError, error: true };
  if (!s.state) return { text: 'WhatsApp: подключение проверяется…', error: false };
  if (!CONNECTED.includes(s.state)) {
    return STATE_TEXT[s.state] || { text: 'WhatsApp: состояние шлюза — ' + s.state, error: true };
  }
  const warnings = (s.warnings || []).map(w => w === 'MESSAGE_DROPPED' ? droppedText(s.droppedCount) : WARNING_TEXT[w] || w);
  if (warnings.length) return { text: 'WhatsApp: ' + warnings.join('; '), error: true };
  const last = s.lastMessageAt ? ' · последнее сообщение ' + relativeTime(s.lastMessageAt) : '';
  return { text: 'WhatsApp' + (s.number ? ' ' + formatPhone(s.number) : '') + ' · подключён' + last, error: false };
}

/**
 * Чаты живут на рынке зеркала; на другом рынке список пуст, хотя строка говорит «подключён». Ловушка «не видно
 * данных»: новый браузер и иконка iPhone открываются на РФ (CLAUDE.md §14).
 */
export function marketHint(s: WhatsappStatus | null, current: string): string | null {
  if (!s?.enabled || !s.market || s.market === current) return null;
  return `Чаты WhatsApp ведутся на рынке ${MARKET_LABEL[s.market] || s.market}, а сейчас выбран `
    + `${MARKET_LABEL[current] || current} — переключите рынок слева вверху.`;
}

/** «77000000001» / «+77000000001» → «+7 700 000 00 01». */
export function formatPhone(raw: string): string {
  const d = (raw || '').replace(/\D/g, '');
  if (d.length === 11) return `+${d[0]} ${d.slice(1, 4)} ${d.slice(4, 7)} ${d.slice(7, 9)} ${d.slice(9)}`;
  return d ? '+' + d : '';
}
```

- [ ] **Step 3: Звонок — служебная строка в переписке и ленте обращения**

В `frontend/src/app/shared/chat-message.component.ts`:
1. В шаблоне строку `<div class="row" [class.out]="m.direction === 'OUT'">` заменить на

```html
    <div class="call" *ngIf="m.type === 'CALL'">
      <span class="call-line">{{ m.body }} · <time [attr.title]="full(m.sentAt)">{{ time(m.sentAt) }}</time></span>
    </div>
    <div class="row" *ngIf="m.type !== 'CALL'" [class.out]="m.direction === 'OUT'">
```

2. В `styles` перед блоком `@media (max-width: 900px)` добавить:

```css
    /* звонок — служебная строка по центру, не пузырь (спека whatsapp-waha §8) */
    .call { display: flex; justify-content: center; margin: 6px 0; }
    .call-line { font-size: 12px; color: var(--text-muted); background: color-mix(in srgb, var(--text-muted) 15%, transparent);
                 border-radius: 999px; padding: 4px 12px; text-align: center; }
```

- [ ] **Step 4: Страница «Система → WhatsApp»**

Создать `frontend/src/app/pages/whatsapp/whatsapp.component.ts`:

```ts
import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { NgIf } from '@angular/common';
import { Observable, Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { WhatsappStatusLineComponent } from '../../shared/whatsapp-status-line.component';
import { formatPhone, WhatsappStatus } from '../../shared/whatsapp-status';
import { fullDateTime, relativeTime } from '../../shared/relative-time';

type Tone = 'ok' | 'wait' | 'bad';

/** Статус сессии по-русски (спека whatsapp-waha §7); у Green-API — его состояния. */
const STATUS_LABEL: Record<string, { text: string; tone: Tone }> = {
  WORKING: { text: 'подключён', tone: 'ok' },
  SCAN_QR_CODE: { text: 'ждёт привязки', tone: 'wait' },
  STARTING: { text: 'подключается', tone: 'wait' },
  PASSKEY_REQUIRED: { text: 'нужно подтверждение на телефоне', tone: 'wait' },
  PASSKEY_CONFIRMATION_REQUIRED: { text: 'нужно подтверждение на телефоне', tone: 'wait' },
  FAILED: { text: 'сессия упала', tone: 'bad' },
  STOPPED: { text: 'остановлена', tone: 'bad' },
  authorized: { text: 'подключён', tone: 'ok' },
  notAuthorized: { text: 'номер не подключён', tone: 'bad' },
  blocked: { text: 'номер заблокирован', tone: 'bad' },
  sleepMode: { text: 'телефон выключен', tone: 'wait' },
  starting: { text: 'запускается', tone: 'wait' },
  suspended: { text: 'ограничения WhatsApp', tone: 'bad' },
};

/**
 * «Система → WhatsApp» (спека whatsapp-waha §7): шлюз, состояние, номер; у WAHA — QR для привязки (код живёт 20–60 с,
 * страница обновляет его сама), «Перезапустить» и «Отвязать номер» с подтверждением в самой странице.
 * Только администратор: QR — это доступ к рабочему WhatsApp.
 */
@Component({
  selector: 'app-whatsapp',
  standalone: true,
  imports: [NgIf, WhatsappStatusLineComponent],
  template: `
    <div class="page-head">
      <div>
        <h2>WhatsApp</h2>
        <p class="subtitle">Рабочий номер, переписка которого приходит в «Чаты» и «Обращения».</p>
      </div>
    </div>

    <div class="error-banner" *ngIf="error">{{ error }}</div>

    <section class="card" *ngIf="info">
      <dl class="facts">
        <div><dt>Шлюз</dt><dd>{{ providerLabel(info.provider) }}</dd></div>
        <div><dt>Состояние</dt><dd><span class="badge" [attr.data-tone]="label(info.status).tone">{{ label(info.status).text }}</span></dd></div>
        <div *ngIf="info.number"><dt>Номер</dt><dd>{{ phone(info.number) }}<span class="muted" *ngIf="info.name"> · {{ info.name }}</span></dd></div>
        <div *ngIf="status?.lastMessageAt as at"><dt>Последнее сообщение</dt><dd [title]="full(at)">{{ ago(at) }}</dd></div>
      </dl>
      <app-whatsapp-status-line [status]="status"></app-whatsapp-status-line>
      <p class="muted" *ngIf="!info.enabled">Приём WhatsApp выключен (WHATSAPP_ENABLED=false в настройках сервера).</p>
      <p class="muted" *ngIf="info.enabled && info.provider === 'greenapi'">Привязка номера и состояние инстанса — в кабинете Green-API.</p>
      <p class="field-error" *ngIf="info.error">Шлюз не ответил: {{ info.error }}</p>
    </section>

    <section class="card" *ngIf="info?.qrAvailable">
      <h3>Привязать номер</h3>
      <div class="qr-body">
        <div class="qr">
          <img *ngIf="qrUrl" [src]="qrUrl" alt="QR-код для привязки WhatsApp" width="240" height="240" />
          <span *ngIf="!qrUrl" class="muted">QR-код загружается…</span>
        </div>
        <ol class="steps">
          <li>Откройте WhatsApp на <b>рабочем</b> телефоне.</li>
          <li>Настройки → Связанные устройства → Привязка устройства.</li>
          <li>Наведите камеру на код. Код меняется каждые 20 секунд — страница обновляет его сама.</li>
        </ol>
      </div>
    </section>

    <section class="card" *ngIf="canRestart || canLogout">
      <h3>Управление</h3>
      <div class="actions" *ngIf="!confirmLogout">
        <button type="button" class="btn btn-primary" *ngIf="canRestart" [disabled]="busy" (click)="restart()">Перезапустить</button>
        <button type="button" class="btn btn-line" *ngIf="canLogout" [disabled]="busy" (click)="confirmLogout = true">Отвязать номер</button>
      </div>
      <div class="confirm" *ngIf="confirmLogout">
        <span>Сообщения перестанут приходить в АИС, пока номер не привяжут заново. Отвязать?</span>
        <button type="button" class="btn btn-danger" [disabled]="busy" (click)="logout()">Отвязать</button>
        <button type="button" class="btn btn-cancel" (click)="confirmLogout = false">Отмена</button>
      </div>
    </section>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .card { margin-top: 16px; padding: 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
    .card h3 { margin: 0 0 12px; font-size: 16px; }
    .facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px 24px; margin: 0 0 12px; }
    .facts dt { font-size: 12px; color: var(--text-muted); margin-bottom: 2px; }
    .facts dd { margin: 0; font-size: 15px; color: var(--text); }
    .muted { color: var(--text-muted); font-size: 13px; }
    .badge[data-tone="ok"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .badge[data-tone="wait"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .badge[data-tone="bad"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .qr-body { display: flex; gap: 24px; align-items: flex-start; flex-wrap: wrap; }
    /* своя белая подложка у PNG самого кода: светлый фон нужен камере в любой теме */
    .qr { width: 240px; height: 240px; display: flex; align-items: center; justify-content: center;
          border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
    .qr img { width: 240px; height: 240px; image-rendering: pixelated; }
    .steps { margin: 0; padding-left: 20px; line-height: 1.7; color: var(--text); max-width: 420px; }
    .actions, .confirm { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .confirm span { font-size: 13px; color: var(--danger-text); }
    @media (max-width: 900px) {
      .facts { grid-template-columns: 1fr; }
      .qr-body { flex-direction: column; align-items: stretch; }
      .qr { align-self: center; }
      .actions .btn, .confirm .btn { flex: 1 1 auto; }
    }
  `],
})
export class WhatsappComponent implements OnInit, OnDestroy {
  info: any = null;
  status: WhatsappStatus | null = null;
  qrUrl: string | null = null;
  error = '';
  busy = false;
  confirmLogout = false;
  private timer: any = null;
  private qrSub?: Subscription;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.load();
    this.timer = setInterval(() => this.load(true), 5000);   // код живёт 20 с; после привязки состояние сменится сразу
  }

  /** Запрос QR отменяется вместе с видом: иначе ответ создал бы blob-адрес, который уже некому отозвать. */
  ngOnDestroy() {
    clearInterval(this.timer);
    this.qrSub?.unsubscribe();
    this.dropQr();
  }

  get canManage(): boolean { return !!this.info && this.info.provider === 'waha' && this.info.enabled && this.info.configured; }
  get canRestart(): boolean { return this.canManage && ['FAILED', 'STOPPED'].includes(this.info.status); }
  get canLogout(): boolean { return this.canManage && !!this.info.status && this.info.status !== 'SCAN_QR_CODE'; }

  load(quiet = false) {
    this.api.getWhatsappSession().subscribe({
      next: s => {
        this.info = s;
        this.error = '';
        if (s.qrAvailable) {
          this.loadQr();
        } else {
          this.qrSub?.unsubscribe();
          this.dropQr();
        }
        this.cdr.detectChanges();
      },
      error: e => {
        if (!quiet) {
          this.error = e.error?.message || 'Не удалось получить состояние WhatsApp';
          this.cdr.detectChanges();
        }
      },
    });
    this.api.getWhatsappStatus().subscribe({ next: s => { this.status = s; this.cdr.detectChanges(); }, error: () => {} });
  }

  private loadQr() {
    this.qrSub?.unsubscribe();
    this.qrSub = this.api.getWhatsappQr().subscribe({
      next: blob => { this.dropQr(); this.qrUrl = URL.createObjectURL(blob); this.cdr.detectChanges(); },
      error: () => {},   // код сменился или номер уже привязан — следующий опрос покажет новое состояние
    });
  }

  private dropQr() {
    if (this.qrUrl) {
      URL.revokeObjectURL(this.qrUrl);
      this.qrUrl = null;
    }
  }

  restart() { this.act(this.api.restartWhatsappSession(), 'Сессия перезапускается — состояние обновится через несколько секунд'); }

  logout() {
    this.confirmLogout = false;
    this.act(this.api.logoutWhatsappSession(), 'Номер отвязан — привяжите заново по QR-коду');
  }

  private act(obs: Observable<any>, ok: string) {
    this.busy = true;
    obs.subscribe({
      next: s => { this.busy = false; this.info = s; this.notify.success(ok); this.load(true); },
      error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не удалось выполнить действие'); this.load(true); },
    });
  }

  label(status: string | null): { text: string; tone: Tone } {
    if (!status) return { text: this.info?.enabled ? 'неизвестно' : 'приём выключен', tone: 'wait' };
    return STATUS_LABEL[status] || { text: status, tone: 'bad' };
  }

  providerLabel(p: string | null): string {
    if (p === 'waha') return 'WAHA (свой шлюз на сервере)';
    if (p === 'greenapi') return 'Green-API (запасной)';
    return p || '—';
  }

  phone(n: string) { return formatPhone(n); }
  ago(iso: string) { return relativeTime(iso); }
  full(iso: string) { return fullDateTime(iso); }
}
```

- [ ] **Step 5: Маршрут, меню, иконка**

`frontend/src/app/app.routes.ts`: импорт `import { WhatsappComponent } from './pages/whatsapp/whatsapp.component';` и маршрут после `devices`:

```ts
      { path: 'whatsapp', component: WhatsappComponent, canActivate: [adminGuard] },
```

`frontend/src/app/app.config.ts`: в оба списка (импорт из `@lucide/angular` и `provideLucideIcons(...)`) после `LucideMessagesSquare` добавить `LucideQrCode`.

`frontend/src/app/layout/layout.component.ts`: в группе «Система» сразу после ссылки «Устройства» (закрывающий `</a>` после `nav-count`) вставить:

```html
            <a *ngIf="auth.isAdmin()" routerLink="/whatsapp" routerLinkActive="active">
              <svg lucideIcon="qr-code" [size]="16"></svg> WhatsApp
            </a>
```

- [ ] **Step 6: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка без ошибок (предупреждение о начальном бандле > 800 кБ было и раньше — §16 CLAUDE.md). `grep -rn "#[0-9a-fA-F]\{3,6\}\b" src/app/pages/whatsapp/whatsapp.component.ts` — пусто (ни одного хекса).

- [ ] **Step 7: Живая проверка в браузере (стаб WAHA)**

Поднять стаб, бэкенд (команды задачи 10, шаг 3) и `cd frontend && npm start`. Браузер — Playwright MCP, а если он недоступен — локальный Playwright (`node <scratchpad>/pw.mjs`, `channel: 'chrome'`, модуль из `~/.npm/_npx/9833c18b2d85bc59/node_modules/`). Для замеров — `http://[::1]:4200` (у профиля MCP масштаб 110% на localhost); перед мобильным замером проверять `window.innerWidth` (навигация сбрасывает вьюпорт на 1280, §12). Логин admin/admin, `localStorage['ais.market'] = 'KZ'`.

Проверить и снять скриншоты (в scratchpad), 1280 и 390, светлая и тёмная темы:
1. Меню «Система» → пункт «WhatsApp» с иконкой (у `svg` есть дочерние элементы — `svg.children.length > 0`, §14). Под operator/operator пункта нет, `/whatsapp` уводит guard'ом.
2. Сессия ждёт привязки: состояние «ждёт привязки» (жёлтый бейдж), картинка QR из стаба, инструкция; в сети — запрос `/api/whatsapp/session/qr` раз в ~5 с; на «Чатах» строка «номер не подключён — привяжите в «Система → WhatsApp»» (красная).
3. `curl -X POST localhost:7708/__link` → за ≤5 с «подключён» (зелёный), номер «+7 700 000 00 01 · West-Med (стаб)», QR исчез, есть «Отвязать номер»; на «Чатах» — «WhatsApp +7 700 000 00 01 · подключён».
4. «Отвязать номер» → в странице подтверждение с текстом про остановку сообщений → «Отмена» закрывает; «Отвязать» → тост, снова «ждёт привязки» и QR.
5. `curl -X POST localhost:7708/__status/FAILED` → «сессия упала» (красный), кнопка «Перезапустить» → тост, состояние меняется.
6. После `/__link` и `/__scenario/basic`: на «Чатах» у чата Сауле строка по центру «📞 Входящий звонок — принят · ЧЧ:ММ», не пузырь; у Айгерим «📹 Входящий видеозвонок — отклонён»; в «Обращениях» — обращение «Звонок в WhatsApp» (Сауле), в его карточке в ленте та же служебная строка.
7. На 390px: карточки в колонку, QR по центру, кнопки на всю ширину, горизонтальной прокрутки нет (`document.documentElement.scrollWidth <= innerWidth`), тач-таргеты ≥ 40px.
8. Тёмная тема: контраст бейджей и строки звонка читаемый (текстовые токены `--*-text`), QR на своей белой подложке.

Остановить фронт, бэкенд и стаб (только слушающие процессы).

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/whatsapp frontend/src/app/services/api.service.ts \
  frontend/src/app/shared/whatsapp-status.ts frontend/src/app/shared/chat-message.component.ts \
  frontend/src/app/app.routes.ts frontend/src/app/app.config.ts frontend/src/app/layout/layout.component.ts
git commit -m "feat(whatsapp): «Система → WhatsApp» — привязка по QR, перезапуск, отвязка; тексты WAHA в строке; звонок — служебной строкой

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: WAHA в `docker-compose.yml` под профилем и `.env.example`

**Files:**
- Modify: `docker-compose.yml`, `.env.example`

**Interfaces:**
- Consumes: настройки `chats.whatsapp.*` (задачи 1, 4).
- Produces: сервис `ais-waha` (образ `devlikeapro/waha:gows-2026.9.1`, профиль `whatsapp`, только сеть `ais-net`, тома `ais-waha-sessions`, `ais-waha-media`); обычный деплой без `COMPOSE_PROFILES=whatsapp` WAHA не запускает.

- [ ] **Step 1: Сервис WAHA**

В `docker-compose.yml`:
1. Во вводный комментарий (строки 1–3) дописать строку: `# WhatsApp: шлюз ais-waha (WAHA) — только во внутренней сети, запускается профилем whatsapp (COMPOSE_PROFILES в .env).`
2. После сервиса `ais-frontend` (перед `networks:`) вставить:

```yaml
  # Шлюз WhatsApp (спека docs/superpowers/specs/2026-09-28-whatsapp-waha-design.md §11). Наружу НЕ публикуется:
  # вебхук идёт ais-waha → ais-backend:8080 по ais-net, API WAHA зовёт только бэкенд. Панель и Swagger выключены.
  ais-waha:
    image: devlikeapro/waha:gows-2026.9.1
    profiles: ["whatsapp"]               # обычный деплой WAHA не запускает, пока в .env нет COMPOSE_PROFILES=whatsapp
    restart: unless-stopped
    mem_limit: 700m                      # WAHA держит скачиваемый файл целиком в памяти; ~200 МБ в покое
    environment:
      WHATSAPP_DEFAULT_ENGINE: GOWS
      WAHA_API_KEY: ${WHATSAPP_WAHA_API_KEY}          # без него WAHA генерирует ключ сама и печатает в лог
      WAHA_DASHBOARD_ENABLED: "false"
      WHATSAPP_SWAGGER_ENABLED: "false"
      WAHA_PRINT_QR: "False"
      WAHA_PRESENCE_AUTO_ONLINE: "False"              # иначе телефон перестаёт получать push (активное «устройство»)
      WAHA_BASE_URL: http://ais-waha:3000             # из него WAHA собирает media.url — бэкенд качает только отсюда
      WHATSAPP_HOOK_URL: http://ais-backend:8080/api/whatsapp/waha/webhook
      WHATSAPP_HOOK_EVENTS: message.any,message.edited,message.revoked,session.status,call.received,call.accepted,call.rejected
      WHATSAPP_HOOK_HMAC_KEY: ${WHATSAPP_WAHA_HMAC_KEY}
      WHATSAPP_HOOK_RETRIES_POLICY: exponential
      WHATSAPP_HOOK_RETRIES_DELAY_SECONDS: "2"
      WHATSAPP_HOOK_RETRIES_ATTEMPTS: "12"
      WAHA_EVENTS_DOWNLOAD_MEDIA: "false"            # файлы — по требованию АИС (спека §6)
      WHATSAPP_FILES_FOLDER: /app/.media
      WHATSAPP_FILES_LIFETIME: "600"
      WAHA_GOWS_DEVICE_HISTORY_SYNC_FULL_SYNC_DAYS_LIMIT: "7"
      WAHA_LOG_FORMAT: JSON
    volumes:
      - ais-waha-sessions:/app/.sessions   # ⚠️ = доступ к рабочему WhatsApp: не выкладывать, в бэкапы — только осознанно
      - ais-waha-media:/app/.media
    networks: [ais-net]
```

3. В `volumes:` после `ais-pgdata` добавить:

```yaml
  ais-waha-sessions:
    name: ais-waha-sessions
  ais-waha-media:
    name: ais-waha-media
```

- [ ] **Step 2: `.env.example`**

Блок «Чаты WhatsApp через Green-API» (от комментария `# --- Чаты WhatsApp через Green-API` до строки `WHATSAPP_MAX_FILE_MB=25`) заменить на:

```bash
# --- Чаты WhatsApp (зеркало переписки рабочего номера; DEPLOY.md §7) ---
WHATSAPP_ENABLED=false
WHATSAPP_PROVIDER=waha            # waha — свой шлюз в Docker (по умолчанию); greenapi — запасной
WHATSAPP_MAX_FILE_MB=25
# WAHA: оба ключа — `openssl rand -hex 32` прямо на сервере, в чат не печатать.
# COMPOSE_PROFILES=whatsapp запускает контейнер ais-waha (без этой строки обычный деплой WAHA не поднимает).
WHATSAPP_WAHA_API_KEY=CHANGE_ME_openssl_rand_hex_32
WHATSAPP_WAHA_HMAC_KEY=CHANGE_ME_openssl_rand_hex_32
#COMPOSE_PROFILES=whatsapp
# Green-API (запасной, WHATSAPP_PROVIDER=greenapi): инстанс Business, адрес вебхука в кабинете — ПУСТОЙ.
# Токен стоит в пути URL запросов — нигде не печатать.
WHATSAPP_API_URL=CHANGE_ME_apiUrl_from_green_api_cabinet   # вида https://7105.api.greenapi.com
WHATSAPP_ID_INSTANCE=CHANGE_ME_idInstance
WHATSAPP_API_TOKEN=CHANGE_ME_apiTokenInstance
```

- [ ] **Step 3: Профиль работает так, как задумано**

```bash
cd /Users/vlad/IdeaProjects/AIS
S=/private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/c947d9ac-6874-4d66-8090-31f80e0e2615/scratchpad/compose-check
mkdir -p $S && cp docker-compose.yml $S/
printf 'POSTGRES_USER=x\nPOSTGRES_PASSWORD=y\nWHATSAPP_WAHA_API_KEY=k\nWHATSAPP_WAHA_HMAC_KEY=h\n' > $S/.env
docker compose --project-directory $S config --services | sort          # без ais-waha
echo 'COMPOSE_PROFILES=whatsapp' >> $S/.env
docker compose --project-directory $S config --services | sort          # + ais-waha (профиль читается из .env)
docker compose --project-directory $S config | grep -A3 'ais-waha:' | head -5
docker compose --project-directory $S config | grep -c 'ports:'         # 1 — только фронт; у ais-waha портов нет
```

(`dangerouslyDisableSandbox: true`; Docker Desktop не отвечает — `colima start --cpu 1 --memory 1`, `DOCKER_CONTEXT=colima`, CLAUDE.md §14.)
Expected: первый список — `ais-backend ais-frontend ais-postgres`; второй — с `ais-waha`; `ports:` ровно один.

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add docker-compose.yml .env.example
git commit -m "chore(deploy): шлюз WAHA в compose под профилем whatsapp — только внутренняя сеть, потолок памяти, ключи из .env

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Настоящая WAHA и тестовый номер — живая проверка и фикстуры (нужен оператор)

⚠️ Только **тестовый** номер WhatsApp (не рабочий): каждое связанное устройство получает всю переписку номера, и она поехала бы в локальную базу. Нужны тестовый телефон и второй телефон (писать на тестовый). Форма `_data` движка GOWS документацией не описана — эта задача закрепляет её фикстурами и чинит парсер, если живая форма расходится с `WahaJson`.

**Files:**
- Create: `src/test/resources/waha/*.json` (фикстуры без персональных данных), `src/test/java/com/vladoose/nir/integration/waha/WahaLiveFixturesTest.java`
- Modify (только если живая форма расходится): `integration/waha/WahaEventParser.java`, `src/test/java/com/vladoose/nir/integration/waha/WahaJson.java`

- [ ] **Step 1: Ключи и WAHA локально**

```bash
umask 077; mkdir -p ~/.config/ais
[ -s ~/.config/ais/waha-dev.key ] || openssl rand -hex 32 > ~/.config/ais/waha-dev.key
[ -s ~/.config/ais/waha-dev.hmac ] || openssl rand -hex 32 > ~/.config/ais/waha-dev.hmac
colima start --cpu 2 --memory 3
export DOCKER_CONTEXT=colima
docker pull devlikeapro/waha:gows-2026.9.1
docker run -d --name ais-waha-dev -p 127.0.0.1:3000:3000 \
  -e WHATSAPP_DEFAULT_ENGINE=GOWS -e WAHA_API_KEY="$(cat ~/.config/ais/waha-dev.key)" \
  -e WAHA_DASHBOARD_ENABLED=false -e WHATSAPP_SWAGGER_ENABLED=false -e WAHA_PRINT_QR=False -e WAHA_PRESENCE_AUTO_ONLINE=False \
  -e WAHA_BASE_URL=http://localhost:3000 \
  -e WHATSAPP_HOOK_URL=http://host.lima.internal:8080/api/whatsapp/waha/webhook \
  -e WHATSAPP_HOOK_EVENTS=message.any,message.edited,message.revoked,session.status,call.received,call.accepted,call.rejected \
  -e WHATSAPP_HOOK_HMAC_KEY="$(cat ~/.config/ais/waha-dev.hmac)" \
  -e WHATSAPP_HOOK_RETRIES_POLICY=exponential -e WHATSAPP_HOOK_RETRIES_DELAY_SECONDS=2 -e WHATSAPP_HOOK_RETRIES_ATTEMPTS=12 \
  -e WAHA_EVENTS_DOWNLOAD_MEDIA=false -e WHATSAPP_FILES_LIFETIME=600 -e WAHA_GOWS_DEVICE_HISTORY_SYNC_FULL_SYNC_DAYS_LIMIT=7 \
  -v waha-dev-sessions:/app/.sessions devlikeapro/waha:gows-2026.9.1
curl -s localhost:3000/ping                                                         # {"message":"pong"}
curl -s -o /dev/null -w '%{http_code}\n' localhost:3000/api/sessions               # 401 — без ключа не пускает
```

`WAHA_BASE_URL=http://localhost:3000` — чтобы `media.url` совпал с `WHATSAPP_WAHA_URL` бэкенда (он качает файл только с адреса самой WAHA). Если `host.lima.internal` из контейнера не резолвится (проверка: `docker exec ais-waha-dev node -e "fetch('http://host.lima.internal:8080/api/auth/passkey-config').then(r=>console.log(r.status))"` после старта бэкенда → `200`), пересоздать контейнер с `--add-host host.lima.internal:$(colima ssh -- ip route | awk '/default/ {print $3}')`.

- [ ] **Step 2: Бэкенд и фронт против настоящей WAHA**

```bash
cd /Users/vlad/IdeaProjects/AIS && WHATSAPP_ENABLED=true WHATSAPP_PROVIDER=waha WHATSAPP_WAHA_URL=http://localhost:3000 \
  WHATSAPP_WAHA_API_KEY="$(cat ~/.config/ais/waha-dev.key)" WHATSAPP_WAHA_HMAC_KEY="$(cat ~/.config/ais/waha-dev.hmac)" \
  WHATSAPP_INITIAL_DELAY_MS=3000 JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew bootRun
cd /Users/vlad/IdeaProjects/AIS/frontend && npm start
```

(оба — фоном, `dangerouslyDisableSandbox: true`). Через ≤1 мин в логе бэкенда нет ошибок WhatsApp; `docker logs ais-waha-dev | tail` — сессия `westmed` создана.

- [ ] **Step 3: Привязка и сценарий (оператор)**

Оператор: `http://localhost:4200` → admin → «Система → WhatsApp» → QR **тестовым** телефоном. Дальше:
1. со второго телефона на тестовый — текст, фото, PDF, Excel, стикер, геоточка, контакт; правка текста; удаление;
2. с тестового — ответ второму телефону и ещё 1–2 людям, **в т.ч. не из контактов** (issue #2241: ответ лёг в правильный чат);
3. со второго телефона — сначала голосовой звонок (принять), потом видеозвонок (отклонить) — именно в этом порядке: фикстуры берут первое событие каждого вида;
4. группа с тестовым номером — сообщение и фото;
5. **push на тестовом телефоне приходят** всё время, пока WAHA привязана (`WAHA_PRESENCE_AUTO_ONLINE=False`).

Агент сверяет: «Чаты» — каждый пункт на месте, правильные направления и чаты; очередь — `select event, status, count(*) from whatsapp_inbox group by 1,2` (ничего не висит в `PENDING`, `DROPPED` нет).

- [ ] **Step 4: Снять фикстуры без персональных данных**

```bash
S=/private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/c947d9ac-6874-4d66-8090-31f80e0e2615/scratchpad
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -At -c \
  "select json_build_object('id', id, 'event', event, 'payload', payload::jsonb) from whatsapp_inbox order by id" > $S/waha-live.jsonl
cat > $S/scrub.mjs <<'EOF'
// Фикстура без персональных данных: номера → 7700000000N, имена → «Клиент N», тексты сохраняются только наши тестовые,
// криптография и миниатюры медиа — вон. Форма (ключи, вложенность, типы значений) — как пришла.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
const [, , src, outDir] = process.argv;
const phones = new Map(); const names = new Map();
const phone = (d) => { if (!phones.has(d)) phones.set(d, '770000000' + String(phones.size + 10).padStart(2, '0')); return phones.get(d); };
const name = (n) => { if (!names.has(n)) names.set(n, 'Клиент ' + (names.size + 1)); return names.get(n); };
const DROP = new Set(['JPEGThumbnail', 'jpegThumbnail', 'mediaKey', 'fileSHA256', 'fileEncSHA256', 'directPath', 'URL', 'url', 'thumbnailSHA256', 'thumbnailEncSHA256', 'thumbnailDirectPath', 'waveform', 'streamingSidecar', 'mediaKeyTimestamp']);
const NAME_KEYS = new Set(['PushName', 'pushName', 'notifyName', 'displayName', 'VerifiedName', 'name']);
function scrub(v, key) {
  if (Array.isArray(v)) return v.map((x) => scrub(x, key));
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).filter(([k]) => !DROP.has(k)).map(([k, x]) => [k, scrub(x, k)]));
  if (typeof v === 'string') {
    if (NAME_KEYS.has(key) && v.trim()) return name(v);
    return v.replace(/\d{10,15}(?=[@:.]|$)/g, (d) => phone(d));
  }
  return v;
}
mkdirSync(outDir, { recursive: true });
const seen = new Map();
for (const line of readFileSync(src, 'utf8').split('\n').filter(Boolean)) {
  const row = JSON.parse(line);
  const env = scrub(row.payload);
  const any = row.event === 'message.any';
  const c = any && env.payload?._data?.Message ? Object.keys(env.payload._data.Message).find((k) => k !== 'messageContextInfo') : null;
  const kind = row.event + (c ? '.' + c : '') + (any && env.payload?.fromMe ? '.fromMe' : '');
  if (seen.has(kind)) continue;
  seen.set(kind, true);
  writeFileSync(`${outDir}/${kind}.json`, JSON.stringify(env, null, 2) + '\n');
}
console.log([...seen.keys()].join('\n'));
EOF
node $S/scrub.mjs $S/waha-live.jsonl /Users/vlad/IdeaProjects/AIS/src/test/resources/waha
grep -rlE '7[0-9]{10}' /Users/vlad/IdeaProjects/AIS/src/test/resources/waha | xargs -r grep -hoE '7[0-9]{10}' | sort -u   # только 77000000010…
```

Агент просматривает КАЖДЫЙ файл глазами: нет реальных номеров, имён, текстов личной переписки (тексты сценария шага 3 писал оператор как тестовые — если есть сомнение, заменить текст на «тест»).

- [ ] **Step 5: Тест на живых формах (сначала красный, если форма расходится)**

Создать `src/test/java/com/vladoose/nir/integration/waha/WahaLiveFixturesTest.java`. Имена файлов — из вывода шага 4; ниже — ожидаемые (`message.any.conversation.json`, `message.any.documentMessage.json`, `message.any.imageMessage.json`, `message.any.conversation.fromMe.json`, `message.edited.json`, `message.revoked.json`, `session.status.json`, `call.received.json`, `call.accepted.json`, `call.rejected.json`). Если вид назван иначе (`extendedTextMessage` вместо `conversation`, `documentWithCaptionMessage` вместо `documentMessage`) — подставить фактическое имя: это тоже знание о живой форме, его и закрепляем.

```java
package com.vladoose.nir.integration.waha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.entity.ChatMessageType;
import com.vladoose.nir.entity.LeadDirection;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * События, снятые с живой WAHA 2026.9.1 (GOWS) и очищенные от персональных данных (задача 13 плана). Закрепляет то,
 * чего нет в документации WAHA: где у GOWS лежат PushName, размер файла, данные звонка.
 */
class WahaLiveFixturesTest {

    static final ObjectMapper M = new ObjectMapper();

    static JsonNode fixture(String name) throws IOException {
        try (InputStream in = WahaLiveFixturesTest.class.getResourceAsStream("/waha/" + name)) {
            assertThat(in).as("фикстура " + name).isNotNull();
            return M.readTree(in);
        }
    }

    static ParsedNotification.Message msg(String name) throws IOException {
        ParsedNotification p = WahaEventParser.parse(fixture(name), null);
        assertThat(p).as(name).isInstanceOf(ParsedNotification.Message.class);
        return (ParsedNotification.Message) p;
    }

    @Test
    void incomingText() throws IOException {
        ParsedNotification.Message m = msg("message.any.conversation.json");
        assertThat(m.direction()).isEqualTo(LeadDirection.IN);
        assertThat(m.type()).isEqualTo(ChatMessageType.TEXT);
        assertThat(m.body()).isNotBlank();
        assertThat(m.chatName()).as("PushName из _data").isNotBlank();
        assertThat(m.phone()).startsWith("+7700000000");
    }

    @Test
    void phoneReplyIsOutgoing() throws IOException {
        ParsedNotification.Message m = msg("message.any.conversation.fromMe.json");
        assertThat(m.direction()).isEqualTo(LeadDirection.OUT);
        assertThat(m.viaApi()).isFalse();
    }

    /** Главное, ради чего задача: где GOWS сообщает размер файла (иначе проверка «больше 25 МБ — не качать» молчит). */
    @Test
    void documentCarriesSizeNameAndType() throws IOException {
        ParsedNotification.Message m = msg("message.any.documentMessage.json");
        assertThat(m.type()).isEqualTo(ChatMessageType.DOCUMENT);
        assertThat(m.file()).isNotNull();
        assertThat(m.file().sizeBytes()).as("размер файла из события").isPositive();
        assertThat(m.file().fileName()).isNotBlank();
        assertThat(m.file().mimeType()).isNotBlank();
    }

    @Test
    void imageIsImageWithSize() throws IOException {
        ParsedNotification.Message m = msg("message.any.imageMessage.json");
        assertThat(m.type()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(m.file().sizeBytes()).isPositive();
    }

    @Test
    void editPointsToOriginal() throws IOException {
        ParsedNotification.Message m = msg("message.edited.json");
        assertThat(m.isEdit()).isTrue();
        assertThat(m.editOf()).isNotBlank().doesNotContain("_");
    }

    @Test
    void revokeIsDelete() throws IOException {
        assertThat(WahaEventParser.parse(fixture("message.revoked.json"), null)).isInstanceOf(ParsedNotification.Delete.class);
    }

    @Test
    void sessionStatusIsState() throws IOException {
        assertThat(WahaEventParser.parse(fixture("session.status.json"), null)).isInstanceOf(ParsedNotification.State.class);
    }

    @Test
    void callsAreCallLines() throws IOException {
        ParsedNotification.Message received = msg("call.received.json");
        ParsedNotification.Message accepted = msg("call.accepted.json");
        assertThat(received.type()).isEqualTo(ChatMessageType.CALL);
        assertThat(received.body()).startsWith("📞").doesNotContain("—");
        assertThat(accepted.editOf()).isEqualTo(received.idMessage());
        assertThat(accepted.body()).endsWith("— принят");
        assertThat(msg("call.rejected.json").body()).endsWith("— отклонён");
    }
}
```

Run: `./gradlew cleanTest test --tests 'com.vladoose.nir.integration.waha.WahaLiveFixturesTest'`
Если FAIL — это и есть расхождение живой формы с документацией: смотреть фикстуру, править `WahaEventParser` (и `WahaJson`, чтобы синтетические тесты описывали ту же форму), прогонять `--tests 'com.vladoose.nir.integration.waha.*'` до PASS. Каждое расхождение — одной строкой в сообщение коммита и в §14 CLAUDE.md (задача 14).

- [ ] **Step 6: Догонка вживую**

Остановить бэкенд (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`); со второго телефона — два сообщения на тестовый; запустить бэкенд снова. Expected: за ≤1 мин оба сообщения в «Чатах», в очереди — события с `"origin":"catch-up"` (или вебхуки, повторённые WAHA, — оба пути годятся, дублей нет).

- [ ] **Step 7: Уборка и commit**

WAHA-контейнер остановить (`docker rm -f ais-waha-dev`; том `waha-dev-sessions` оставить — пригодится для следующей разработки, или `docker volume rm waha-dev-sessions` и отвязать устройство на тестовом телефоне). Чаты тестового номера в dev-базе — оставить (это dev).

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/test/resources/waha src/test/java/com/vladoose/nir/integration/waha \
  src/main/java/com/vladoose/nir/integration/waha
git commit -m "test(waha): фикстуры живой WAHA 2026.9.1 (GOWS) — размер файла, звонки, правки; парсер сверен с живой формой

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: Документация и финальный гейт

**Files:**
- Modify: `CLAUDE.md`, `DEPLOY.md`, `docs/PROGRESS.md`; память `~/.claude/projects/-Users-vlad-IdeaProjects-AIS/memory/ais-prod-deploy-oblako.md`, `last-task-pointer.md`

- [ ] **Step 1: CLAUDE.md**

1. §5, подраздел «WhatsApp (Green-API) — локально» → переименовать в «WhatsApp — локально» и переписать:
   - по умолчанию провайдер `waha` (`WHATSAPP_PROVIDER`), приём выключен;
   - стаб WAHA: `node scripts/waha-stub.mjs` + команда бэкенда из шапки стаба; `POST /__link` — «привязка», `/__scenario/basic`, `/__scenario/offline` + `/__status/WORKING` — догонка;
   - настоящая WAHA: команда `docker run` задачи 13 (colima, `host.lima.internal`, `WAHA_BASE_URL=http://localhost:3000`), ключи — `~/.config/ais/waha-dev.{key,hmac}`; **только тестовый номер**;
   - стаб Green-API теперь требует `WHATSAPP_PROVIDER=greenapi`.
2. §8, блок «Чаты WhatsApp…» — дописать подпункт «**Шлюз WAHA (2026-09-28, ветка `feature/whatsapp-waha`)**»: зачем (Green-API Business платный, бесплатно — 3 чата; Meta — «муторно»); цепочка вебхук (HMAC) → `whatsapp_inbox` (V22, `ON CONFLICT`) → общий цикл (`WhatsappSource`: `GreenApiSource`/`WahaInboxSource`, выбор `WHATSAPP_PROVIDER`, опечатка — красная строка); «отлёживание» 3 с; догонка (старт, WORKING, 10 мин; отметка — самое позднее сообщение номера − 10 мин; первая привязка — без догонки); уборка (DONE 7 дн., DROPPED 30 дн.); файлы по требованию с проверкой размера до скачивания и только с адреса WAHA; имена из справочников WAHA с кешем; звонки — строка `CALL` + обращение «Звонок в WhatsApp»; «Система → WhatsApp» (QR, перезапуск, отвязка — только администратор); `WAHA_PRESENCE_AUTO_ONLINE=False` (push на телефон); известные ограничения WAHA (issue #2241, #1968, #1789); что показали фикстуры живой WAHA (задача 13).
3. §13 — новое число тестов после `./gradlew cleanTest test`.
4. §14 — уроки, реально найденные при реализации (минимум — если подтвердились): повторы вебхука WAHA живут только в её памяти → своя очередь в БД; HTTP-тесты с ключами — через системное свойство в `build.gradle`, не `@TestPropertySource`; `@Bean(destroyMethod = "")` у бина-селектора, возвращающего существующий компонент; расхождения живой формы GOWS с документацией (из задачи 13).
5. §15 — `/api/whatsapp/waha/webhook` (без входа, подпись HMAC; в nginx фронта — 404), `/api/whatsapp/session` (GET, `/qr`, POST `/restart`, `/logout` — ADMIN), `/api/chats/status` + `provider`.
6. §16 — пункт «Чаты WhatsApp»: провайдер по умолчанию — WAHA; хвосты этой работы; следующий блок «диктофон» (спека §14, п. 1) и прочее из спеки §14; `V22` занята → следующая свободная **V23**.

- [ ] **Step 2: DEPLOY.md §7 — заменить целиком**

```markdown
## 7. Чаты WhatsApp (свой шлюз WAHA; Green-API — запасной)
Зеркало переписки рабочего номера West-Med: спеки `docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md` (модель чатов, правила обращений) и `docs/superpowers/specs/2026-09-28-whatsapp-waha-design.md` (шлюз); механика — CLAUDE.md §8. WAHA — контейнер `ais-waha` в том же compose, только во внутренней сети: вебхук идёт `ais-waha → ais-backend:8080` мимо nginx, **наружу ничего не открывается** (в nginx фронта путь вебхука закрыт `404`, калитка не меняется). Код приезжает обычным деплоем (V22); WAHA запускается только профилем `whatsapp`.
- **Перед включением:** `free -h` и `docker stats --no-stream` (WAHA ≈200 МБ, потолок 700 МБ); `df -h` — файлы чатов хранятся в БД (до `WHATSAPP_MAX_FILE_MB`, по умолчанию 25 МБ на файл).
- **Включение** (агент — только после явного «да» оператора; копия `.env` до правки): в `/srv/ais/.env` — `WHATSAPP_ENABLED=true`, `WHATSAPP_PROVIDER=waha`, `WHATSAPP_WAHA_API_KEY` и `WHATSAPP_WAHA_HMAC_KEY` (каждый — `openssl rand -hex 32` прямо на сервере, значения в чат не печатать), `COMPOSE_PROFILES=whatsapp` → `cd /srv/ais && docker compose up -d` (поднимет `ais-waha` и пересоздаст бэкенд с новым `.env`; `restart` новый `.env` не перечитывает).
- **Привязка:** АИС сама создаёт сессию `westmed`; оператор → «Система → WhatsApp» → QR **рабочим** телефоном (WhatsApp → Настройки → Связанные устройства → Привязка устройства).
- **Приёмочный тест** (главное — issue #2241 WAHA: свои сообщения в чужом чате): с личного телефона — на рабочий; с рабочего — ответы 2–3 разным людям, в т.ч. не из контактов, → легли в правильные чаты; фото, PDF, Excel; правка, удаление; звонок → строка «📞 Входящий звонок — принят»; **push на рабочем телефоне по-прежнему приходят** (`WAHA_PRESENCE_AUTO_ONLINE=False`). То же — после каждого обновления образа.
- ⚠️ **Автоответы WhatsApp Business** на рабочем номере до приёмочного теста не включать: если WAHA пришлёт их как отправленные с телефона, каждое новое обращение сразу уйдёт «В работу».
- **Красная строка на «Чатах» — что делать:** «номер не подключён» — привязать в «Система → WhatsApp»; «сессия упала» — там же «Перезапустить»; «WAHA недоступен … принятые сообщения разбираются» — `docker compose ps ais-waha`, `docker compose logs --since 10m ais-waha`; «не заданы ключи шлюза WAHA» — ключи в `.env`; «WAHA отклонил ключ API» — ключ в `.env` разошёлся с запущенным контейнером → `docker compose up -d`; «база данных недоступна» — чинить БД: WAHA повторяет вебхуки до ~4,5 ч (в своей памяти; её рестарт их теряет — пропущенное подберёт догонка); «приём остановлен: за сутки не записались 3 сообщения» — поломка кода, `docker compose logs ais-backend`; «не удалось догнать сообщения…» — повтор сам через 10 мин.
- **Очередь:** `docker compose exec ais-postgres psql -U "$POSTGRES_USER" -d nirdb -c "select status, count(*) from whatsapp_inbox group by 1"` — `PENDING` не должен копиться, у `DROPPED` причина в `last_error`.
- **Откат:** `WHATSAPP_ENABLED=false` → `docker compose up -d`; устройство WAHA удалить в телефоне («Связанные устройства»). Убрать WAHA совсем — ещё строку `COMPOSE_PROFILES=whatsapp` из `.env` и `docker compose stop ais-waha`. История остаётся и читается на «Чатах».
- **Обновление WAHA** — только вручную: журнал изменений → новый тег в `docker-compose.yml` → деплой → приёмочный тест.
- ⚠️ Том `ais-waha-sessions` = доступ к рабочему WhatsApp: не выкладывать, в бэкапы — только осознанно.
- **Запасной провайдер Green-API:** `WHATSAPP_PROVIDER=greenapi` + `WHATSAPP_API_URL` / `WHATSAPP_ID_INSTANCE` / `WHATSAPP_API_TOKEN` (инстанс Business, адрес вебхука в кабинете — ПУСТОЙ; один инстанс — один потребитель очереди) → `docker compose up -d`. Дублей при переключении нет: id сообщения WhatsApp у обоих шлюзов один.
- **Номер на сайте westmed.kz** (отдельный репозиторий `~/IdeaProjects/westmed`, отдельный деплой): три места — `frontend/src/components/shared/WhatsAppButton.tsx` (`PHONE`), `frontend/src/components/shared/Footer.tsx`, `frontend/src/app/[locale]/(storefront)/contacts/page.tsx`.
```

- [ ] **Step 3: PROGRESS и память**

- `docs/PROGRESS.md`, раздел «▶ Последняя задача» — переписать: блок «WhatsApp через свой шлюз WAHA»; что сделано (задачи 1–13), где спека/план; в прод — не пушилось (или пушилось — по факту); приём на проде выключен до шага раскатки; что открыто (задача 15, хвосты); не запушено — список коммитов (`git log --oneline origin/main..main`, в т.ч. `df95ded`); кандидаты следующего блока — «диктофон».
- Память `ais-prod-deploy-oblako.md` — строку про чаты: «шлюз — WAHA в compose под профилем `whatsapp` (с …), Green-API — запасной; включение — DEPLOY.md §7». `last-task-pointer.md` — «сейчас: WAHA, раскатка ждёт оператора».

- [ ] **Step 4: Финальный гейт**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew cleanTest test` (`dangerouslyDisableSandbox: true`) и `cd frontend && npm run build`
Expected: 0 падений; сборка зелёная.

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md DEPLOY.md docs/PROGRESS.md
git commit -m "docs(whatsapp): шлюз WAHA — CLAUDE.md, DEPLOY.md §7, PROGRESS

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

Затем — финальное ревью всей ветки (SDD) и мерж по решению оператора (superpowers:finishing-a-development-branch).

---

### Task 15: Раскатка на прод (оператор)

По спеке §13. Агент готовит и проверяет, но **не** пушит и **не** правит сервер без явного «да» оператора.

- [ ] **Step 1:** Мерж в `main` (после финального ревью) → оператор: `! git push origin main` → деплой (~3–5 мин; WAHA не запускается — профиля ещё нет, приём выключен, V22 накатилась). Проверка снаружи: `https://ais.westmed.kz` открывается, «Система → WhatsApp» — «приём выключен», шлюз WAHA.
- [ ] **Step 2:** С согласия оператора — SSH: `free -h`, `docker stats --no-stream`, `df -h`; копия `.env` (`cp /srv/ais/.env /srv/ais/.env.bak-$(date +%F)`).
- [ ] **Step 3:** С явного «да»: в `/srv/ais/.env` — `WHATSAPP_ENABLED=true`, `WHATSAPP_PROVIDER=waha`, ключи (`openssl rand -hex 32` на сервере, значения не печатать), `COMPOSE_PROFILES=whatsapp` → `cd /srv/ais && docker compose up -d` → `docker compose ps` (`ais-waha` Up), `docker compose logs --since 5m ais-backend | grep -i whatsapp` (сессия создана, ошибок нет), `curl -s -o /dev/null -w '%{http_code}' https://ais.westmed.kz/api/whatsapp/waha/webhook` снаружи → калитка/404 (вебхук снаружи недоступен).
- [ ] **Step 4:** Оператор — «Система → WhatsApp» → QR рабочим телефоном.
- [ ] **Step 5:** Приёмочный тест DEPLOY.md §7 вместе с оператором; результат — в PROGRESS и память.
- [ ] **Step 6:** Откат при проблеме — DEPLOY.md §7 «Откат».

---

## Self-review (сделан при написании плана)

**Покрытие спеки:** §1 решения — задачи 1 (переключатель, Green-API запасной), 4–8 (WAHA, очередь, догонка), 5–7 (файлы по требованию), 9 + 11 (страница), 2 + 3 (звонки фактом); решение 9 (без старой переписки) — `firstLinkWithoutMessagesDoesNothing`. §2 вызовы и события — задачи 3, 5; события `message` без `.any` не подписываются — compose задачи 12. §3 архитектура — 1, 7. §4 V22 и `CALL` — 2, 4. §5.1 вебхук (401/200/503, ON CONFLICT) — 4; §5.2 порядок и «отлёживание» — 7; §5.3 догонка — 8; §5.4 уборка — 8. §6 файлы (только личные, размер до скачивания, 25 МБ, `DOWNLOAD_FAILED` после 3 попыток, lifetime 600) — 1, 7, 12. §7 сессия и страница — 6, 9, 11; тексты строки состояния — 11. §8 звонки — 2, 3, 7, 11. §9 REST — 4, 9. §10 безопасность — 4 (подпись, permitAll, nginx), 5 (ключ не в текстах, файлы только с WAHA), 9 (только ADMIN), 12 (без `ports`, панель/Swagger выключены, `WAHA_PRESENCE_AUTO_ONLINE=False`). §11 конфигурация — 4 (yaml), 12 (compose, `.env`). §12 тесты: парсер — 3 и 13; вебхук — 4; очередь и общий цикл на очереди WAHA (порядок, «отлёживание», DONE/DROPPED, попытки, сбой БД, предохранитель) — 7; «не продвигается» — планировщик от источника не зависит, его тест идёт на Green-API (1); HTTP-клиент — 5; догонка — 8; звонки — 2, 7; переключатель — 1, 7; мутации — 1, 4, 7; стаб — 10; живьём — 10, 11, 13. §13 раскатка — 15, документация — 14.

**Отступления от спеки (осознанные):**
- Спека §3 называет метод источника `refreshStatus`; в плане — `housekeeping`: кроме статуса он ведёт догонку и уборку.
- Вебхук не проверяет провайдера: достаточно подписи (ключ HMAC задан только при WAHA). При временном переключении на Green-API события WAHA копятся в очереди и разбираются при возврате — без дублей (тот же id WhatsApp).
- Имена чатов берутся из справочников WAHA (`contacts`, `groups`, `lids`) — в спеке этого нет явно, но без них у WAHA не было бы имён из записной книжки, которые давал Green-API; сбой справочника сообщение не задерживает.

**Плейсхолдеры:** нет; единственное условное место — задача 13 (исправления парсера по живой форме), оно по природе живое и оформлено как «красный тест на фикстуре → правка → зелёный».

**Согласованность типов:** `WhatsappSource` (1) ↔ `GreenApiSource` (1) ↔ `WahaInboxSource` (7/8); `FileRef.locator/sizeBytes` (1) ↔ парсер (3) ↔ `download` (7); `WahaInboxWriter.insert(JsonNode[, String, String])` (4) ↔ догонка (8) ↔ тесты; `WahaSessionManager.refresh/account/session/number/live` (6) ↔ источник (7/8) ↔ сервис страницы (9); `WhatsappStatusHolder.setNumber/setSourceWarnings` (1), `setSourceError/state/number` (7), `CATCH_UP_FAILED` (8) ↔ фронт `WARNING_TEXT` (11); `ChatIngestWriter.CALL_SUBJECT` (2) ↔ тесты 7, 11.
