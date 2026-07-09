# PROGRESS / Handoff — АИС Регион-Мед / West-Med

> Durable-статус проекта для возобновления после чистки контекста. Обновлять в конце крупных сессий.
> Детали архитектуры/механик — в `CLAUDE.md` (project brain). Здесь — «где мы и что дальше».

**Последнее обновление:** 2026-07-09 (конец сессии). Ветка `main`, HEAD `cea1df8`. Всё ниже — **уже в main**.

## 1. Что сделано в этой сессии (6 фич + фиксы, все смержены `--ff-only`)

Каждая прошла полный цикл: brainstorm → spec → plan → SDD (субагент/задача + ревью) → whole-branch review → merge. У каждой — спек в `docs/superpowers/specs/`, план в `docs/superpowers/plans/`, живая Playwright-проверка.

1. **Подбор поставщиков по виду МИ** (§8 CLAUDE.md) — `LotTypeClassifier` (обучаемый словарь `equipment_type_synonym`, **V7**) + ранг `LotSourcingService` typeHit⊕brandHit + Tier 2 (`ComplectTermExtractor` для аксессуаров) + персист вида МИ лота (`POST /api/lots/{id}/equipment-type`). Данные: `equipment_type` расширен до 18 типов; реальные KZ-дистрибьюторы — **V8** (10 ТОО).
2. **Качественные KZ-дистрибьюторы** (§8) — **V9**: веб-верификация → **20 ТОО** с реальными брендами/видами МИ/email (email на generic-доменах — verify перед боевой рассылкой). Имя поставщика в КП-панели — кликабельная ссылка на сайт (↗).
3. **Грамотная отправка КП + приём (ч.2)** (§9) — `KpEmailComposer` приветствие «Здравствуйте!» без ФИО + подпись-инструкция; `EmailService` → `MimeMessage`+Reply-To+UTF-8 (боевой SMTP через env); редактируемое превью (`POST /api/price-requests/preview` + `subject/bodyOverride` в `/send`, токен `[КП-id]` всегда серверный); `POST /{id}/resend`; round-trip на zakup@ (`MailReceiveService` не самопомечает свои письма). UI: «Проверить ответы», «↻ Переслать».
4. **Редактор шаблона письма КП** (§9) — `EmailTemplate` (**V10**, строка на рынок) + `EmailTemplateRenderer` (плейсхолдеры `{{приветствие}}/{{компания}}/{{позиции}}/{{дедлайн}}/{{реестр}}`); фолбэк на зашитый дефолт (`KpEmailComposer.DEFAULT_*`); API `GET/PUT/default`; страница «Система → Шаблон письма КП». **⚠ Анти-лик:** письмо больше НЕ раскрывает тендер (убраны номер и ссылка на объявление, `announceLink` удалён).
5. **Двухступенчатый фильтр импорта goszakup** (§5) — `MedicalRelevanceFilter` по названиям ЛОТОВ (медтовар = POSITIVE ∧ ¬NEGATIVE, ≥1 медтоварный лот → релевантен) в `importOne` + чистка keyword-предфильтра (убраны «аппарат»/«медицинск»). Живой прогон: 3000 получено → 65 прошли имя → 28 создано чистых (дроны/медотходы/медосмотр отсеяны). Fix-forward (старое не чистим).
6. **Сравнение предложений (ч.3a)** (§9) — `GET /api/tenders/{id}/offer-comparison` → `OfferComparisonService` (пивот лоты×поставщики, мин по лоту, итоги) + модалка `app-offer-comparison` + кнопка «Сравнить предложения».

**Фиксы:** `learn` UPSERT (классификатор), `EmailTemplateEndpointTest` изоляция (`deleteAllInBatch @BeforeEach` — падал при сохранённом шаблоне; JPA insert-before-delete гочи), честный `matched` в импорте, мёртвый код.

## 2. Статус потока «Частники / отправка КП» (главное направление)

- **ч.1 подбор по виду МИ** — ✅ закрыто.
- **ч.2 грамотная отправка + приём** — ✅ закрыто.
- **ч.3 разбор ответов:**
  - **3a сравнение предложений** — ✅ закрыто (эта сессия).
  - **3b авто-парс цены из письма** — ⏳ **следующее**. Извлечь цену/срок/модель из текста ответа поставщика → автозаполнить `PriceRequestItem.responsePrice` (сейчас вводится вручную в «Калькулятор наценки»). Ключевое решение при старте: **стратегия эвристика vs LLM** (опт-ин по Anthropic API-ключу, дёшево на Haiku — уже в бэклоге). Письма поставщиков — вольный русский текст, форматы разные → эвристика хрупка, LLM надёжнее.

## 3. Как запустить (свежая сессия)

