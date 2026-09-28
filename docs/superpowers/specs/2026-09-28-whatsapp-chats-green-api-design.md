# Чаты WhatsApp рабочего номера через Green-API — дизайн

**Дата:** 2026-09-28
**Статус:** утверждён оператором по разделам (чат 2026-09-28); ждёт просмотра спеки
**Контекст:** CLAUDE.md §6 (многорыночность, гард фоновых потоков), §7 (дети — через коллекции), §8 «Обращения», §12 (UI-kit, мобильные карточки), §14 (уроки), §16 (бэклог «WhatsApp Business и АТС через ту же точку входа»). Предыдущий блок: `docs/superpowers/specs/2026-09-27-leads-westmed-intake-design.md` (§12 там — задел под WhatsApp, этот дизайн его конкретизирует и меняет механизм).
**Запрос оператора:** «как привязать к этому WhatsApp» → «давай Green-API, номер будет отдельный, чисто по заявкам» → «как сделать, чтобы все рабочие чаты отображались».

## 1. Решения, принятые в обсуждении

| # | Вопрос | Решение |
|---|---|---|
| 1 | Как подключаем WhatsApp | **Green-API** (неофициальный шлюз, номер привязан как «связанное устройство»). Риск блокировки номера оператор принял: номер отдельный, только под заявки. Официальный WhatsApp Business Platform рассмотрен и отвергнут оператором |
| 2 | Объём первой версии | **Зеркало переписки:** АИС читает, отвечаешь с телефона. Отправки из АИС нет |
| 3 | Какие чаты | **Все чаты одного рабочего номера** (клиенты, поставщики, коллеги, группы) — экран «Чаты» как WhatsApp Web; обращения — слоем поверх чатов. Несколько номеров, Telegram и старая переписка — не в этом этапе |
| 4 | Файлы | **Хранить** (фото, PDF, Excel…); Excel-список **разбирается в позиции** обращения тем же гридом, что письма клиник |
| 5 | Механизм приёма | **Опрос очереди Green-API** (`ReceiveNotification`/`DeleteNotification`), не вебхук: калитка остаётся закрытой, работает локально, простой АИС до 24 ч без потерь |
| 6 | Правила чат ↔ обращение | §5 (утверждены: 30 дней для «Заявка создана», авто-«В работу» после ответа с телефона, «не клиент», «Создать обращение» из чата) |
| 7 | Плашка «открыт не тот рынок» на «Обращениях» | **Не делаем** (решение оператора) |

## 2. Green-API: что используем (сверено с документацией 2026-09-28)

**Модель.** Инстанс = один номер WhatsApp, привязанный QR-кодом как связанное устройство. Все вызовы: `{apiUrl}/waInstance{idInstance}/{метод}/{apiTokenInstance}` — ⚠️ **токен стоит в пути URL** (см. §10).

| Вызов | Назначение |
|---|---|
| `GET …/receiveNotification/{token}?receiveTimeout=20` | Следующее уведомление из очереди (long-poll 5–60 с). Пусто → ответ `null`. Иначе `{"receiptId": 1234567, "body": {…}}` |
| `DELETE …/deleteNotification/{token}/{receiptId}` | Уведомление обработано → удалить из очереди. Ответ `{"result": true}` |
| `GET …/getStateInstance/{token}` | `{"stateInstance": "authorized"}`; значения: `authorized`, `notAuthorized`, `blocked`, `sleepMode` (телефон выключен), `starting`, `suspended` (временные ограничения), `yellowCard` (устаревшее) |
| `GET …/getSettings/{token}` | Проверка настроек: `webhookUrl` (должен быть пуст — иначе уведомления уходят на вебхук, а не в очередь), `incomingWebhook`, `outgoingMessageWebhook` (уведомления об ответах с телефона), `editedMessageWebhook`, `deletedMessageWebhook`, `wid` (номер инстанса) |
| `GET <downloadUrl>` | Скачивание файла по ссылке из уведомления. Ссылка может вести на стороннее хранилище (пример в документации — `sw-media.storage.yandexcloud.net`), поэтому хосты не белим (§10) |

**Очередь.** Уведомления хранятся **24 часа**, отдаются FIFO. Читать очередь инстанса должен **один** потребитель: два (например, локальная АИС и прод) забирали бы сообщения друг у друга.

**Уведомления (`body.typeWebhook`):**

| typeWebhook | Что делаем |
|---|---|
| `incomingMessageReceived` | входящее сообщение → §6 |
| `outgoingMessageReceived` | отправлено **с телефона** → исходящее сообщение §6 |
| `outgoingAPIMessageReceived` | отправлено через API (АИС не шлёт; если шлёт кто-то ещё через этот инстанс) → исходящее §6 |
| `stateInstanceChanged` | `stateInstance` → строка состояния (§7) |
| `quotaExceeded` | лимит тарифа Developer → строка состояния (§7) |
| остальные (`outgoingMessageStatus`, `incomingCall`, `deviceInfo`, `statusInstanceChanged`, `incomingBlock`, …) | подтвердить (удалить из очереди) и пропустить — рекомендация Green-API |

Форма сообщения (входящее; у исходящего `senderData.chatId` — **получатель**, `sender` — собственный `wid`, `chatName` — имя получателя):

