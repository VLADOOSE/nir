# Чаты WhatsApp через свой шлюз WAHA — дизайн

**Дата:** 2026-09-28
**Статус:** утверждён оператором по разделам (чат 2026-09-28); ждёт просмотра спеки
**Контекст:** это ДЕЛЬТА к `docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md` (дальше — «спека Green-API»): модель чатов, правила обращений, файлы, экраны «Чаты» и карточка обращения остаются как там. Меняется транспорт — откуда приходят уведомления. CLAUDE.md §6 (гард фоновых потоков), §8 «Чаты WhatsApp…», §14 (уроки: токен/ключ не в текстах ошибок, дедлайн на весь HTTP-обмен, «ядовитое» ≠ сбой инфраструктуры).
**Запрос оператора:** «платить не очень хочется» (Green-API Business платный, бесплатный тариф — только 3 чата) → из вариантов выбран WAHA; официальный API Meta — «муторно и долго»; запись разговоров — «сложно», отдельным блоком позже.

## 1. Решения, принятые в обсуждении

| # | Вопрос | Решение |
|---|---|---|
| 1 | Шлюз | **WAHA** (WhatsApp HTTP API, `devlikeapro/waha`) в Docker на нашем сервере. Бесплатно (Apache-2.0; с 2026.6.1 бывшие платные функции — в бесплатном образе). Техника та же, что у Green-API (номер — «связанное устройство»), риск блокировки номера тот же и принят оператором ещё для Green-API |
| 2 | Green-API | **Остаётся запасным**: `WHATSAPP_PROVIDER=waha\|greenapi`. Код и тесты Green-API не выбрасываются; при поломке WAHA после изменения протокола WhatsApp — переключение за минуту |
| 3 | Доставка | **Вебхук WAHA → своя очередь в БД АИС (`whatsapp_inbox`) → общий цикл обработки** + догонка пропущенного через API истории WAHA. Отвергнуты: обработка вебхука прямо в запросе (надёжность держалась бы на повторах WAHA в памяти, порядка нет) и опрос одной истории (правки, удаления и звонки через историю не приходят) |
| 4 | Движок | **GOWS** (рекомендован документацией, без браузера, ~200 МБ на номер). Образ закреплён: `devlikeapro/waha:gows-2026.9.1` |
| 5 | Файлы | **По требованию**: WAHA сама медиа не качает; АИС запрашивает файл конкретного сообщения личного чата с пределом 25 МБ. Файлы групп не качаются (как в спеке Green-API) |
| 6 | Привязка номера | **В АИС: «Система → WhatsApp»** (администратор): статус, QR-код, «Перезапустить», «Отвязать номер». Панель и Swagger WAHA выключены, наружу WAHA не открыта |
| 7 | Звонки | **Только факт**: служебная строка «📞 Входящий звонок» (+ «— принят» / «— отклонён»); правила обращений — как у входящего сообщения. Звук звонка шлюзы не умеют (подтверждено исходниками WAHA). Для Green-API звонки не делаем |
| 8 | Запись и расшифровка разговоров | **Вне этапа** — следующий блок «диктофон» (микрофон + системный звук в Chrome, расшифровка, выжимка в обращение) |
| 9 | Старая переписка | Не тянем: отсчёт с момента привязки (как в спеке Green-API) |
| 10 | Официальный API Meta | Отвергнут оператором («муторно и долго»). Остаётся единственным путём к звукам звонков WhatsApp — если понадобится |

## 2. WAHA: что используем (сверено с документацией и исходниками тега 2026.9.1)

**Модель.** Сессия WAHA = один номер WhatsApp, привязанный QR-кодом как связанное устройство. REST внутри сети Docker: `http://ais-waha:3000`, ключ в заголовке `X-Api-Key`. Имя сессии — `westmed` (`WHATSAPP_WAHA_SESSION`).

