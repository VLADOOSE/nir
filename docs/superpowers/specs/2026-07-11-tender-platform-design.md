# Дизайн: канал (площадка) тендера + кнопка «Открыть» по каналу

**Дата:** 2026-07-11
**Ветка:** `feat/tender-platform`
**Статус:** одобрено, реализация. Под-задача **A** из блока «два KZ-канала» (B — авто-импорт СК-Фармации — отдельно, после ресёрча/отца).

## Контекст и проблема

West-Med работает с ДВУМЯ KZ-каналами госзакупок медтехники:
1. **goszakup.gov.kz** — общий портал (наш импорт);
2. **СК-Фармация** — единый дистрибьютор (ГОБМП/ОСМС), портал **`fms.ecc.kz`** (оператор АО ЦЭФ), двухэтапный тендер, открытый HTML.

Кнопка «Открыть на площадке» на карточке тендера сейчас выбирается **по рынку** (`market.portalLink`: KZ→goszakup, RF→zakupki) — для всех KZ-тендеров ведёт на goszakup. Нужно: у каждого тендера кнопка ведёт на **его** площадку.

**Находка ресёрча:** URL-схема fms.ecc.kz **идентична goszakup** — `https://fms.ecc.kz/ru/announce/index/{id}?tab=lots` (движок «ecc»). Значит ссылка строится тем же способом, что для goszakup, только другой хост.

## Модель

Новое поле `Tender.platform` — enum `TenderPlatform` (`GOSZAKUP` / `SK_PHARMACY`), **nullable** (`@Enumerated(STRING)`, `@Column(length=20)`).
- **Без бэкфилла:** существующие KZ-тендеры (`platform=null`) → фолбэк по рынку (см. фронт) → ведут на goszakup, поведение не меняется.
- goszakup-импорт проставляет `GOSZAKUP` (для новых записей).
- Ручной СК-Ф тендер — оператор ставит `SK_PHARMACY`.
- RF-тендеры не трогаем (`platform=null` → фолбэк на zakupki).

V11-миграция: `ALTER TABLE tender ADD COLUMN platform VARCHAR(20);` (только колонка).

## Архитектура

### Backend
- `entity/TenderPlatform.java` — enum `GOSZAKUP, SK_PHARMACY`.
- `Tender.platform` (nullable enum).
- `db/migration/V11__tender_platform.sql` — add column.
- `TenderResponse.platform` (String) + `TenderRequest.platform` (String) + `TenderMapper` маппит enum↔String (как `source`).
- Goszakup-импорт (writer/upsert) ставит `platform = GOSZAKUP` при создании/обновлении (не затирая, если уже задан вручную — но импортные и так goszakup, ставим всегда GOSZAKUP на goszakup-путь).

### Frontend
`market.service` — портал-методы становятся **platform-aware** (platform необязателен; задан → перебивает рынок):

```
portalLabel(platform?)  : SK_PHARMACY→«СК-Фармация» | GOSZAKUP→«Госзакуп» | иначе рынок (KZ→Госзакуп, RF→ЕИС)
portalHost(platform?)   : SK_PHARMACY→fms.ecc.kz     | GOSZAKUP→goszakup.gov.kz | иначе рынок
portalLink(number, platform?):
   id = /^(\d+)-\d+$/ из number (как сейчас)
   SK_PHARMACY → https://fms.ecc.kz/ru/announce/index/{id}?tab=lots   (нет id → fms.ecc.kz/ru/searchanno)
   GOSZAKUP    → https://goszakup.gov.kz/ru/announce/index/{id}       (нет id → goszakup поиск лотов)
   иначе       → текущий фолбэк по рынку (RF→zakupki, KZ→goszakup)   ← RF не трогаем
```

`tenders.component` — хелперы принимают тендер:
- `eisLink(t)` → `market.portalLink(t.tenderNumber, t.platform)`
- `procurementPortalLabel(t)` → `market.portalLabel(t.platform)`
- `procurementPortalHost(t)` → `market.portalHost(t.platform)`
- шаблоны (список карточка ~стр.142, детальная ~стр.191) передают `t` / `selectedTender`.

**Форма тендера (create/edit).** Селектор «Площадка» (виден при рынке KZ): Госзакуп / СК-Фармация. `TenderRequest.platform` → сохраняется. Оператор вводит номер объявления fms.ecc.kz → кнопка ведёт на него.

## Модель данных

`tender` +колонка `platform VARCHAR(20)` (nullable). Больше ничего.

## Обработка ошибок / краевые

- `platform=null` → фолбэк по рынку (обратная совместимость, RF на zakupki).
- Номер без формата `\d+-\d+` (ручной «KZ-2026-…») → ссылка на поиск площадки (как сейчас для goszakup).
- Enum-значение вне {GOSZAKUP, SK_PHARMACY} в запросе → 400 (валидация маппера/парса enum).

## Тестирование (TDD)

Backend (`@SpringBootTest @Transactional`):
- create тендера с `platform=SK_PHARMACY` → персистится, `TenderResponse.platform="SK_PHARMACY"`;
- goszakup-импорт (upsert) → `platform=GOSZAKUP`;
- null-platform допустим (создание без platform).

Frontend: `npm run build` зелёный; **живая проверка (Playwright):** завести KZ-тендер с площадкой СК-Фармация (номер вида `363780-1`) → кнопка «Открыть в СК-Фармация» → `fms.ecc.kz/ru/announce/index/363780`; существующий goszakup-тендер 3122 → «Открыть в Госзакуп» → goszakup.

## Затрагиваемые файлы

- Create: `entity/TenderPlatform.java`, `db/migration/V11__tender_platform.sql`.
- Modify: `entity/Tender.java`, `dto/response/TenderResponse.java`, `dto/request/TenderRequest.java`, `mapper/TenderMapper.java`, goszakup-writer (upsert), `frontend/.../services/market.service.ts`, `frontend/.../pages/tenders/tenders.component.ts` (хелперы + шаблоны + селектор в форме).
- Test: backend — новый/расширенный тест платформы; goszakup-импорт-тест (если есть) проверить GOSZAKUP.

## YAGNI / вне scope

- **Без авто-импорта СК-Фармации** (отдельный блок B — скрейп fms.ecc.kz).
- Без бэкфилла существующих строк (фолбэк покрывает).
- Без отдельного «канал-чипа» на карточке (лейбл кнопки «Открыть в …» уже говорит канал).
- RF-логику (zakupki) не трогаем.

## Открытые вопросы

- Точное имя поля номера для fms.ecc.kz при ручном вводе: используем существующий `tenderNumber` (оператор вводит номер объявления «363780-1») — id извлекается тем же регексом. Отдельное поле не заводим.