```json
{
  "typeWebhook": "incomingMessageReceived",
  "instanceData": { "idInstance": 7103000000, "wid": "79876543210@c.us", "typeInstance": "whatsapp" },
  "timestamp": 1588091580,
  "idMessage": "F7AEC1B7086ECDC7E6E45923F5EDB825",
  "senderData": { "chatId": "79001234567@c.us", "sender": "79001234567@c.us",
                  "chatName": "John", "senderName": "John", "senderContactName": "John Doe" },
  "messageData": { "typeMessage": "textMessage", "textMessageData": { "textMessage": "…" } }
}
```

Файл: `"typeMessage": "imageMessage" | "videoMessage" | "documentMessage" | "audioMessage" | "stickerMessage"`, `"fileMessageData": { "downloadUrl", "caption", "fileName", "jpegThumbnail", "mimeType" }` (размера в уведомлении нет). Правка: `editedMessage` + `editedMessageData { textMessage, stanzaId }`; удаление: `deletedMessage` + `deletedMessageData { stanzaId }` (`stanzaId` — id исходного сообщения).

**Виды чатов по `chatId`:** `…@c.us` — личный (цифры = номер), `…@g.us` — группа, `…@lid` — личный со скрытым номером. Не чаты: `status@broadcast` (истории), `…@newsletter` (каналы), прочие `…@broadcast` — пропускаем целиком.

**Ошибки HTTP:** `401`/`403` — неверные `idInstance`/токен; `429` — слишком часто; **`466`** — исчерпан лимит тарифа Developer; `5xx`/сеть — сбой сервиса.

**Тарифы:** Developer (бесплатный) — **только 3 чата**, и лимит действует на ПРИЁМ тоже: сообщения из 4-го чата не придут; годится для проверок. Business — без ограничений, оплата помесячно за инстанс. Для этого дизайна на проде нужен Business.

## 3. Архитектура

```
Green-API (очередь инстанса, 24 ч)                         АИС
  receiveNotification ◄──── WhatsappChatSync.drain()  ← WhatsappChatScheduler
  deleteNotification  ◄────      │  (свой поток «whatsapp-chats», MarketContext=KZ явно)
  getStateInstance    ◄────      │  сеть и файлы — ВНЕ транзакции
  getSettings         ◄────      │
  GET downloadUrl     ◄────      ▼  разобранное уведомление (+ байты файла, + позиции корзины с брендом)
                           ChatIngestWriter.write()  ← @Transactional, отдельный бин (§6 CLAUDE.md)
                              · чат (upsert) · сообщение (уникально по idMessage) · файл
                              · правила обращений (§5) → LeadIntakeService.ingest / LeadService-переход
                                          ▼
                           chat · chat_message · chat_attachment · lead.chat_id   (рынок KZ)
                                          ▼
                  экран «Чаты» (все чаты)      карточка обращения (переписка его чата + лента)
```

| Компонент | Ответственность |
|---|---|
| `integration/greenapi/GreenApiClient` + `GreenApiHttpClient` | HTTP к Green-API (`java.net.http`): receive/delete/state/settings/download. Интерфейс — ради фейка в тестах (как `WestmedClient`) |
| `integration/greenapi/dto/*` | уведомления как есть (Jackson, неизвестные поля игнорируются) |
| `integration/greenapi/GreenApiNotificationParser` | чистая функция: уведомление → `ParsedNotification` (вид: сообщение IN/OUT, правка, удаление, состояние, лимит, пропуск; чат, номер, имена, тип, текст, файл) |
| `util/SiteCartMessageParser` | чистая функция: текст по шаблону корзины westmed.kz (ru/kz/en) → позиции `(наименование, кол-во)` |
| `integration/greenapi/WhatsappChatSync` | цикл «получить → (скачать файл, найти бренды корзины) → записать → удалить»; ретраи и «ядовитые» уведомления (§6.3) |
| `integration/greenapi/WhatsappChatScheduler` | `@Scheduled` + свой однопоточный экзекьютор `whatsapp-chats`, флаг `running`, паузы после ошибок, периодический опрос состояния, состояние для UI (§7) |
| `service/ChatIngestWriter` | `@Transactional`: upsert чата, вставка сообщения/файла, правки/удаления, правила обращений (§5) |
| `service/ChatLeadRules` | поиск «открытого обращения» чата (§5.1) — вызывается из writer в той же транзакции |
| `service/ChatService` | чтение для экранов, «Создать обращение», «не клиент», Excel-превью файла |
| `controller/ChatController` | REST `/api/chats` (§8) |
| `entity/Chat`, `ChatMessage`, `ChatAttachment` | модель (§4). `Chat` — рыночная (`MarketScoped` + `@Filter(marketFilter)` + `MarketStampingListener`; **`@FilterDef` не переобъявлять**). Сообщения и файлы — без рыночного фильтра, доступ только через свой чат |
| `LeadService` (правка) | публичный переход «взять в работу автоматически» (тот же `transition`, что у «Взять в работу»); импорт позиций из Excel в обращение |
| `PrivateRequestImportService` (правка) | вынести обучение словаря заголовков (`saveSynonym`) в публичный метод `learn(mappings)` — нужен и импорту в обращение |
| фронт `shared/import-grid` | ОДИН грид разбора Excel вместо двух копий (`inbound`, `private-requests`) + третье место — чаты/карточка обращения |

## 4. Данные — миграция `V21__whatsapp_chats.sql`