| Вызов | Назначение |
|---|---|
| `GET /api/sessions/{s}` | статус сессии и `me` (`id` — номер, `pushName`) |
| `POST /api/sessions {name, start:true, config}` | создать сессию (§7) |
| `POST /api/sessions/{s}/start`, `/restart`, `/logout` | запуск, перезапуск, отвязка (logout удаляет авторизацию, сессия стартует заново с новым QR) |
| `GET /api/{s}/auth/qr` | QR-код PNG (по умолчанию). Первый живёт 60 с, следующие по 20 с, всего до шести, дальше `FAILED` → нужен restart |
| `GET /api/{s}/chats/{chatId}/messages/{messageId}?downloadMedia=true` | сообщение со скачанным файлом → `media.url` |
| `GET <media.url>` + `X-Api-Key` | байты файла (`/api/files/{s}/{messageId}.{ext}`; ссылку берём как есть, не собираем сами) |
| `GET /api/{s}/chats/all/messages?filter.timestamp.gte=…&sortBy=timestamp&sortOrder=asc&limit=100&offset=…&downloadMedia=false` | догонка (§5.3); `chats/all` поддержан в GOWS |
| `GET /ping` | «жив ли контейнер» (без ключа) |

**Вебхуки** — глобально, переменными окружения WAHA (§11):
- заголовки: `X-Webhook-Request-Id` (одинаков во всех повторах), `X-Webhook-Timestamp`, `X-Webhook-Hmac`, `X-Webhook-Hmac-Algorithm: sha512`;
- подпись: `hex(HMAC-SHA512(сырое тело, ключ))`, заголовки не подписываются. Эталон из документации: тело `{"event":"message","session":"default","engine":"WEBJS"}`, ключ `my-secret-key` → `208f8a55dde9e05519e898b10b89bf0d0b3b0fdf11fdbf09b6b90476301b98d8097c462b2b17a6ce93b6b47a136cf2e78a33a63f6752c2c1631777076153fa89`;
- повторы: политика `exponential`, 2 с, 12 попыток ≈ 4,5 ч. ⚠️ **Повторы живут только в памяти WAHA** (рестарт WAHA их теряет), ретраится любой ответ не 2xx, включая 4xx; таймаута у HTTP-клиента WAHA нет. Порядок событий не гарантирован, дубли возможны;
- конверт: `{id, timestamp(мс), event, session, metadata, me, payload, engine, environment}`.

**События и что из них делаем:**

| Событие | → наше событие |
|---|---|
| `message.any`, `fromMe:false` | входящее сообщение (IN) |
| `message.any`, `fromMe:true`, `source:"app"` | ответ с телефона (OUT) |
| `message.any`, `fromMe:true`, `source:"api"` | отправлено через API (OUT, `viaApi`) — в работу не берёт |
| `message.edited` | правка: `editedMessageId` (сырой id оригинала), `body` — новый текст |
| `message.revoked` | удаление: `revokedMessageId` |
| `session.status` | статус сессии (`STOPPED`, `STARTING`, `SCAN_QR_CODE`, `PASSKEY_REQUIRED`, `PASSKEY_CONFIRMATION_REQUIRED`, `WORKING`, `FAILED`) |
| `call.received`, `call.accepted`, `call.rejected` | звонок (§8) |
| прочее | пропуск |

⚠️ Событие `message` (без `.any`) НЕ подписываем: в нём нет своих сообщений, а вместе с `message.any` давало бы дубли.

**Идентификаторы.** id сообщения — `{fromMe}_{chatId}_{rawId}[_{participant}]`. Чат берётся из сегмента `chatId` в id (надёжнее поля `from`); `external_id` сообщения — `rawId`: это тот же идентификатор WhatsApp, что `idMessage` у Green-API, поэтому при переключении провайдеров дублей не будет. Группа — `participant` = автор. `@lid` (скрытый номер) — телефон из `_data.Info.SenderAlt` (входящие) / `RecipientAlt` (свои), если есть; нет — чат без телефона, как у Green-API. `payload.timestamp` — секунды.

