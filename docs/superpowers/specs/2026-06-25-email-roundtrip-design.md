# Дизайн: Почта — round-trip КП + входящие (Блок D2)

**Дата:** 2026-06-25
**Статус:** утверждён, готов к плану
**Контекст:** второй шаг блока D (авто-источники) из `HANDOFF-АИС-медоборудование.md`. D1 дал импорт частной заявки из файла (ручная загрузка). D2 замыкает почтовый канал: отправка запросов КП поставщикам **с ящика zakup@westmed.kz** и **приём** входящих писем по IMAP — ответы поставщиков (авто-матч к КП) и письма клиник с Excel (в очередь на импорт через D1).

---

## 1. Цель

Закрыть цикл работы с КП через почту:
- **Отправка:** запрос КП уходит поставщику с zakup@westmed.kz с токеном `[КП-<id>]` в теме.
- **Приём:** система опрашивает ящик по IMAP, и для каждого письма решает:
  - ответ поставщика (тема содержит наш токен) → привязать к `PriceRequest`, статус RESPONDED, сохранить ответ;
  - письмо клиники с таблицей МИ (Excel-вложение, токена нет) → в очередь «Входящие» для импорта через парсер D1;
  - прочее → залогировать как UNMATCHED.

Оператор работает не с почтовым клиентом, а со страницей «Входящие»: видит классифицированные письма и действует (открыть КП / импортировать вложение).

---

## 2. Журнал решений

| # | Развилка | Решение |
|---|----------|---------|
| 1 | Доступ к ящику | Реальные SMTP+IMAP креды есть, но **тест — без реальных людей**. Интеграционный тест на встроенном **GreenMail** (SMTP+IMAP в процессе, без сети); реальные креды через env |
| 2 | Когда опрашивать | **IMAP-поллинг по умолчанию ВЫКЛЕН** (`mail.imap.enabled=false`). Включаешь когда готов. Плюс ручная кнопка «Проверить почту» |
| 3 | Скоуп приёма | **Оба источника**: ответы поставщиков (авто-матч → RESPONDED) + письма клиник с Excel (очередь → импорт через D1) |
| 4 | Матчинг ответа поставщика | **Токен `[КП-<id>]` в теме** при отправке; ответ (Re:…) сохраняет тему → парсим токен. Доп. сигнал — адрес отправителя |
| 5 | Хранение входящих | Новая сущность `InboundEmail` (письмо + классификация + вложение). Поллер пишет сюда; ответы ещё и апдейтят `PriceRequest` |
| 6 | Импорт письма клиники | **Переиспуем весь флоу D1**: вложение из `InboundEmail` → парсер D1 → превью → оператор назначает клиента → существующий commit. Клиента из письма НЕ угадываем (оператор назначает) |
| 7 | IMAP-транспорт | `jakarta.mail` (`Store`/`Folder`) — уже в `spring-boot-starter-mail`. Новых main-зависимостей не нужно; GreenMail — только `testImplementation` |
| 8 | Отправка | Дополняем существующий путь отправки КП: токен в теме + статус SENT. `EmailService.sendEmail` (SMTP) уже есть |

---

## 3. Скоуп блока D2

**В скоупе:**
- Сущность `InboundEmail` + таблица + репозиторий.
- `MailReceiveService` — IMAP-опрос непрочитанных, классификация, запись `InboundEmail`, матчинг ответов к `PriceRequest`.
- `MailPollScheduler` — `@Scheduled` (под флагом `mail.imap.enabled`), + ручной триггер опроса.
- Токен `[КП-<id>]` в теме при отправке запроса КП + статус SENT.
- `InboundController`: список входящих, ручной опрос, превью вложения письма клиники (через парсер D1), commit (существующий D1).
- Конфиг `mail.imap.*` в `application.yaml` (env), по умолчанию выключено.
- Frontend: страница «Входящие письма» (список + бейджи типа + «Проверить почту» + «Импортировать» для писем клиник).
- Интеграционный тест на GreenMail.

**Вне скоупа (далее):**
- Авто-резолв клиента из адреса отправителя (оператор назначает вручную).
- Парсинг цены/позиций из тела ответа поставщика (сохраняем текст ответа; структурирование — потом).
- OAuth/совр. провайдеры почты (логин/пароль через env; OAuth — потом).
- Тендерная площадка (другой источник).

---

## 4. Модель данных