⚠️ Номер **V21** — следующий свободный (V20 — passkeys). Схема — только новой миграцией (§10 CLAUDE.md).

```sql
CREATE TABLE chat (
    id                   BIGSERIAL PRIMARY KEY,
    market               VARCHAR(2)   NOT NULL,
    channel              VARCHAR(20)  NOT NULL,             -- WHATSAPP (позже, возможно, TELEGRAM)
    account              VARCHAR(40)  NOT NULL,             -- номер подключённого аккаунта: wid без «@c.us»
    external_chat_id     VARCHAR(100) NOT NULL,             -- chatId Green-API: 7701…@c.us / …@g.us / …@lid
    is_group             BOOLEAN      NOT NULL DEFAULT false,
    title                VARCHAR(255),                      -- имя контакта / профиля / название группы
    phone_norm           VARCHAR(20),                       -- только у @c.us; ключ склейки с обращениями
    not_client           BOOLEAN      NOT NULL DEFAULT false, -- «не клиент»: обращения не создаются
    last_message_at      TIMESTAMPTZ,
    last_message_preview VARCHAR(300),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_chat_ext         ON chat (channel, account, external_chat_id);
CREATE INDEX idx_chat_market_last       ON chat (market, last_message_at DESC);
CREATE INDEX idx_chat_phone             ON chat (market, phone_norm) WHERE phone_norm IS NOT NULL;

CREATE TABLE chat_message (
    id           BIGSERIAL PRIMARY KEY,
    chat_id      BIGINT      NOT NULL REFERENCES chat(id) ON DELETE CASCADE,
    external_id  VARCHAR(100) NOT NULL,                     -- idMessage
    direction    VARCHAR(3)  NOT NULL,                      -- IN / OUT
    sender_name  VARCHAR(255),                              -- автор (важно в группах)
    type         VARCHAR(20) NOT NULL,                      -- TEXT / IMAGE / VIDEO / AUDIO / DOCUMENT / STICKER / LOCATION / CONTACT / OTHER
    body         TEXT,                                      -- текст или подпись к файлу
    sent_at      TIMESTAMPTZ NOT NULL,                      -- timestamp уведомления
    edited       BOOLEAN     NOT NULL DEFAULT false,
    deleted      BOOLEAN     NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_chat_message_ext   ON chat_message (chat_id, external_id);
CREATE INDEX idx_chat_message_chat_time   ON chat_message (chat_id, sent_at DESC, id DESC);

CREATE TABLE chat_attachment (
    id                 BIGSERIAL PRIMARY KEY,
    message_id         BIGINT NOT NULL UNIQUE REFERENCES chat_message(id) ON DELETE CASCADE,
    file_name          VARCHAR(255),
    mime_type          VARCHAR(100),
    size_bytes         BIGINT,                              -- известен, только если файл скачан
    content            BYTEA,                               -- NULL — файл не сохранён (причина ниже)
    not_stored_reason  VARCHAR(20)                          -- TOO_LARGE / GROUP / DOWNLOAD_FAILED
);

ALTER TABLE lead ADD COLUMN chat_id BIGINT REFERENCES chat(id) ON DELETE SET NULL;
CREATE INDEX idx_lead_chat ON lead (chat_id) WHERE chat_id IS NOT NULL;
```

- Сообщение хранится **один раз** — в своём чате. Лента обращения (`lead_event`) сообщения не копирует; карточка обращения показывает их из чата (§9.3). Тип `MESSAGE` у `lead_event` остаётся для будущих каналов, этим этапом не пишется.
- `chat_attachment` — отдельная таблица, чтобы байты не грузились вместе со списком сообщений: список берёт метаданные проекцией, `content` читается только при скачивании.
- Рыночный фильтр — на `chat`. `chat_message`/`chat_attachment` по голому id наружу не отдаются (урок §9 CLAUDE.md про `PriceRequestItem`): только через чат текущего рынка.
- `lead.chat_id` — у одного чата со временем может быть несколько обращений (закрыли — клиент написал снова).

## 5. Правила: чат ↔ обращение (утверждены)

### 5.1 Определения

- **Личный чат** — `@c.us` или `@lid`. Группы (`@g.us`) обращений не создают никогда.
- **Номер чата** (`phone_norm`) — у `@c.us`: цифры `chatId` → `PhoneNormalizer.normalize`; не +7 → `"+" + цифры`. У `@lid` номера нет (`NULL`).
- **Активность обращения** — позднейшее из `lead.updated_at` и времени последнего сообщения его чата (`chat.last_message_at` ДО текущего сообщения).
- **Открытое обращение чата** на момент сообщения — обращение текущего рынка, у которого (`chat_id` = этот чат **или** `phone_norm` = номер чата, если номер есть) **и** (статус `NEW`/`IN_WORK` **или** статус `CONVERTED` и активность не старше **30 дней** — `chats.whatsapp.lead-converted-days`). Из нескольких — самое позднее по `received_at`, затем `id`. Склейка по номеру идёт **через каналы**: клиент оставил заявку на сайте, потом написал в WhatsApp — это одно обращение.

### 5.2 Правила

