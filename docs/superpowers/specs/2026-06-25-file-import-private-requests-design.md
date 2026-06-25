# Дизайн: Импорт частной заявки из файла (обучаемый парсер) — Блок D1

**Дата:** 2026-06-25
**Статус:** утверждён, готов к плану
**Контекст:** первый шаг блока D (авто-источники) из roadmap `HANDOFF-АИС-медоборудование.md`. Блок B дал ручной ввод частных заявок через шов `PrivateRequestService.createFromLines`. D1 добавляет **загрузку Excel-файла** клиники и автоматический разбор его в строки заявки — без печати руками.

---

## 1. Цель

Клиника присылает таблицу медизделий (Excel) с просьбой о КП. Сейчас оператор перебивает её в форму строка за строкой. D1 даёт: **загрузить файл → парсер сам размечает колонки → оператор проверяет/правит в превью → создаётся заявка** (через готовый `createFromLines`). Парсер — на правилах, и **умнеет от правок оператора** (обучаемые синонимы заголовков), без ML/LLM.

---

## 2. Декомпозиция блока D и место D1

Поток D = **канал доставки** + **парсер** + **приём в заявку**.
- **Парсер** (файл → строки `{name, manufact, quantity}`) — ядро, общее для любого канала.
- **Канал:** D1 — ручная загрузка файла; **D2 (потом)** — живой приём из почты (IMAP) тем же парсером.
- **Приём** — готов: `createFromLines` (шов из блока B).

D1 строит и обкатывает парсер на ручной загрузке, без зависимости от реального почтового ящика.

---

## 3. Журнал решений

| # | Развилка | Решение |
|---|----------|---------|
| 1 | Канал первым | **Ручная загрузка файла** (D1). Живой IMAP — D2, потом (нужен реальный ящик/креды) |
| 2 | Движок парсинга | **Правила** (по заголовкам колонок), за интерфейсом `LineExtractor`. LLM — опция на будущее (вкл. по env-ключу), не строим в D1 |
| 3 | «Обучаемость» | **Без ML/LLM:** словарь синонимов заголовков, пополняемый из правок оператора в превью. Таблица `header_synonym` |
| 4 | Биллинг LLM | API Claude — отдельная плата по токенам (не покрывается подпиской), дёшево, но нужен ключ. Поэтому правила — основной бесплатный путь, LLM — опт-ин на потом |
| 5 | Форматы | **Excel `.xlsx`/`.xls`** (POI уже в зависимостях, `WorkbookFactory`). CSV/PDF/вставка текста — отложено (интерфейс позволит добавить) |
| 6 | Точка обучения | Шаг **превью**, который в импорте и так нужен: оператор размечает/правит колонки → правки сохраняются как синонимы |
| 7 | Скоуп синонимов | **Глобальный** (без рыночного `market`): имя колонки «Изделие» значит одно и то же на РФ и KZ |
| 8 | Приём | Переиспользуем `PrivateRequestService.createFromLines(clientFacilityId, lines)`. Новых сущностей заявки не вводим |

---

## 4. Скоуп блока D1

**В скоупе:**
- `LineExtractor` (интерфейс) + `RuleBasedLineExtractor` (Excel `.xlsx`/`.xls` через POI).
- Обучаемый словарь синонимов заголовков (`header_synonym`), пополняемый из правок оператора.
- Превью импорта: грид с угаданной разметкой колонок + ручная правка + выбор клиента.
- Создание заявки из превью через `createFromLines`.
- Frontend: кнопка «Импорт из файла» на странице «Частные заявки» + флоу превью→создание.

**Вне скоупа (далее):**
- Живой приём из почты (IMAP) — блок D2.
- CSV/PDF/вставка текста как форматы (интерфейс готов к расширению).
- LLM-реализация `LineExtractor` (опт-ин по ключу) — задел.
- Авто-генерация финального КП клиенту.

---

## 5. Модель данных