**Известные ограничения WAHA (принимаются):**
- звук звонков недоступен; об исходящих звонках событий нет (issue #1789);
- правки, удаления и звонки через API истории не восстанавливаются — только вебхуком;
- история GOWS для части групп после рестарта может отдаваться пустой (issue #1968) — группы без обращений, потеря терпима;
- ⚠️ issue #2241: в GOWS 2026.8.x сообщения, отправленные с телефона, приписывались не тому чату; объявлено исправленным, но о повторе сообщали в сентябре. Защита — закреплённая версия и приёмочный тест с телефоном оператора перед включением и после каждого обновления (§13);
- WAHA при скачивании держит файл целиком в памяти → потолок памяти контейнера (§11) и проверка размера до скачивания, где WhatsApp его сообщает (§6).

## 3. Архитектура

```
WAHA ──вебхук (HMAC)──► WahaWebhookController ──► whatsapp_inbox (PENDING)
  ▲                                                     │
  │ история (догонка)  WahaCatchUp ───────────────────►─┤
  │                                                     ▼
  └── файлы, статус ◄── WahaInboxSource ◄── WhatsappChatSync (общий цикл) ──► ChatIngestWriter (как в спеке Green-API)
Green-API (очередь) ◄── GreenApiSource ◄──┘ (если WHATSAPP_PROVIDER=greenapi)
```

- **Источник уведомлений — интерфейс `WhatsappSource`**: `isConfigured()`, `next()` (следующее или `null`), `ack(уведомление, dropped)`, `parse(уведомление) → ParsedNotification`, `download(FileRef, maxBytes)`, `refreshStatus(WhatsappStatusHolder)`. Реализации: `GreenApiSource` (обёртка над существующими клиентом и парсером — поведение Green-API не меняется) и `WahaInboxSource`. Активная выбирается `WHATSAPP_PROVIDER`.
- **Общий цикл** — существующие `WhatsappChatSync` + `WhatsappChatScheduler`, переведённые с `GreenApiClient` на `WhatsappSource`: попытки, пауза при сбое БД, предохранитель (3 за сутки), «не продвигается», свой поток `whatsapp-chats`, `MarketContext` — как сейчас. Периодические запросы состояния (сейчас `getSettings`/`getStateInstance`) уходят в `refreshStatus` источника.
- **`FileRef`** получает непрозрачную ссылку для скачивания: у Green-API — `downloadUrl`, у WAHA — `chatId` + id сообщения; понимает её только свой источник.
- **Пакет `integration/waha`:** `WahaClient` (+ `WahaHttpClient`: ключ в заголовке, `sendAsync` с дедлайном на весь обмен, ключ и адреса не попадают в тексты ошибок — как у Green-API), `WahaEventParser` (событие → `ParsedNotification`, чистая функция), `WahaWebhookController`, `WahaInboxWriter` (`@Transactional` запись в очередь), `WahaInboxSource`, `WahaCatchUp`, `WahaSessionManager` (создание сессии, статус, QR, перезапуск, отвязка).

## 4. Модель данных (V22)

```sql
CREATE TABLE whatsapp_inbox (
    id            BIGSERIAL PRIMARY KEY,
    provider      VARCHAR(20)  NOT NULL,               -- 'waha'
    request_id    VARCHAR(100),                         -- X-Webhook-Request-Id; у догонки NULL
    message_key   VARCHAR(200),                         -- 'fromMe_rawId' у message.any (вебхук И догонка); у прочих NULL
    event         VARCHAR(40)  NOT NULL,
    event_at      TIMESTAMPTZ  NOT NULL,                -- payload.timestamp — порядок обработки
    payload       TEXT         NOT NULL,                -- сырой JSON события целиком
    status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING',   -- PENDING / DONE / DROPPED
    last_error    VARCHAR(500),
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_whatsapp_inbox_request ON whatsapp_inbox (provider, request_id) WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_whatsapp_inbox_message ON whatsapp_inbox (provider, message_key) WHERE message_key IS NOT NULL;
CREATE INDEX idx_whatsapp_inbox_pending ON whatsapp_inbox (event_at, id) WHERE status = 'PENDING';
```

- Таблица не рыночная (сырые события до разбора); рынок проставляется при записи чата, как сейчас (`WHATSAPP_MARKET`).
- `ChatMessageType.CALL` — новое значение; колонка `chat_message.type` — `VARCHAR(20)` без CHECK, миграция не нужна.
- Больше схема не меняется.

## 5. Очередь и обработка

### 5.1 Приём вебхука
1. `POST /api/whatsapp/waha/webhook`, сырое тело. Нет `X-Webhook-Hmac` или подпись не сходится (сравнение за постоянное время) → **401**, ничего не пишем, в лог — одна строка без тела.
2. Из конверта берём `event`, `payload.timestamp`, `X-Webhook-Request-Id`; для `message.any` — `message_key`.
3. `INSERT … ON CONFLICT DO NOTHING` (повтор WAHA с тем же `request_id` или то же сообщение из догонки — не дублируется) → **200** сразу, без разбора и без сети.
4. БД недоступна → **503** (WAHA повторит).
Ничего больше в запросе не делаем: у WAHA нет таймаута, а медленный ответ задерживает её следующие события.

### 5.2 Обработка
- `WahaInboxSource.next()` — первое `PENDING` с `event_at ≤ now − 3 с` по `(event_at, id)`. Задержка ~3 с — чтобы правка или удаление, пришедшие чуть раньше оригинала, встали после него. Если оригинал всё же пришёл позже правки — поведение из спеки Green-API: правка сохраняется отдельным сообщением с пометкой «изменено», удаление неизвестного пропускается.
- `ack` → `DONE` или `DROPPED` (+ `last_error` — только класс/свой текст, как в строке состояния) и `processed_at`.
- Счётчик попыток, пауза при сбое БД, предохранитель, «не продвигается» — общий цикл (CLAUDE.md §8 «Цикл»), без изменений.

### 5.3 Догонка
- Когда: при старте АИС, при переходе сессии в `WORKING` и раз в 10 минут.
- С какого момента: самое позднее `sent_at` сообщений WhatsApp этого номера минус 10 минут. Сообщений ещё нет (первая привязка) — догонки нет: отсчёт с привязки (решение 9).
- Что: история `chats/all/messages` страницами по 100, `downloadMedia=false`; каждое сообщение кладётся в очередь как синтетическое `message.any` с тем же `message_key` → уже принятое вебхуком не дублируется, а запись в чаты и так отсекает дубль по `(chat_id, external_id)`.
- Ошибка догонки → в строку состояния («догонка не удалась: …»), повтор в следующий раз; приём вебхуков не останавливается.

### 5.4 Уборка
Раз в сутки: `DONE` старше 7 дней и `DROPPED` старше 30 дней удаляются.

## 6. Файлы

- Только личные чаты; группа → `GROUP` (как сейчас).
- Если в `_data` сообщения WhatsApp указан размер файла и он больше `WHATSAPP_MAX_FILE_MB` → `TOO_LARGE` без скачивания (иначе WAHA потянула бы весь файл в память). Где именно лежит размер у GOWS — фиксируется в тесте парсера по живому уведомлению (§12).
- Иначе: `GET …/messages/{id}?downloadMedia=true` → `media.url` → `GET` с ключом, поток с обрывом на пределе и дедлайном на весь обмен (как у Green-API). Имя и тип файла — из сообщения.
- Не скачалось 3 раза → сообщение без файла, `DOWNLOAD_FAILED` (как сейчас).
- `WHATSAPP_FILES_LIFETIME=600`: мы забираем файл сразу, дольше хранить его в WAHA незачем.

## 7. Сессия и страница «Система → WhatsApp»

- **Создание сессии — АИС сама** при старте (если `WHATSAPP_ENABLED=true` и провайдер `waha`): `GET` сессии; нет → `POST /api/sessions` с `{name, start:true, config:{metadata:{market}, ignore:{status:true, channels:true, broadcast:true, groups:false}}}`; остановлена → `start`.
- **Статус:** раз в минуту `GET` сессии (статус, `me`) + события `session.status`. В строку состояния (спека Green-API §7) добавляется `provider`.
- **Страница** (только администратор, пункт «WhatsApp» в группе «Система»):
  - провайдер; статус по-русски: `WORKING` — «подключён», `SCAN_QR_CODE` — «ждёт привязки», `STARTING` — «подключается», `FAILED` — «сессия упала», `STOPPED` — «остановлена», `PASSKEY_*` — «нужно подтверждение на телефоне»; номер и имя; время последнего сообщения; строка состояния;
  - в `SCAN_QR_CODE` — QR-код (картинку отдаёт АИС, получив у WAHA внутри сервера) с инструкцией «WhatsApp на рабочем телефоне → Настройки → Связанные устройства → Привязка устройства»; страница обновляет QR каждые 5 с;
  - «Перезапустить» (для `FAILED`/`STOPPED`) и «Отвязать номер» — с подтверждением в самой странице («сообщения перестанут приходить, пока не привяжете заново»);
  - провайдер Green-API — только состояние и подсказка «привязка — в кабинете Green-API».
- **Строка состояния на «Чатах»** — тексты для WAHA: «номер не подключён — привяжите в «Система → WhatsApp»» (`SCAN_QR_CODE`), «сессия WhatsApp упала — перезапустите в «Система → WhatsApp»» (`FAILED`), «сессия остановлена» (`STOPPED`), «подключается…» (`STARTING`), «WhatsApp просит подтверждение на телефоне» (`PASSKEY_*`); `WORKING` — «подключён», как `authorized` у Green-API.

## 8. Звонки

- `call.received` → служебное сообщение `type = CALL`, `external_id = "call:" + id звонка`, направление IN, текст «📞 Входящий звонок» или «📹 Входящий видеозвонок». Групповой звонок — в чат группы.
- `call.accepted` / `call.rejected` → к тексту того же сообщения дописывается «— принят» / «— отклонён» (правило превью — как у правки). Не пришло ни то, ни другое — строка остаётся без исхода.
- **Правила обращений** — как у входящего сообщения: новый клиент без открытого обращения → «Новое» с темой «Звонок в WhatsApp»; открытое обращение — звонок просто в его ленте; группы и «не клиент» — без обращений. В «В работе» звонок не переводит.
- **Интерфейс:** в переписке и в ленте обращения — служебная строка по центру, приглушённая, со временем, а не пузырь.

## 9. REST

| Метод | Путь | Кто | Назначение |
|---|---|---|---|
| POST | `/api/whatsapp/waha/webhook` | без входа, **подпись HMAC** | приём событий WAHA (§5.1). В nginx фронта закрыт (`404`) — его зовёт только WAHA внутри сети Docker |
| GET | `/api/whatsapp/session` | ADMIN | `{provider, status, number, name, qrAvailable}` |
| GET | `/api/whatsapp/session/qr` | ADMIN | `image/png`, `Cache-Control: no-store` |
| POST | `/api/whatsapp/session/restart` | ADMIN | перезапуск сессии |
| POST | `/api/whatsapp/session/logout` | ADMIN | отвязка номера |
| GET | `/api/chats/status` | вошедший | + поле `provider` |

## 10. Безопасность

- WAHA: без `ports:` (только внутренняя сеть), сильный API-ключ (иначе WAHA сама генерирует ключ при каждом старте и печатает его в лог), `WAHA_DASHBOARD_ENABLED=false`, `WHATSAPP_SWAGGER_ENABLED=false`, `WAHA_PRINT_QR=False`.
- ⚠️ `WAHA_PRESENCE_AUTO_ONLINE=False`: иначе WAHA при каждом запросе объявляет устройство «в сети» на 90 с, а пока есть активный «компьютер», WhatsApp не шлёт push на телефон — оператор перестал бы получать уведомления.
- Вебхук: только с верной подписью; в Spring Security — явное `permitAll` для одного пути (CSRF в приложении выключен), в nginx фронта — `location = /api/whatsapp/waha/webhook { return 404; }` перед `/api/`.
- Ключ API и HMAC — только в `.env`; в тексты ошибок, логи и строку состояния не попадают (тест).
- Том сессий WAHA = доступ к рабочему WhatsApp: не выкладывать, в бэкапы — только осознанно.
- QR, «Перезапустить», «Отвязать» — только администратор.
- Лицензия: WAHA — Apache-2.0; у бинарника движка GOWS (`devlikeapro/gows-plus`) файла лицензии нет. Для внутреннего использования не мешает; фиксируем как известный риск.

## 11. Конфигурация

**Бэкенд (`application.yaml`, блок `chats.whatsapp`):**
```yaml
    provider: ${WHATSAPP_PROVIDER:waha}            # waha | greenapi
    waha:
      url: ${WHATSAPP_WAHA_URL:http://ais-waha:3000}
      api-key: ${WHATSAPP_WAHA_API_KEY:}           # секрет: только env
      hmac-key: ${WHATSAPP_WAHA_HMAC_KEY:}         # секрет: только env
      session: ${WHATSAPP_WAHA_SESSION:westmed}
      settle-ms: 3000
      catch-up-ms: 600000
      catch-up-overlap-min: 10
      status-refresh-ms: 60000
      inbox-done-days: 7
      inbox-dropped-days: 30
```

**Сервис в `docker-compose.yml`:**
```yaml
  ais-waha:
    image: devlikeapro/waha:gows-2026.9.1
    profiles: ["whatsapp"]          # обычный деплой WAHA не запускает, пока в .env нет COMPOSE_PROFILES=whatsapp
    restart: unless-stopped
    mem_limit: 700m
    environment:
      WHATSAPP_DEFAULT_ENGINE: GOWS
      WAHA_API_KEY: ${WHATSAPP_WAHA_API_KEY}
      WAHA_DASHBOARD_ENABLED: "false"
      WHATSAPP_SWAGGER_ENABLED: "false"
      WAHA_PRINT_QR: "False"
      WAHA_PRESENCE_AUTO_ONLINE: "False"
      WAHA_BASE_URL: http://ais-waha:3000
      WHATSAPP_HOOK_URL: http://ais-backend:8080/api/whatsapp/waha/webhook
      WHATSAPP_HOOK_EVENTS: message.any,message.edited,message.revoked,session.status,call.received,call.accepted,call.rejected
      WHATSAPP_HOOK_HMAC_KEY: ${WHATSAPP_WAHA_HMAC_KEY}
      WHATSAPP_HOOK_RETRIES_POLICY: exponential
      WHATSAPP_HOOK_RETRIES_DELAY_SECONDS: "2"
      WHATSAPP_HOOK_RETRIES_ATTEMPTS: "12"
      WAHA_EVENTS_DOWNLOAD_MEDIA: "false"
      WHATSAPP_FILES_FOLDER: /app/.media
      WHATSAPP_FILES_LIFETIME: "600"
      WAHA_GOWS_DEVICE_HISTORY_SYNC_FULL_SYNC_DAYS_LIMIT: "7"
      WAHA_LOG_FORMAT: JSON
    volumes:
      - ais-waha-sessions:/app/.sessions
      - ais-waha-media:/app/.media
    networks: [ais-net]
```

**`.env` прода:** `WHATSAPP_ENABLED`, `WHATSAPP_PROVIDER=waha`, `WHATSAPP_WAHA_API_KEY`, `WHATSAPP_WAHA_HMAC_KEY`, `COMPOSE_PROFILES=whatsapp` (+ `.env.example` с заглушками).

## 12. Тестирование

Гейт: `./gradlew cleanTest test` — 0 падений; `npm run build`.

- **`WahaEventParserTest`** — реальные формы из документации WAHA: входящее, ответ с телефона (`source:"app"`), отправленное через API (`source:"api"`), файл (без скачивания: `hasMedia`, `media:null`), группа с `participant`, `@lid` с `SenderAlt`/`RecipientAlt`, правка, удаление, `session.status`, три события звонка, неизвестное событие. Минимум одно уведомление каждого вида снимается с живой локальной WAHA (без персональных данных) и закрепляется фикстурой — в т.ч. где лежит размер файла.
- **`WahaWebhookControllerTest`** — эталон подписи из документации принимается; неверная и отсутствующая подпись → 401 и пустая очередь; повтор с тем же `request_id` пишется один раз; то же сообщение из догонки — один раз.
- **Очередь** — порядок по `event_at`; событие моложе 3 с не берётся; `DONE`/`DROPPED`; существующие тесты цикла (попытки, сбой БД, предохранитель, «не продвигается») — на обоих источниках.
- **`WahaHttpClientTest`** (стаб на JDK HttpServer) — ключ в заголовке; дедлайн на вставшее тело; тексты ошибок без ключа и адреса; QR; создание/старт сессии; история постранично; файл с пределом.
- **`WahaCatchUpTest`** — отметка «самое позднее сообщение − 10 мин»; первая привязка без догонки; дубль со вебхуком не пишется.
- **Звонки** — строка `CALL`; «принят»/«отклонён»; новый клиент → обращение «Звонок в WhatsApp»; группа и «не клиент» — без обращений.
- **Переключатель** — `greenapi` работает как раньше (все тесты Green-API зелёные).
- **Мутации** (каждая роняет ровно свой тест): снять проверку подписи; снять уникальность `request_id`/`message_key`; снять задержку 3 с; снять пропуск файлов групп; снять проверку размера до скачивания. (`WAHA_PRESENCE_AUTO_ONLINE=False` тестом не ловится — это пункт приёмки «push приходят», §13.)
- **Dev-стаб WAHA** `scripts/waha-stub.mjs` (как стаб Green-API): API сессии (статусы, QR-картинка), сообщение по id с файлом, файлы, история для догонки; отправка подписанных вебхуков на бэкенд по сценарию (корзина сайта, Excel, фото, ответ с телефона, правка, удаление, группа, звонок).
- **Живьём:**
  - со стабом — экраны «Чаты», карточка обращения, «Система → WhatsApp» (1280/390, обе темы; оператор страницу не видит), догонка после остановки АИС;
  - с настоящей локальной WAHA и **тестовым** номером (не рабочим: каждое связанное устройство получает все сообщения, и переписка клиентов поехала бы в локальную базу) — привязка по QR из АИС, сообщения в обе стороны, файлы, правка, удаление, звонок, push на телефоне.

## 13. Раскатка

1. Мерж → push делает оператор → деплой: код с V22, WAHA не запущена (профиль), WhatsApp выключен.
2. Перед включением: `free -h` и `docker stats` на сервере (WAHA ~200 МБ, потолок 700 МБ).
3. С явного «да» оператора: в `/srv/ais/.env` (копия до правки) — `WHATSAPP_ENABLED=true`, `WHATSAPP_PROVIDER=waha`, сгенерированные `WHATSAPP_WAHA_API_KEY` и `WHATSAPP_WAHA_HMAC_KEY`, `COMPOSE_PROFILES=whatsapp` → `docker compose up -d`.
4. Оператор: «Система → WhatsApp» → QR рабочим телефоном.
5. **Приёмочный тест** (главное — issue #2241): оператор пишет на рабочий номер с личного; с рабочего отвечает 2–3 разным людям, в т.ч. тому, кого нет в контактах, — ответы легли в правильные чаты; фото, PDF, Excel; правка, удаление; звонок; **push на рабочем телефоне по-прежнему приходят**.
6. Откат: `WHATSAPP_ENABLED=false` → `docker compose up -d`; устройство WAHA удаляется в телефоне («Связанные устройства»).
7. Обновление WAHA — вручную: журнал изменений → новый тег в compose → приёмочный тест.
8. Документация: CLAUDE.md §5 (запуск со стабом WAHA и с локальной WAHA), §8, §14, §15, §16; DEPLOY.md §7 (WAHA вместо Green-API, Green-API — запасной путь); `.env.example`; PROGRESS.

## 14. Вне этапа

1. «Диктофон»: запись разговора в карточке обращения (микрофон + системный звук — Chrome 141+ на macOS 14.2+ / Windows; без него — только микрофон), расшифровка, выжимка «о чём говорили» в ленту; где расшифровывать и согласие клиента — там же.
2. Всплывающее «звонит клиент X» по событию звонка.
3. Звонки для Green-API (`incomingCall`).
4. Привязка по коду вместо QR (`auth/request-code`).
5. Импорт старой переписки при привязке.
6. Ответ клиенту из АИС, несколько номеров, Telegram (как в спеке Green-API §14).
7. Автообновление WAHA и оповещения (Telegram) о падении сессии.

## 15. Источники (WAHA, проверено 2026-09-28 по документации и исходникам тега 2026.9.1)

- Бесплатность с 2026.6.1: https://waha.devlike.pro/docs/how-to/waha-plus/, https://waha.devlike.pro/blog/waha-2026-6/
- Движки: https://waha.devlike.pro/docs/how-to/engines/, https://waha.devlike.pro/docs/engines/gows/
- События и вебхуки (HMAC, повторы): https://waha.devlike.pro/docs/how-to/events/
- Приём сообщений и медиа: https://waha.devlike.pro/docs/how-to/receive-messages/
- Звонки: https://waha.devlike.pro/docs/how-to/calls/
- Чаты и история: https://waha.devlike.pro/docs/how-to/chats/
- Конфигурация: https://waha.devlike.pro/docs/how-to/config/
- Хранилища и тома: https://waha.devlike.pro/docs/how-to/storages/
- Сессии, QR: https://waha.devlike.pro/docs/how-to/sessions/
- Безопасность: https://waha.devlike.pro/docs/how-to/security/
- Присутствие (push на телефон): https://waha.devlike.pro/docs/how-to/presence/
- Наблюдаемость: https://waha.devlike.pro/docs/how-to/observability/
- Образы и теги: https://hub.docker.com/r/devlikeapro/waha/tags
- Журнал изменений: https://waha.devlike.pro/docs/overview/changelog/
- Issues: #2241 (свои сообщения не в том чате), #1968 (история групп в GOWS), #1789 (исходящие звонки), #1564 (дубли) — https://github.com/devlikeapro/waha/issues
- Исходники: `src/modules/waha-webhook/WebhookPlugin.sender.ts` (повторы, HMAC), `src/core/engines/gows/session.gows.core.ts` (события, история), `src/api/calls.controller.ts` (звонки — только reject) — https://github.com/devlikeapro/waha/tree/2026.9.1