1. **Входящее в личном чате, чат не «не клиент»:**
   - есть открытое обращение → если у него нет чата, привязать (`lead.chat_id`); сообщение просто ложится в чат;
   - нет → **новое обращение** через `LeadIntakeService.ingest`: `source = "whatsapp"`, `externalId = "wa:" + idMessage` (идемпотентность), `channel = WHATSAPP`, статус `NEW`, `received_at` = время сообщения, контакт — имя (`senderContactName` → `senderName` → `chatName`), телефон (`"+" + цифры`, у `@lid` — пусто), `message` = текст/подпись (у файла без подписи — «[фото]», «[документ: имя]» …); тема — «WhatsApp», а если сообщение по шаблону корзины сайта (§6.7) — «Запрос КП» + позиции с брендом из каталога. Затем `lead.chat_id` = чат. Клиент подбирается `LeadClientMatcher` (как у сайта).
2. **Входящее в чате «не клиент»** — только сохраняется, с обращениями ничего не делаем.
3. **Исходящее (с телефона) в личном чате:**
   - есть открытое обращение → привязать чат, если не привязан; если статус `NEW` — перевести в `IN_WORK` **тем же переходом, что «Взять в работу»** (`LeadService.transition`: событие `STATUS`, автор — система, пояснение «ответ клиенту в WhatsApp с телефона»; у обращения с сайта уходит и `ext_status_pending` → статус на сайте тоже сменится);
   - нет → только сохраняется (ты написал первым — обращение не создаём).
4. **Группы** — только сохраняются.
5. **«Создать обращение»** (кнопка в чате; личный чат без открытого обращения, администратор): новое обращение `IN_WORK` (кто создал вручную, тот и ведёт — как ручной ввод), `source = "whatsapp"`, `externalId = NULL`, тема «WhatsApp», `message` = последнее входящее сообщение чата (если есть), контакт из чата, `lead.chat_id` = чат. Открытое обращение уже есть → `400` «у чата уже есть обращение».
6. **«Не клиент»** (переключатель, администратор) — только отключает автосоздание (правило 1); уже существующие обращения чата не трогает.
7. **Закрытое обращение не продолжается:** новое входящее после закрытия (и после «Заявка создана» старше 30 дней) = новое обращение. Старое видно в карточке через «этот номер уже обращался» (по `phone_norm`).

`LeadSources.WHATSAPP = "whatsapp"`; `writesBack("whatsapp") = false` (статусы обращений в WhatsApp не пишутся).

## 6. Приём сообщений

### 6.1 Цикл

`WhatsappChatScheduler.tick()` каждую секунду (`fixedDelay`), только при `enabled`; если цикл не идёт — ставит `drain()` в экзекьютор `whatsapp-chats` (не общий `scheduling-1`: long-poll держит поток до 20 с). В потоке: `MarketContext.set(<рынок из конфига, KZ>)` → работа → `MarketContext.clear()` в `finally` (§6 CLAUDE.md).

`WhatsappChatSync.drain()`: до 200 уведомлений за проход —

1. `receiveNotification(receiveTimeout=20)`; пусто → конец прохода.
2. Разобрать (`GreenApiNotificationParser`).
3. Сеть — **вне транзакции**: скачать файл (§6.4), для шаблона корзины — бренды через `WestmedProductLookup.byName` (сбой поиска → позиция без бренда, как у сайта).
4. `ChatIngestWriter.write(…)` — одна транзакция.
5. `deleteNotification(receiptId)` — **только после** успешной записи.

### 6.2 Идемпотентность

Сообщение уникально по `(chat_id, external_id = idMessage)`. Writer сперва проверяет существование: есть → ничего не делает (и обращение не создаётся, т.к. создание — в той же транзакции, что вставка сообщения). Сбой между записью и удалением → уведомление придёт снова → no-op → удаление. ⚠️ `DataIntegrityViolationException` целиком **не глотать** (урок разбора 2026-09-28: `WestmedLeadSync` глотает любое нарушение целостности как «гонку»): очередь читает один поток, гонки вставок нет, проверка — явная.

### 6.3 Сбои

| Что | Реакция |
|---|---|
| Обработка уведомления бросила исключение | не удалять → придёт снова; счётчик попыток по `receiptId` в памяти; **3-я неудача → удалить**, ошибку в `lastError` + WARN в лог (без URL) — «ядовитое» уведомление не должно забить очередь навсегда |
| Файл не скачался | как выше (повтор через очередь); на 3-й попытке сообщение пишется **без файла** с `not_stored_reason = DOWNLOAD_FAILED` — текст не теряется |
| `401`/`403` | «неверный idInstance или токен» → пауза `auth-backoff-ms` (10 мин) |
| `466` / уведомление `quotaExceeded` | состояние «лимит тарифа» (§7) |
| `429`, `5xx`, сеть | `lastError` → пауза `error-backoff-ms` (30 с) |
| АИС лежала > 24 ч | уведомления старше суток из очереди пропали — это принятое ограничение (старую переписку не тянем, решение 3); в строке состояния видно время последнего сообщения |

Состояние инстанса: `getStateInstance` при старте и каждые 5 мин (`state-refresh-ms`) + уведомления `stateInstanceChanged`. Настройки: `getSettings` при старте и раз в час (§7).

### 6.4 Файлы