**Новая сущность `InboundEmail`** (таблица `inbound_email`):
```sql
CREATE TABLE inbound_email (
    id                       BIGSERIAL PRIMARY KEY,
    from_address             VARCHAR(320),
    subject                  VARCHAR(998),
    received_at              TIMESTAMPTZ,
    type                     VARCHAR(20) NOT NULL,   -- SUPPLIER_RESPONSE | CLIENT_REQUEST | UNMATCHED
    matched_price_request_id BIGINT REFERENCES price_request(id) ON DELETE SET NULL,
    attachment_name          VARCHAR(255),
    attachment               BYTEA,                  -- байты Excel-вложения (только для CLIENT_REQUEST)
    excerpt                  VARCHAR(2000),          -- усечённый текст письма
    status                   VARCHAR(20) NOT NULL DEFAULT 'NEW',  -- NEW | PROCESSED
    market                   VARCHAR(2)                          -- рынок (штампуется при опросе по активному рынку ящика)
);
```
- DROP-блок `schema.sql`: `DROP TABLE IF EXISTS inbound_email CASCADE;` ПЕРЕД `price_request` (есть FK на него).
- `type` и `status` — Java-enum (`@Enumerated(STRING)`). `attachment` — `byte[]` (`@Lob`/`@Column columnDefinition="bytea"`).
- **Рыночный скоуп:** `inbound_email` несёт `market` (входящие принадлежат рынку ящика). Опрос идёт под фиксированным рынком (по умолчанию KZ — ящик zakup@westmed.kz относится к West-Med); сущность скоупится `@Filter`, как прочие рыночные. (При ручном опросе из UI — по активному рынку запроса.)

`PriceRequest` (существует) — для ответа поставщика: `status = "RESPONDED"`, `responseDate = today`, `note = <excerpt ответа>`. Без новых полей.

---

## 5. Backend-компоненты

- **`InboundEmail`** entity (рыночно-скоупленная: `MarketScoped` + `@Filter` + `@EntityListeners(MarketStampingListener)`, как `tender`/`distributor`) + `InboundEmailRepository` (`findAllByOrderByReceivedAtDesc`, `existsByMessageKey` для идемпотентности — см. ниже).
- **Идемпотентность:** письмо не задваивается при повторном опросе. Используем флаг IMAP `SEEN` (помечаем прочитанным после обработки) и читаем только непрочитанные. (Опционально — хранить `message_id`; для MVP достаточно SEEN.)
- **`MailReceiveService`:**
  - `poll() : PollResult` — открыть IMAP `Store` (`imap`/`imaps`) по конфигу, папка INBOX, выбрать непрочитанные; для каждого письма:
    - извлечь `from`, `subject`, текст (excerpt), Excel-вложения;
    - **классификация:** тема матчит `\[КП-(\d+)\]` → `SUPPLIER_RESPONSE` (id = группа); иначе есть `.xlsx`/`.xls` вложение → `CLIENT_REQUEST`; иначе → `UNMATCHED`;
    - сохранить `InboundEmail` (для CLIENT_REQUEST — с байтами вложения);
    - для SUPPLIER_RESPONSE — найти `PriceRequest` по id (если есть и рынок совпадает) → `RESPONDED` + `responseDate` + `note`; проставить `matched_price_request_id`;
    - пометить письмо SEEN.
  - Гард: если `mail.imap.enabled=false` или нет host — `poll()` возвращает «выключено» (для ручного триггера — понятное сообщение), не падает.
- **`MailPollScheduler`** — `@Scheduled(fixedDelayString=...)` зовёт `poll()` только при `mail.imap.enabled=true`. `@EnableScheduling` на конфиге.
- **Отправка (токен):** D2 гарантирует, что запрос КП уходит на email дистрибьютора через `EmailService.sendEmail(...)` с темой, содержащей `[КП-<priceRequestId>]`, и ставит `status=SENT` + `sentAt`. Точный call-site (существующий КП-send путь — `EmailController`/КП-сервис, либо новый минимальный, если живой отправки ещё нет) пинит recon в плане; токен формируется единым хелпером `"[КП-" + id + "]"`, который читает классификатор приёма.
- **`InboundController`** (`/api/inbound`):
  - `GET /` → `List<InboundEmailResponse>` (рыночный скоуп).
  - `POST /poll` → `PollResultResponse` (ручной опрос; `@PreAuthorize ADMIN`).
  - `POST /{id}/preview` → `ImportPreviewResponse` (прогон парсера D1 `LineExtractor` по сохранённому `attachment`; только для CLIENT_REQUEST). Коммит импорта — **существующий** `POST /api/private-requests/import/commit`.
  - `POST /{id}/processed` → помечает `InboundEmail.status=PROCESSED` (фронт зовёт после успешного импорта). `@PreAuthorize ADMIN`.