**Одна новая таблица — `header_synonym`** (глобальная, без рыночного скоупа):
```sql
CREATE TABLE header_synonym (
    id          BIGSERIAL PRIMARY KEY,
    header_norm VARCHAR(255) NOT NULL UNIQUE,   -- нормализованный заголовок (lower+trim)
    field       VARCHAR(20)  NOT NULL           -- NAME | MANUFACT | QUANTITY
);
```
- `header_norm` — заголовок колонки, приведённый к нижнему регистру с trim. UNIQUE → апсерт по нему.
- `field` — какое поле строки этой колонке соответствует.
- В DROP-блок `schema.sql` добавить `DROP TABLE IF EXISTS header_synonym CASCADE;` (независимая таблица, без FK — порядок относительно других не критичен).

**Сид (`data.sql`)** — распространённые рус-заголовки:
- NAME: `наименование`, `наименование товара`, `модель`, `товар`, `изделие`, `позиция`, `оборудование`
- MANUFACT: `бренд`, `производитель`, `изготовитель`, `марка`, `вендор`
- QUANTITY: `кол-во`, `количество`, `шт`, `штук`, `q-ty`, `qty`

**Java-enum** `LineField { NAME, MANUFACT, QUANTITY, IGNORE }` (`com.vladoose.nir.entity` или `...parser`). `IGNORE` — колонка не размечена/не нужна (в БД-синонимах не хранится; только в API превью/commit).

---

## 6. Backend-компоненты

- **`LineField` enum** — `NAME | MANUFACT | QUANTITY | IGNORE`.
- **`HeaderSynonym`** entity (`id`, `headerNorm` UK, `field` enum STRING) + `HeaderSynonymRepository` (`findByHeaderNorm`, `findAll`).
- **`LineExtractor`** (интерфейс): `ImportPreview extract(byte[] content, String filename, Map<String,LineField> learned)` — разбор файла в грид + разметку колонок. `learned` — карта `header_norm → field` из БД (встроенный словарь + выученное).
  - `ImportPreview { List<PreviewColumn> columns; List<List<String>> rows; }`
  - `PreviewColumn { int index; String header; LineField field; }` (field — угаданное; `IGNORE` если не узнали)
- **`RuleBasedLineExtractor implements LineExtractor`:**
  - Читает `.xlsx`/`.xls` через POI `WorkbookFactory.create(InputStream)`; первый лист.
  - Шапка — первая непустая строка; данные — строки ниже.
  - Для каждой колонки: `header_norm` = lower+trim заголовка → ищет в `learned` (встроенный seed уже влит туда из БД-сида) → `field` или `IGNORE`.
  - Ячейки в строки — как строки (числа форматируются без `.0` для целых).
  - Неподдерживаемый формат / битый файл → `IllegalArgumentException` (→ 400).
- **Встроенный словарь** — реализован через **сид `header_synonym` в `data.sql`** (а не хардкод в Java), чтобы один источник правды и расширялся правками. `RuleBasedLineExtractor` работает только с тем, что пришло в `learned`.
- **`PrivateRequestImportService`:**
  - `preview(byte[] content, String filename) : ImportPreview` — грузит `header_synonym.findAll()` в карту, делегирует экстрактору.
  - `commit(ImportCommitRequest dto) : Tender` — (1) **апсертит синонимы**: для каждой пары `{header, field}` с `field != IGNORE` — `header_norm` upsert (по `findByHeaderNorm`: есть → обновить field, нет → создать); (2) `createFromLines(clientFacilityId, lines)`; возвращает созданный `Tender`. `@Transactional`.
- **`PrivateRequestImportController`** (`/api/private-requests/import`):
  - `POST /preview` (multipart `file`) → `ImportPreviewResponse`. `@PreAuthorize("hasRole('ADMIN')")`.
  - `POST /commit` (JSON `ImportCommitRequest`) → `PrivateRequestResponse` (переиспуем DTO/маппер блока B). `@PreAuthorize("hasRole('ADMIN')")`.
- **DTO:**
  - `ImportPreviewResponse { List<PreviewColumnResponse> columns; List<List<String>> rows; }`, `PreviewColumnResponse { int index; String header; LineField field; }`.
  - `ImportCommitRequest { Long clientFacilityId; List<ColumnMapping> mappings; List<PrivateLineRequest> lines; }`, `ColumnMapping { String header; LineField field; }`, `PrivateLineRequest { String name; String manufact; Integer quantity; }` (последнее — переиспуем строку из `PrivateRequestCreate` блока B, если совпадает).