- **Личные чаты:** скачать `downloadUrl` (только `https`; connect 10 с, чтение 60 с), читая поток со счётчиком; больше `max-file-mb` (25) → оборвать, `TOO_LARGE`, байты не сохранять.
- **Группы:** не скачивать, `GROUP` (имя и тип сохраняются).
- **Стикеры:** не скачивать; текст «[стикер]».
- Подпись к файлу → `body`. Файл есть и у исходящих (например, КП в PDF, отправленное с телефона) — хранится так же.
- Excel — по расширению `.xlsx`/`.xls` или MIME (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `application/vnd.ms-excel`).

### 6.5 Типы сообщений

| typeMessage | `type` | `body` |
|---|---|---|
| `textMessage` | TEXT | `textMessageData.textMessage` |
| `extendedTextMessage`, `quotedMessage` | TEXT | `extendedTextMessageData.text` (цитата в v1 не показывается) |
| `imageMessage`/`videoMessage`/`audioMessage`/`documentMessage` | IMAGE/VIDEO/AUDIO/DOCUMENT | подпись; файл §6.4 |
| `stickerMessage` | STICKER | «[стикер]» |
| `locationMessage` | LOCATION | «📍 » + адрес/название, иначе координаты |
| `contactMessage` / `contactsArrayMessage` | CONTACT | «[контакт: имя]» / «[контакты: N]» |
| `editedMessage` | — | исходное сообщение (`stanzaId`): `body` = новый текст, `edited = true`; исходного нет → сохранить как новое TEXT с `edited = true` |
| `deletedMessage` | — | исходное: `deleted = true`, **текст сохраняется**; исходного нет → пропустить |
| `reactionMessage` | — | пропустить |
| прочие (`pollMessage`, `groupInviteMessage`, `productMessage`, `orderMessage`, `interactiveButtons*`, неизвестные) | OTHER | «[сообщение типа <тип> — смотрите в WhatsApp]» |

### 6.6 Имя, номер и превью чата

- **Название чата:** личный — имя контакта из телефона (`senderContactName`), иначе имя профиля (`senderName`), иначе `chatName`, иначе номер; у исходящих — `chatName` (имя получателя; `senderName` там — наше собственное). Группа — `chatName`. Обновляется, когда уведомление приносит непустое имя, отличное от сохранённого.
- **Автор сообщения в группе** (`sender_name`) — `senderContactName`, иначе `senderName`.
- **Превью** (`last_message_preview`) — текст последнего сообщения, для файла без подписи — «[фото]», «[документ: имя]» и т.п.; обрезка 300 симв. `last_message_at` — время самого позднего сообщения (правка старого сообщения превью не сдвигает).

### 6.7 Шаблон корзины сайта

Кнопка WhatsApp на westmed.kz подставляет текст из корзины КП (`frontend/src/components/shared/WhatsAppButton.tsx` + `messages/{ru,kz,en}.json` сайта):

```
Здравствуйте! Интересует следующее оборудование:      ← whatsappGreeting (kz: «Сәлеметсіз бе! Келесі жабдық қызықтырады:», en: «Hello! I'm interested in the following equipment:»)

1. <наименование товара>
2. <наименование товара> (x3)

Прошу подготовить коммерческое предложение.            ← whatsappRequest
```

`SiteCartMessageParser`: текст **начинается** с одного из трёх приветствий (без учёта регистра и крайних пробелов) → строки `^\d+\.\s+(.+?)(?:\s+\(x(\d+)\))?\s*$` → позиции (кол-во по умолчанию 1). Нет приветствия → не шаблон (свободный нумерованный список клиента позициями НЕ становится — там бывают «1. срочно 2. доставка»). Клиент мог отредактировать текст перед отправкой — берётся то, что пришло.

## 7. Состояние подключения — строка «WhatsApp» на экранах «Чаты» и «Обращения»

`GET /api/chats/status` → `{ enabled, state, number, lastMessageAt, warnings[], lastError }`.

| Состояние / предупреждение | Текст | Цвет |
|---|---|---|
| выключено | «WhatsApp: приём выключен» | обычный |
| `authorized` | «WhatsApp +7 7xx xxx xx xx · подключён · последнее сообщение 5 мин назад» | обычный |
| `notAuthorized` | «WhatsApp: номер не подключён — отсканируйте QR-код в кабинете Green-API» | красный |
| `blocked` | «WhatsApp: номер заблокирован WhatsApp» | красный |
| `suspended` | «WhatsApp: временные ограничения WhatsApp на номере» | красный |
| `sleepMode` | «WhatsApp: телефон выключен — сообщения придут, когда он появится в сети» | красный |
| `starting` | «WhatsApp: инстанс запускается» | обычный |
| прочие значения (`yellowCard`, новые) | «WhatsApp: состояние инстанса — <значение>» | красный |
| предупреждение `WEBHOOK_URL_SET` | «в инстансе указан адрес вебхука — сообщения уходят туда, а не в АИС. Очистите поле в кабинете Green-API» | красный |
| предупреждение `INCOMING_OFF` | «не включены уведомления о входящих» | красный |
| предупреждение `OUTGOING_PHONE_OFF` | «не включены уведомления об ответах с телефона — ваши ответы не попадут в АИС» | красный |
| предупреждение `QUOTA_EXCEEDED` | «исчерпан лимит бесплатного тарифа — сообщения из новых чатов не приходят» | красный |
| `lastError` | «WhatsApp: Green-API недоступен / неверный ключ …» (человеческим текстом) | красный |

Включено, но не заданы `api-url`/`id-instance`/`api-token` → «не заданы учётные данные Green-API» (в лог один раз, не на каждом тике).