- **DTO:** `InboundEmailResponse { id, fromAddress, subject, receivedAt, type, matchedPriceRequestId, attachmentName, hasAttachment, excerpt, status }`; `PollResultResponse { enabled, fetched, supplierResponses, clientRequests, unmatched, message }`.
- **Конфиг (`application.yaml`):**
  ```yaml
  mail:
    imap:
      enabled: ${MAIL_IMAP_ENABLED:false}
      host: ${MAIL_IMAP_HOST:localhost}
      port: ${MAIL_IMAP_PORT:3143}
      username: ${MAIL_IMAP_USERNAME:zakup@westmed.kz}
      password: ${MAIL_IMAP_PASSWORD:}
      protocol: ${MAIL_IMAP_PROTOCOL:imap}   # imap | imaps
      poll-ms: ${MAIL_IMAP_POLL_MS:300000}
  ```

---

## 6. Frontend

- **Страница «Входящие письма»** (`/inbound`, сайдбар — группа «Заявки» рядом с «Частные заявки»):
  - Кнопка **«Проверить почту»** → `POST /api/inbound/poll` → тост с итогом (или «приём выключен — включите MAIL_IMAP_ENABLED»).
  - Таблица входящих: отправитель, тема, дата, **бейдж типа** (ответ поставщика / письмо клиники / прочее), статус.
  - Строка **CLIENT_REQUEST** → кнопка **«Импортировать»**: `POST /api/inbound/{id}/preview` → открывает **тот же грид-превью D1** (колонки+правка) → оператор выбирает клиента → `POST /api/private-requests/import/commit` → заявка создана, письмо PROCESSED.
  - Строка **SUPPLIER_RESPONSE** → ссылка/переход к связанному `PriceRequest` (тендер/заявка по `matched_price_request_id`).
- Валюта/рынок — по активному рынку (как везде). `X-Market` вешается интерсептором.

---

## 7. Обработка ошибок / edge

- `mail.imap.enabled=false` или нет host → `poll()` возвращает `{enabled:false, message:"приём выключен"}`, не падает; кнопка показывает тост.
- IMAP-коннект упал (неверные креды/сеть) → ловим, возвращаем `{message:"ошибка подключения: …"}`, не валим запрос.
- Тема с токеном, но `PriceRequest` не найден/чужой рынок → `InboundEmail` типа SUPPLIER_RESPONSE без `matched_price_request_id` (видно в списке как «не сопоставлено»).
- Письмо без вложения и без токена → UNMATCHED (для аудита).
- Несколько Excel-вложений → берём первое `.xlsx/.xls` (остальные — игнор в MVP).
- Повторный опрос → SEEN-письма не перечитываются (идемпотентность).
- Вложение слишком большое → ограничение размера IMAP-fetch (разумный лимит, напр. 10 МБ — как multipart).

---

## 8. Тестирование (без реальных людей)

- **Интеграционный тест (GreenMail, `testImplementation`):** поднять встроенный GreenMail (SMTP+IMAP), завести ящик zakup@westmed.kz, положить в INBOX два письма:
  1. ответ поставщика с темой `Re: Запрос КП [КП-<id>]` (id существующего `PriceRequest`),
  2. письмо «клиники» с темой без токена и `.xlsx`-вложением (таблица наименование/производитель/кол-во).
  Сконфигурировать `mail.imap.*` на GreenMail, вызвать `MailReceiveService.poll()`, проверить: `PriceRequest` стал RESPONDED + `matched_price_request_id` проставлен; письмо клиники → `InboundEmail` типа CLIENT_REQUEST с непустым `attachment`; повторный `poll()` ничего не задваивает (SEEN).
- **Unit:** классификатор темы (`[КП-123]` → SUPPLIER_RESPONSE/123; без токена + xlsx → CLIENT_REQUEST; иначе UNMATCHED).
- **Frontend:** сборка; ручной e2e — «Входящие» → «Проверить почту» (на dev-конфиге/выключено — корректный тост); импорт письма клиники открывает грид D1.
- **Демо:** опрос ВЫКЛ по умолчанию; включаешь env и шлёшь тестовое письмо сам себе — реальных адресатов не трогаем.

---

## 9. Следующие шаги (после D2)

1. Авто-резолв клиента частной заявки по адресу отправителя (match facility.email).
2. Структурирование ответа поставщика (цена/позиции из тела/вложения) → авто-заполнение `PriceRequestItem.responsePrice`.
3. OAuth-доступ к почте (вместо логин/пароль), современные провайдеры.
4. LLM-парсер вложений (опт-ин), CSV/PDF; тендерная площадка как источник; Flyway.