**Multipart:** Spring Boot включает обработку multipart по умолчанию; `@RequestParam("file") MultipartFile file` — без доп. конфигурации. (В проекте загрузки файлов ещё не было — это первый multipart-эндпоинт.)

**Рыночный скоуп:** заявка создаётся через `createFromLines` → штампуется активный рынок листенером (как в блоке B). `header_synonym` — глобальная, рынок не нужен.

---

## 7. Frontend

- **Страница «Частные заявки»** — кнопка **«Импорт из файла»** рядом с «Создать».
- **Флоу импорта** (модал или отдельный роут `/private-requests/import`):
  1. **Выбор файла** (`<input type=file accept=".xlsx,.xls">`) → `POST /import/preview` (multipart) → `ImportPreviewResponse`.
  2. **Грид-превью:** над каждой колонкой `<select>` со значениями (Наименование / Бренд / Кол-во / Игнорировать), предзаполненный угаданным `field`; ниже — строки данных. Оператор правит разметку (тут и происходит обучение).
  3. **Клиент** — выбор существующего учреждения (facility), как в форме блока B.
  4. **Правка строк** — можно удалить мусорные строки (шапка-дубль, пустые).
  5. **«Создать заявку»** → собирает `lines` из грида по текущей разметке (колонки NAME→name, MANUFACT→manufact, QUANTITY→quantity; IGNORE отбрасывается) + `mappings` (header→field по каждой не-IGNORE колонке) → `POST /import/commit` → переход в карточку созданной заявки.
- **Валидация на фронте:** нет колонки с `field=NAME` → кнопка «Создать» заблокирована с подсказкой «отметьте колонку с наименованием».
- **ApiService:** `previewImport(file): Observable` (FormData), `commitImport(body): Observable`.

---

## 8. Обработка ошибок / edge

- Нет колонки `NAME` в разметке → commit невозможен (фронт блокирует; бэк тоже валидирует — 400 при отсутствии name-строк).
- Кол-во не парсится в число → дефолт `1`.
- Пустые строки (все ячейки пустые) → пропускаются при сборке `lines`.
- Неподдерживаемый формат файла / битый Excel → 400 с понятным сообщением.
- Ни один заголовок не узнан → все колонки `IGNORE`, оператор размечает руками; после commit правки сохранятся в `header_synonym` и в следующий раз узнаются.
- Дубль-синоним (тот же `header_norm` приходит с другим `field`) → апсерт (last-write-wins): последняя правка оператора побеждает.
- Заголовок пустой (колонка без шапки) → `header_norm` пустой, синоним не сохраняем (пропускаем при апсерте).

---

## 9. Тестирование

- **Unit (бэк):**
  - `RuleBasedLineExtractor`: `.xlsx` с шапкой «Наименование | Производитель | Кол-во» + словарём → колонки размечены NAME/MANUFACT/QUANTITY, строки разобраны; выученный синоним (напр. «Изделие»→NAME) из `learned` применяется; целое кол-во без `.0`.
  - `PrivateRequestImportService.commit`: апсертит новые синонимы (создаёт/обновляет `header_synonym`) и `createFromLines` создаёт заявку с правильными строками/брендами; повторный commit того же заголовка с другим полем — обновляет, не дублирует.
- **Frontend:** сборка `npm run build`; ручной e2e.
- **E2E-смоук (рынок KZ):** «Частные заявки» → «Импорт из файла» → загрузить пример `.xlsx` (наименование/производитель/кол-во) → превью показывает верную разметку → создать → открылась карточка заявки со строками; повторно загрузить файл с ранее размеченным вручную заголовком → он узнаётся автоматически.

---

## 10. Следующие шаги (после D1)

1. **D2 — живой приём из почты (IMAP):** опрос рабочего ящика → авто-черновики заявок из вложений тем же `LineExtractor` + `createFromLines`.
2. **LLM-`LineExtractor`** (опт-ин по API-ключу) — для совсем хаотичных файлов; правила остаются дефолтом/фолбэком.
3. CSV/PDF/вставка текста как форматы.
4. КП-генератор клиенту (НДС/происхождение), RF-реестр, Flyway.