## 8. REST API

Чтение — любому вошедшему; запись — `@PreAuthorize("hasRole('ADMIN')")`, как везде в АИС. Чат чужого рынка или несуществующий → `404`.

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/api/chats?filter=ALL\|WITH_LEAD\|WITHOUT_LEAD\|GROUPS&q=` | список до 300, по `last_message_at DESC`: `id, title, phone, isGroup, notClient, lastMessageAt, lastMessagePreview, lead {id, status}` (текущее — открытое, иначе последнее обращение чата); `q` — по названию, номеру, тексту сообщений |
| GET | `/api/chats/{id}` | шапка чата + текущее обращение |
| GET | `/api/chats/{id}/messages?before={messageId}&limit=50` | порция сообщений (новые → старые по `sent_at, id`, отдаются в хронологическом порядке); у файла — метаданные `{attachmentId, fileName, mimeType, sizeBytes, stored, notStoredReason, isExcel}` |
| GET | `/api/chats/{id}/attachments/{attachmentId}` | файл (§10: inline только безопасные картинки) |
| POST | `/api/chats/{id}/attachments/{attachmentId}/preview` | Excel → `ImportPreviewResponse` (тот же `LineExtractor` + словарь) — ADMIN |
| POST | `/api/chats/{id}/lead` | «Создать обращение» (§5.2 п.5) — ADMIN |
| POST | `/api/chats/{id}/not-client` `{ "value": true }` | «не клиент» — ADMIN |
| GET | `/api/chats/status` | строка состояния (§7) |
| GET | `/api/leads/{id}/chat-messages` | сообщения чата обращения с момента `received_at` обращения (последние 200) — для карточки |
| POST | `/api/leads/{id}/items/import` `{ mappings[], lines[], mode: "REPLACE" \| "APPEND" }` | позиции обращения из Excel + обучение словаря заголовков — ADMIN; как `PUT /items` — только в `NEW`/`IN_WORK` |

`LeadResponse` получает `chatId` (или `null`).

## 9. Интерфейс

Общие правила проекта: standalone-компоненты, только токены (тёмная тема сама), `@media` последним блоком, мобильный ≤900px, тач-таргеты 40px, `cdr.detectChanges()` после async, без инлайн-цветов, без нативного `await` в экранах (§12, §14 CLAUDE.md).

### 9.1 Меню

Группа «Заявки»: **Чаты** — сразу после «Обращений», порядок остальных пунктов не меняется. Иконка — lucide `messages-square` (регистрация `LucideMessagesSquare` в `app.config.ts`), при пустом SVG — инлайн (урок §14).

### 9.2 «Чаты» (`pages/chats/chats.component.ts`)

- **Строка состояния WhatsApp** (§7) сверху.
- **Список (слева на десктопе):** имя, последнее сообщение одной строкой, время («5 мин назад», полная дата в `title`); чип обращения (Новое / В работе / Заявка создана / Закрыто), отметки «не клиент» и «группа». Поиск + фильтр-чипы «Все · С обращением · Без обращения · Группы». Автообновление раз в 10 с, пока экран открыт.
- **Переписка (справа):** входящие слева, исходящие справа (пузыри на токенах), время у каждого; в группах — имя автора; «изменено» / «удалено отправителем» пометкой; переносы строк сохраняются (`white-space: pre-line`). Файлы: картинки — миниатюра (клик — полный размер в оверлее), прочие — строка «📎 имя · 1,2 МБ» со скачиванием, не сохранённые — «файл не сохранён: больше 25 МБ / группа / не удалось скачать — смотрите в телефоне». У Excel — «Разобрать в позиции» (§9.4), только если текущее обращение чата в статусе «Новое»/«В работе» и у пользователя права администратора (позиции обращения правятся только в этих статусах). Порции по 50, «Показать раньше». Автообновление открытого чата раз в 10 с.
- **Шапка переписки:** имя, номер, «Написать в WhatsApp» (`wa.me`), обращение ссылкой (`/leads?openId=`) или «Создать обращение» (админ, личный чат без открытого обращения), переключатель «не клиент» (админ, личные чаты).
- **Телефон (≤900px):** мастер-деталь — список на весь экран, тап → переписка на весь экран с «← Назад»; открытый чат — в `?chatId=` (кнопка «назад» браузера работает).
- Только чтение: поля ввода нет.
- ⚠️ **Файлы и миниатюры грузятся только через `HttpClient` как blob** (`URL.createObjectURL`, как скачивание отчётов в `applies`/`reports`), а не голым `<img src="/api/…">` или ссылкой: такой запрос браузера не несёт заголовок `X-Market` (его вешает `marketInterceptor` только на `HttpClient`), бэкенд примет рынок по умолчанию РФ и ответит `404` на KZ-чат. Полный размер картинки — оверлей внутри страницы (не `window.open` — на iOS всплывающее окно с blob режется).

### 9.3 Карточка обращения (`lead-card.component.ts`)

- Если у обращения есть чат — переписка (`GET /api/leads/{id}/chat-messages`) **вперемешку с лентой событий по времени** (пузыри сообщений + записи ленты), ссылка «Открыть весь чат» → `/chats?chatId=`.
- Файлы — как в §9.2, включая «Разобрать в позиции» (по тому же условию: обращение «Новое»/«В работе», администратор).

### 9.4 Разбор Excel в позиции + общий грид

- Грид разбора выносится в **один** компонент `shared/import-grid.component.ts` (+ чистая функция сборки строк/маппингов). Им пользуются: «Входящие» (письмо клиники), «Частные заявки» (импорт файла) и новое место — файлы чатов. Поведение первых двух не меняется (сверка скриншотами до/после на 1280/390).
- «Разобрать в позиции» → превью (§8) → грид → «Заполнить позиции»: позиций нет — заполняются; есть — выбор «Заменить» / «Добавить». Маппинги колонок учат словарь заголовков (как при импорте письма).

## 10. Безопасность

- **Токен в пути URL:** адреса вызовов Green-API нигде не печатаются — ни в лог, ни в `lastError`, ни в текст исключения; сообщения об ошибках собираются вручную («Green-API: HTTP 401 на приёме сообщений»). Тест: токен не встречается ни в одном тексте исключения клиента. Учётные данные — только в env (`.env` прода, `~/.config/ais/` на Mac оператора), в чат не пишутся.
- **Наружу ничего не открывается:** опрос, не вебхук — калитка и nginx не меняются.
- **Файлы:** отдаются только через чат текущего рынка; `X-Content-Type-Options: nosniff`; inline — только `image/jpeg|png|webp|gif`, всё остальное (включая `text/html` и `image/svg+xml`) — `Content-Disposition: attachment` + `application/octet-stream`: присланный файл не выполнит скрипт в origin АИС. Имя файла — RFC 5987.
- **Скачивание по `downloadUrl`:** только `https`, без перехода на `http`, предел размера и таймауты (§6.4). Хосты не белим: Green-API отдаёт файлы и со стороннего хранилища.
- Права: чтение — всем вошедшим (как «Обращения»); «Создать обращение», «не клиент», превью и импорт Excel — ADMIN.

## 11. Конфигурация (`application.yaml`)

```yaml
chats:
  whatsapp:
    enabled: ${WHATSAPP_ENABLED:false}             # выкл по умолчанию, как все интеграции
    api-url: ${WHATSAPP_API_URL:}                  # «apiUrl» из кабинета Green-API, напр. https://7105.api.greenapi.com
    id-instance: ${WHATSAPP_ID_INSTANCE:}
    api-token: ${WHATSAPP_API_TOKEN:}              # секрет: только env
    market: ${WHATSAPP_MARKET:KZ}
    max-file-mb: ${WHATSAPP_MAX_FILE_MB:25}
    receive-timeout-s: 20
    auth-backoff-ms: 600000
    error-backoff-ms: 30000
    state-refresh-ms: 300000
    lead-converted-days: 30
