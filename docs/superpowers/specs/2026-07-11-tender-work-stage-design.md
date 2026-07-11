# Дизайн: воронка работы по тендеру (производная стадия + фильтр)

**Дата:** 2026-07-11
**Ветка:** `feat/tender-work-stage`
**Статус:** одобрено, реализация

## Контекст и проблема

Среди тендеров (особенно сотни импортных KZ) не видно **на каком этапе работы мы по каждому**: отправили ли запрос КП, пришли ли цены, выбрали ли победителя. Оператор просил: «когда запросили цену по лоту — чтобы фильтр можно было поставить типа "Сделан запрос"».

**Ключевое различие (определяет реализацию):** у тендера ДВА ортогональных статуса —
- **статус тендера** (ACTIVE «Приём заявок» / COMPLETED / CANCELLED) — состояние **на goszakup** (внешнее, уже есть, уже фильтруется);
- **наша стадия работы (воронка)** — где **мы** по тендеру (внутреннее).

Смешивать нельзя (тендер может быть ACTIVE на площадке, а наша стадия — «запрос отправлен»).

**Решение (одобрено):** **производная стадия** — вычисляется из данных, которые уже есть (`PriceRequest`/`PriceRequestItem.responsePrice`/`ActivityApply`), показывается чипом на карточке + новым фильтром. Без изменения схемы, без авто-создания заявки.

## Модель

Enum `WorkStage` (пакет `entity`), 4 стадии, максимально достигнутая:

| Стадия | Значение | Когда |
|---|---|---|
| ⚪ Не начат | `NOT_STARTED` | нет `PriceRequest` по тендеру |
| 🟡 Запрос отправлен | `REQUESTED` | есть КП, но нет ни одной `responsePrice` |
| 🔵 Есть цены | `PRICED` | есть ≥1 `PriceRequestItem.responsePrice` |
| 🟢 Победитель выбран | `WINNER_SELECTED` | есть `ActivityApply` с ≥1 позицией |

Заявка (`ActivityApply`) по-прежнему создаётся только при «Собрать из КП»/«Назначить победителя» — не на КП-запрос.

## Архитектура

### Backend — `TenderWorkStageService` (новый, `service`)

`stagesForMarket() → Map<Long, WorkStage>` — **3 пакетных агрегатных запроса** на весь рынок (не по строке — §11: список не должен считать по строке):

```
requested = tender_id, у которых есть PriceRequest          (множество A)
priced    = tender_id, у которых есть responsePrice != null (множество B)
winner    = tender_id, у которых есть ActivityApply с позицией (множество C)
```

Сборка (порядок = монотонность стадии, старшая перекрывает младшую):
`put REQUESTED для A → put PRICED для B → put WINNER_SELECTED для C`.
В карте — **только затронутые** тендеры (не-`NOT_STARTED`); отсутствие в карте = `NOT_STARTED` (маленький payload).

**Рынок — явным параметром** (не только аспект): `PriceRequestItem`/`TenderLot` НЕ market-scoped (нет `@Filter`), запрос по `PriceRequestItem` root аспект НЕ отфильтрует → фильтруем `pri.priceRequest.market = :market` явно (урок winner-гарда). Market берём из `MarketContext.get()`. Для `PriceRequest`/`ActivityApply` (market-scoped) явный фильтр — избыточен, но безвреден и единообразен.

Запросы (JPQL, `@Query` в репозиториях):
- `PriceRequestRepository.findTenderIdsWithPriceRequest(Market)` → `SELECT DISTINCT pr.tender.id FROM PriceRequest pr WHERE pr.market = :market`
- `PriceRequestRepository.findTenderIdsWithResponsePrice(Market)` → `SELECT DISTINCT pri.priceRequest.tender.id FROM PriceRequestItem pri WHERE pri.responsePrice IS NOT NULL AND pri.priceRequest.market = :market`
- `ActivityApplyRepository.findTenderIdsWithApplyItems(Market)` → `SELECT DISTINCT aa.tender.id FROM ActivityApply aa JOIN aa.items ai WHERE aa.market = :market`