- **Backend:** `cd /Users/vlad/IdeaProjects/AIS && GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" ./gradlew bootRun` (порт 8080; токен опционален — нужен только кнопке «ТЗ» и импорту goszakup). ⚠ `./gradlew`/psql — только с Bash `dangerouslyDisableSandbox: true` (песочница блокирует :5432).
- **Frontend:** `cd frontend && npm start` (4200). Логин `admin`/`admin`. Рынок слева вверху (KZ = West-Med ₸).
- **MailHog** (фейковый SMTP, письма КП копятся тут, наружу не уходят): `docker start nir2-mailhog` → UI `http://localhost:8025`. Боевой SMTP/IMAP на `zakup@`/`info@westmed.kz` — только через env (§5/§9 CLAUDE.md; пароли в `/tmp/*.pass`, не эхо-печатать).
- **Токен goszakup** — `/tmp/goszakup.token` (вне репо, протухает; перевыпуск на портале goszakup). Пароли Mail.ru/токен, засветившиеся в чате, на проде перевыпустить.
- **БД nirdb** — данные переживают рестарт (Flyway V1–V10 + Java-инициализатор реестра). psql: `/Library/PostgreSQL/17/bin/psql`, `PGPASSWORD=admin`, user `postgres`.

## 4. Что дальше (приоритет сверху; полный бэклог — §16 CLAUDE.md)

1. **ч.3b — авто-парс цены из письма** (эвристика/LLM) — автозаполнение `responsePrice`.
2. Выбор победителя по лоту (сплит-заказ) + связь с apply/заявкой; сводное КП клиенту.
3. **Хардинг `resend`** — не обновляет `sentAt`, читает LAZY items под OSIV → при ч.3b объединить (отдельный @Transactional-шаг + JOIN FETCH; FE-гард от двойного клика).
4. Мелочи редактора шаблона: поле «Тема» без focus-хендлера (чип идёт в тело); `no-positions` через error-тост (уместнее info); `{{обращение}}`.
5. Тюнинг фильтра импорта goszakup после наблюдений (обучаемый словарь вместо хардкода; deep-скан по лотам если мало охвата). Фейки/сид — при деплое (§10).
6. Нечёткий/транслит матч брендов (Mindray↔Майндрей). RF-реестр (Росздравнадзор). Починить 2 `ApplyAutoFillServiceTest`.

## 5. Ключевые решения/гочи этой сессии (чтобы не переоткрывать)

- **Анти-лик писем КП** (реш. оператора): не раскрывать тендер — недобросовестный поставщик пойдёт участвовать сам. Номер тендера + ссылка убраны из письма.
- **Фильтр импорта** ловил мусор словами по ИМЕНИ объявления («аппарат»→дроны/«аппарат акима», «медицинск»→медотходы/медосмотр). Решение — судить по ЛОТАМ (`MedicalRelevanceFilter`).
- **JPA delete-before-insert**: `deleteAll()`+`save()` в одном flush → Hibernate ставит INSERT перед DELETE → UNIQUE-конфликт. Использовать `deleteAllInBatch()` (немедленный DELETE). Тот же урок — `ComplectWriter` flush.
- **`findById` (Spring Data) аспект-фильтруется по рынку** (не `em.find`!). `em.find` минует фильтр (RegistryMatchService). В доках раньше путали — код-комментарии поправлены.
- **`app-*` модалки — отдельные standalone-компоненты** (свой style-бюджет): у `tenders.component.ts` запас `anyComponentStyle` ~40 B до 16 kB → тяжёлые панели выносить в свои компоненты (`app-offer-comparison`, `app-bulk-price-modal`).
- **Playwright**: сессия слетает при рестарте бэка — логиниться заново; эндпоинты под auth удобно дёргать `fetch(..., {credentials:'include'})` из контекста страницы.

## 6. Тест-гейт

`./gradlew test` (sandbox off, `lsof -ti :8080 | xargs kill -9` перед прогоном). **Зелёный = падают ТОЛЬКО 2 пред-существующих `ApplyAutoFillServiceTest`** (расхождение ×1.25, в бэклоге). Фронт-гейт — `cd frontend && npm run build`. Перед прогоном тестов чистить `email_template` от строк, сохранённых через UI (иначе — было — `EmailTemplateEndpointTest` мог падать; исправлено `deleteAllInBatch`).

## 7. Рабочий процесс (superpowers)

Каждый блок: brainstorm → spec (`docs/superpowers/specs/`) → plan (`docs/superpowers/plans/`) → SDD (субагент/задача + per-task ревью + fix-loop, леджер `.superpowers/sdd/progress.md`) → whole-branch review → merge `--ff-only` (не на main напрямую). Субагенты наследуют модель сессии (реш. 2026-07-04: Fable 5; сейчас Opus 4.8). Коммиты: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
