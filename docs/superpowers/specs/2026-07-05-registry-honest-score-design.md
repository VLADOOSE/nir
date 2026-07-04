# Честная score-семантика в панели «Реестр» — дизайн

**Дата:** 2026-07-05. **Ветка:** `feat/registry-honest-score`. **Статус:** одобрен («ок»).

## 1. Проблема (исследование проведено)

Панель «Реестр» у лота показывает «похожесть %». Для одно-словных имён лота
(«Центрифуга») запрос — один токен; `word_similarity('центрифуга', 'Центрифуга …') = 1.0`
для ВСЕХ вхождений → 5 записей по **100%**, что вводит в заблуждение: это не «идеальная
модель», а «в названии есть слово». Замер подтвердил (6 центрифуг — все 1.00).

Реестр НЦЭЛС физически без габаритов/веса (колонки `reg_number, name, producer, country,
reg_date, expiration_date, unlimited`) → ранжировать по габаритам/весу нельзя, это справочник
допуска (РУ), не техкаталог. Различающие характеристики только в ТЗ лота; обогащение из ТЗ
(`LotQueryTokenizer` + `characteristics`, уже в main) разводит оценки (61%/59% вместо 100%),
но требует разобранного ТЗ.

Цель: (1) не показывать врущие 100%, когда различающего сигнала нет; (2) подсказать «разберите
ТЗ», когда лот одно-словный и ТЗ не разобрано.

## 2. Бэкенд

- **`computeLotMatch(TenderLot lot, int limit)`** (приватный, `RegistryMatchService`) — общая логика
  матча, возвращает `record LotMatch(List<RegistryCandidateResponse> candidates, boolean distinctive)`:
  - бренд задан (`manufact` непуст) → бренд-путь `findCandidates`, `distinctive = true`;
  - иначе токены `LotQueryTokenizer.tokenize(equipName, characteristics(requiredSpec))`;
    пусто → фолбэк `findCandidates(equipName, null)`, `distinctive = false`;
  - иначе `searchByTokens`; **`distinctive = tokens.size() >= 2`** (≥2 значимых токена различают
    записи; 1 токен = совпадение только по названию, % врёт).
- `candidatesForLot(lotId, limit): List<RegistryCandidateResponse>` (для `LotSourcingService`) —
  делегирует `computeLotMatch(...).candidates()`. Контракт НЕ меняется.
- **`matchForLotUi(lotId, limit): LotRegistryMatchResponse`** (новый публичный) —
  `{ List<RegistryCandidateResponse> candidates; boolean distinctive; boolean techSpecParsed }`,
  где `techSpecParsed = TechSpecExtractor.characteristics(lot.getRequiredSpec()) != null`.
- **DTO** `LotRegistryMatchResponse` (`dto/response`, Lombok `@Data`): 3 поля выше.
- **Эндпоинт** `GET /api/lots/{id}/registry-candidates` (`TenderLotController`) возвращает
  `LotRegistryMatchResponse` вместо `List<RegistryCandidateResponse>` (единственный UI-потребитель —
  панель реестра). `limit` капится 20 как сейчас.

## 3. Фронт (`tenders.component.ts`, registry-panel)

- `ApiService.getLotRegistryCandidates` — тип ответа `any` (объект вместо массива).
- `onLotRegistry` кладёт `registryPanel = { lot, loading, items: resp.candidates, distinctive:
  resp.distinctive, techSpecParsed: resp.techSpecParsed }`.
- **score-badge:** `distinctive` → процент `scorePct(c)%` как сейчас (класс `score-good` при ≥0.35);
  `!distinctive` → нейтральная метка **«✓ по названию»** (без числа, серый бейдж).
- **Баннер-подсказка** над таблицей: `!distinctive && !techSpecParsed && isImportedTender()` →
  «Совпадение только по названию — модели в реестре неразличимы. Нажмите «ТЗ», чтобы разбор
  техспецификации уточнил подбор.»
- **Пояснение в шапке панели** (постоянно, мелким): «Реестр НЦЭЛС — допуск (№ РУ); габариты/вес
  здесь не хранятся, ранжирование — по совпадению наименования.»
- Логика колонки «Похожесть» → «Соответствие» (заголовок нейтральнее).

## 4. Тесты и live

- **Интеграционные** (`RegistryMatchUiTest`, живой реестр): одно-словный лот («Центрифуга», ТЗ
  не разобрано) → `distinctive=false, techSpecParsed=false`; лот с разобранным ТЗ (характеристики
  дают токены) → `distinctive=true, techSpecParsed=true`; лот с `manufact` → `distinctive=true`;
  лот с многословным именем («Дефибриллятор монитор бифазный») → `distinctive=true`. Кандидаты
  непусты во всех.
- Регресс: `LotSourcingService`/`candidatesForLot` не сломаны (тот же `List`).
- **Гейты:** `./gradlew test` (только 2 известных), `npm run build`.
- **Live (Playwright):** центрифуга (тендер 17282821-1) — сбросить разбор нельзя, поэтому взять
  свежий одно-словный импортный лот без разобранного ТЗ → «Реестр»: метки «✓ по названию» + баннер
  «разберите ТЗ»; затем «ТЗ» → «Реестр»: проценты, баннер исчез. Рентген-лот 852 (ТЗ разобрано,
  многословный) → проценты как сейчас.

## 5. Вне скоупа

- Ранжирование реестра по габаритам/весу — невозможно (нет данных); это «Подобрать» по каталогу.
- LLM-подтверждение «РУ подходит под ТЗ».
- Изменение `LotSourcingService`-порога (score-шкала там уже отмечена в §8 CLAUDE.md).

## 6. Порядок работ

1. Бэкенд: `LotMatch`/`computeLotMatch` рефактор + `matchForLotUi` + DTO + эндпоинт + тесты (TDD).
2. Фронт: обёртка ответа + метка «по названию» + баннер + пояснение шапки.
3. Гейт + live + CLAUDE.md + ревью + мерж.

Гочи: миграций нет; `candidatesForLot(List)` — контракт для LotSourcing неизменен; субагенты — Fable 5.