Эндпоинт: `GET /api/tenders/work-stages` → `Map<Long, String>` (enum `.name()`). Read-only, без ADMIN. `@Transactional(readOnly=true)`.

### Frontend — `tenders.component.ts` + `api.service.ts`

- `api.getTenderWorkStages(): Observable<{[id:number]: string}>` → GET `/api/tenders/work-stages`.
- В `tenders.component`: при загрузке списка (и смене рынка) — один вызов, мерж в `stageByTenderId: {[id:number]: string}`.
- **Чип на карточке** тендера — цвет+подпись стадии, рядом со статусом площадки (оба видны). Чип показывается **только** для затронутых стадий (`REQUESTED`/`PRICED`/`WINNER_SELECTED`); `NOT_STARTED` → чипа нет (иначе шум на сотнях импортных).
- **Новый фильтр «Стадия»** `<select>` в существующей панели: Все / Не начат / Запрос отправлен / Есть цены / Победитель выбран. Клиентский, как `filterStatus` (в `applyTendersFilter()`): `filterStage=''` → все; `'NOT_STARTED'` → тендеры без записи в `stageByTenderId`; иначе → `stageByTenderId[id] === filterStage`.

Список уже грузит все тендеры рынка и фильтрует клиентски — паттерн тот же.

Подписи/цвета чипа: 🟡 «Запрос отправлен» (amber), 🔵 «Есть цены» (blue), 🟢 «Победитель» (green).

## Модель данных

Без изменений схемы. Только чтение существующих таблиц.

## Производительность

3 агрегатных `SELECT DISTINCT tender_id` (индексы по tender_id/market, market-фильтр) — O(число КП/заявок), не O(число тендеров). Один вызов на загрузку списка. Не грузим стадию в single-tender GET.

## Обработка ошибок

- Пустой рынок → пустая карта → все тендеры `NOT_STARTED`.
- Фронт: если `work-stages` не ответил — чипы/фильтр просто не показываются (не блокируем список); лог в консоль.

## Тестирование (TDD)

`TenderWorkStageServiceTest` (`@SpringBootTest @Transactional`):
- тендер без КП → отсутствует в карте (= NOT_STARTED);
- тендер с КП без цены → `REQUESTED`;
- тендер с ≥1 `responsePrice` → `PRICED`;
- тендер с `ActivityApply`+позицией → `WINNER_SELECTED`;
- монотонность: тендер, у которого есть и КП, и цена, и заявка → `WINNER_SELECTED` (старшая);
- изоляция рынка: КП другого рынка (RF) не влияет на карту KZ.

Живая проверка (Playwright): чип на карточке тендера 3122 (у него есть КП 505/506/507 → 🟡 «Запрос отправлен»), фильтр «Запрос отправлен» сужает список.

## Затрагиваемые файлы

- Create: `entity/WorkStage.java`, `service/TenderWorkStageService.java`, `test/.../TenderWorkStageServiceTest.java`.
- Modify: `repository/PriceRequestRepository.java` (+2 метода), `repository/ActivityApplyRepository.java` (+1 метод), `controller/TenderController.java` (эндпоинт + поле/ctor), `frontend/.../api.service.ts` (+метод), `frontend/.../tenders/tenders.component.ts` (мерж + чип + фильтр).

## YAGNI / вне scope

- Без стадии «Подано на площадку» (нет данных; добавим при автоподаче — её проясняет параллельный research по goszakup).
- Без авто-создания `ActivityApply`.
- Без изменения схемы/статуса тендера.
- Без «умного» дефолта фильтра (дефолт = Все стадии).

## Открытые вопросы / риски

- `CANCELLED` тендер с нашей стадией: оба чипа видны (площадка «Отменён» + наша стадия) — информативно, оставляем.
- Стадия кешируется на фронте на время просмотра; обновляется при перезагрузке списка/смене рынка (после отправки КП/назначения победителя оператор и так перезагружает). Авто-refresh после действия — в бэклог, если попросят.