```

## 12. Тестирование

Гейт: `./gradlew cleanTest test` — 0 падений (именно `cleanTest`, §13 CLAUDE.md), `npm run build` зелёный.

- **`GreenApiNotificationParserTest`** — реальные формы JSON из документации (§2): входящий текст, `extendedTextMessage`, цитата, фото с подписью, документ, ответ с телефона (`chatId` — получатель), группа (`@g.us`, автор), скрытый номер (`@lid`), `status@broadcast`/`@newsletter` (пропуск), правка, удаление, реакция, `stateInstanceChanged`, `quotaExceeded`, неизвестный тип.
- **`SiteCartMessageParserTest`** — ru/kz/en, `(x3)`, пробелы/регистр, текст без приветствия → не шаблон, пустой список.
- **`GreenApiHttpClientTest`** (стаб на JDK `HttpServer`, как `WestmedHttpClientTest`) — `receiveNotification` пусто/с телом, `deleteNotification`, `getStateInstance`, `getSettings`, скачивание с обрывом на пределе, `401`/`403`/`429`/`466`/`500` → свои исключения; **токен не встречается ни в одном тексте исключения**.
- **`WhatsappChatSyncTest`** (`FakeGreenApiClient`, `@SpringBootTest @Transactional` на nirdb) — все правила §5.2: новый личный чат → чат + сообщение + обращение `NEW`; второе сообщение → без нового обращения; ответ с телефона → `OUT` + `NEW → IN_WORK` (событие `STATUS`; у обращения с сайта — `ext_status_pending`); ответ без открытого обращения → только чат; закрытое → новое обращение; `CONVERTED` ≤ 30 дней → привязка, > 30 → новое; склейка по номеру с обращением с сайта; «не клиент» → без обращения; группа → без обращения, файл не скачан (`GROUP`); повторная доставка → без дублей; удаление из очереди только после записи (запись упала → `delete` не вызван); 3-я неудача → удалено + `lastError`; файл больше предела → `TOO_LARGE`; файл не скачался 3 раза → сообщение без файла `DOWNLOAD_FAILED`; шаблон корзины → «Запрос КП» + позиции с брендом (фейковый каталог); правка/удаление по `stanzaId`; всё с `market = KZ` при дефолтном `MarketContext` потока.
- **`ChatControllerTest`** (MockMvc из общего контекста — урок §14 про лишние пулы) — оператор читает `200`, пишет `403`; чат/файл чужого рынка → `404`; `text/html` и `svg` отдаются `attachment` + `octet-stream`; картинка — inline.
- **`LeadServiceTest`** (дополнение) — импорт позиций из Excel `REPLACE`/`APPEND`, обучение словаря, запрет в `CONVERTED`/`CLOSED`.
- **Мутации** (урок §14 «зелёный тест, который не может упасть»), каждая должна ронять ровно свой тест: снять проверку дубля сообщения; снять «не клиент»; снять условие 30 дней; снять запрет групп; поменять местами запись и `delete`; снять обрыв по пределу размера; снять запрет inline для не-картинок.
- **Живьём (Playwright)**, без касания прод-инстанса: локальная АИС на **отдельном** инстансе Developer (свой тестовый номер ИЛИ тот же рабочий номер, привязанный вторым связанным устройством — у каждого инстанса своя очередь, прод не обкрадывается). Сценарий: написать с личного телефона → чат и обращение; ответить с тестового → `OUT` и «В работе»; фото → миниатюра; PDF → скачивание; Excel → «Разобрать в позиции» → позиции; сообщение по шаблону корзины сайта → «Запрос КП» с позициями; «не клиент» → следующее сообщение без обращения; отвязать устройство в телефоне → строка «номер не подключён». Экраны 1280 и 390, светлая и тёмная тема; «Входящие» и «Частные заявки» после выноса грида — сверка с «до».

## 13. Раскатка

1. **Оператор:** регистрация в Green-API; инстанс на тарифе **Business** (на Developer видно только 3 чата); привязка нового рабочего номера QR-кодом (WhatsApp → «Связанные устройства»); в настройках инстанса включить уведомления о входящих, об отправленных с телефона, о правках и удалениях; **поле адреса вебхука оставить пустым**. Для локальных проверок — отдельный бесплатный инстанс Developer (§12).
2. **Оператор:** `apiUrl`, `idInstance`, токен — в `~/.config/ais/` на Mac (600), не в чат. Агент переносит их в `/srv/ais/.env` (`WHATSAPP_ENABLED=true`, `WHATSAPP_API_URL`, `WHATSAPP_ID_INSTANCE`, `WHATSAPP_API_TOKEN`) только с явного «да» оператора, копия `.env` до правки.
3. Перед включением на проде — свободное место на диске сервера (`df -h`): файлы хранятся в БД.
4. Мерж в `main` → **пуш делает оператор** (`! git push origin main`, §5 CLAUDE.md) → автодеплой, V21.
5. Проверка на проде: строка состояния «подключён», тестовое сообщение → чат и обращение.
6. **Сайт westmed.kz** (отдельный репозиторий `~/IdeaProjects/westmed`, отдельный деплой): номер WhatsApp в трёх местах — `frontend/src/components/shared/WhatsAppButton.tsx` (`PHONE`), `frontend/src/components/shared/Footer.tsx`, `frontend/src/app/[locale]/(storefront)/contacts/page.tsx`. Правку готовит агент по просьбе оператора; выкатывает оператор.
7. Документация: CLAUDE.md §5 (запуск с WhatsApp), §8 (механика), §14 (уроки), §15 (API), §16 (бэклог); DEPLOY.md (env); PROGRESS «▶ Последняя задача».

## 14. Вне этого этапа

1. Ответ клиенту из АИС (поле ввода в чате → `sendMessage`/`sendFileByUpload` Green-API).
2. Старая переписка при подключении (`GetChatHistory` — столько, сколько WhatsApp отдал устройству).
3. Несколько номеров WhatsApp (инстанс на номер; поле `account` уже в модели).
4. Telegram рабочего аккаунта через Green-API (тот же приём; поле `channel` уже в модели).
5. Вынос файлов из БД в файловое хранилище — если таблица `chat_attachment` заметно вырастет.
6. Звонки WhatsApp в ленту (уведомление `incomingCall`).
7. Привязка клика по кнопке WhatsApp на сайте (письмо/Telegram «WhatsApp-обращение») к пришедшему сообщению.

## 15. Источники (Green-API, проверено 2026-09-28)

- Приём уведомлений через HTTP API: https://green-api.com/en/docs/api/receiving/technology-http-api/
- ReceiveNotification: https://green-api.com/en/docs/api/receiving/technology-http-api/ReceiveNotification/
- DeleteNotification: https://green-api.com/en/docs/api/receiving/technology-http-api/DeleteNotification/
- Типы уведомлений и флаги настроек: https://green-api.com/en/docs/api/receiving/notifications-format/type-webhook/
- Входящее сообщение (типы `typeMessage`): https://green-api.com/en/docs/api/receiving/notifications-format/incoming-message/Webhook-IncomingMessageReceived/
- Входящее с файлом: https://green-api.com/en/docs/api/receiving/notifications-format/incoming-message/ImageMessage/
- Исходящее (с телефона): https://green-api.com/en/docs/api/receiving/notifications-format/outgoing-message/TextMessage/
- Правка/удаление: https://green-api.com/en/docs/api/receiving/notifications-format/incoming-message/EditedMessage/, https://green-api.com/en/docs/api/receiving/notifications-format/incoming-message/DeletedMessage/
- GetSettings: https://green-api.com/en/docs/api/account/GetSettings/
- GetStateInstance: https://green-api.com/en/docs/api/account/GetStateInstance/
- DownloadFile: https://green-api.com/en/docs/api/receiving/files/DownloadFile/
- Лимит тарифа (466, `quotaExceeded`): https://green-api.com/en/docs/api/receiving/notifications-format/QuotaExceeded/
- Тарифы: https://green-api.com/docs/about-tariffs/
