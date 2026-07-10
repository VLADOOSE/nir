# Дизайн: ручной выбор победителя по лоту

**Дата:** 2026-07-10
**Ветка:** `feat/manual-winner-selection`
**Статус:** одобрено, реализация

## Контекст и проблема

Поток «отправка КП → ответы → цены» доведён: `PriceRequestItem.responsePrice` заполняется (вручную/авто-парсом ч.3b), `offer-comparison` показывает матрицу лоты×поставщики + `bestByLot` (мин. цена), `ApplyAutoFillService` умеет «Собрать из КП» — по каждому лоту берёт **авто-минимум** цены → `ApplyItem` (лот + дистрибьютор-победитель + `offeredCost` + сплит-заказ), UI `/applies` (кнопка «Собрать из КП», модалка наценки, PDF) готов.

**Пробел:** победитель определяется ТОЛЬКО как самый дешёвый. Оператор не может выбрать другого поставщика (дороже, но лучше сроки/качество/надёжность), хотя в `offer-comparison` всех видит. Нет моста «сравнение → заявка» для ручного назначения.

**Решение:** в модалке «Сравнить предложения» — кнопка «✓ Назначить победителем» у каждой ячейки лот×поставщик. Кладёт выбранного поставщика по лоту в заявку. Переиспользуем существующую модель (`ActivityApply`/`ApplyItem`), новую схему НЕ вводим.

## Границы (scope)

**В scope:**
- Backend: эндпоинт + сервис «назначить победителя по лоту» (get-or-create draft-заявка, upsert позиции по лоту).
- Fix: 2 устаревших `ApplyAutoFillServiceTest` (ассерты без наценки vs дефолт 25%).
- Frontend: кнопка «Назначить» + подсветка победителя в `app-offer-comparison`, ссылка на заявку.

**НЕ в scope (YAGNI):**
- Не трогаем существующую auto-fill UI-модалку наценки (работает).
- Не вводим схему БД (переиспуем `ApplyItem`).
- Нет массового «назначить всех разом» — авто-минимум уже покрыт кнопкой «Собрать из КП».
- Наценку при ручном назначении НЕ применяем (offeredCost = закупка; см. решение ниже).

## Ключевое решение: offeredCost = закупка без наценки

При ручном назначении `offeredCost = responsePrice` победителя (markup дефолт 0). Наценка живёт в одном месте (контрол наценки в `offer-comparison` / будущее КП клиенту), а не размазана по позициям заявки.

**Следствие для тестов:** `ApplyAutoFillService.autoFill(applyId)` дефолт markup `25.0 → 0.0`. 2 теста (`autoFill_picksCheapestResponsePerLot`, `autoFill_reportsLotsWithoutResponse`) ждут `offeredCost` без наценки (850 000 / 720 000) → зеленеют без правки ассертов. UI auto-fill всегда шлёт markup явно (модалка) → его поведение НЕ меняется; оператор по-прежнему может добавить наценку через «Собрать из КП».

## Архитектура

### Backend — `WinnerAssignmentService` (новый, `service`) + эндпоинт

`POST /api/tenders/{tenderId}/assign-winner` body `{lotId, priceRequestId, markupPercent?}` (`@PreAuthorize ADMIN`).

`assignWinner(tenderId, lotId, priceRequestId, markupPercent) → AssignWinnerResponse`:
1. **Гард рынка** через тендер (аспект + явная сверка `tender.market`, defense-in-depth — как `OfferComparisonService`).
2. Найти `PriceRequestItem` по `priceRequestId` + `lotId` (`PriceRequestItemRepository`). Нет такого / `responsePrice == null` → `BadRequestException` (400) «нет цены поставщика по лоту». `medEquipment == null` (КП частной заявки без каталога) → 400 «нет привязки к каталогу оборудования».
3. **Get-or-create** draft `ActivityApply` для тендера: первый со `status == "DRAFT"` из `applyRepository.findByTenderId(...)`, иначе создать новый (`status=DRAFT`, market — стампится листенером).
4. **Upsert** `ApplyItem` по (apply, lotId): найти существующий в `apply.getItems()` с этим `tenderLot.id` → обновить `distributor/medEquipment/offeredCost/quantity`; иначе создать. `offeredCost = responsePrice × (1 + markup/100)` (markup: null/<0 → 0), `quantity = lot.getQuantity()`.
5. Вернуть `AssignWinnerResponse {applyId, applyItemId, lotId, distributorName, offeredCost}`.

Отдельный `@Transactional`-бин (не self-invoke). Через коллекцию `apply.getItems()` (cascade ALL + orphanRemoval — правило §7 CLAUDE.md: управлять лотами/items через коллекцию, не `repository.delete`).

### Fix — `ApplyAutoFillService`

Строка `autoFill(applyId)` → `return autoFill(applyId, 0.0);` (было `25.0`). Одна строка. Ничего в UI/логике авто-минимума больше не трогаем.

### Frontend — `app-offer-comparison`

В матрице лоты×поставщики у каждой ячейки с `responsePrice` — маленькая кнопка «✓ Назначить». Клик → `api.assignWinner(tenderId, {lotId, priceRequestId})` → при успехе:
- пометить ячейку зелёным бейджем «★ победитель» (локально `assignedByLot[lotId] = priceRequestId`);
- смена по тому же лоту переставляет бейдж;
- тост «Назначен <distributorName> по лоту N»;
- показать ссылку «Открыть заявку →» (`/applies?openId=<applyId>`), `applyId` из ответа.

`Cell {lotId, priceRequestId, responsePrice}` уже несёт всё нужное — DTO `offer-comparison` не меняется. Стиль — в бюджете `app-offer-comparison` (отдельный компонент, не трогает 16 kB карточки).

## Модель данных

Без изменений схемы. `ApplyItem` уже: `apply`, `tenderLot`, `medEquipment`, `distributor`, `offeredCost`, `quantity`.

## Обработка ошибок

- Нет цены/нет `PriceRequestItem` по лоту → 400.
- `medEquipment == null` → 400 (нельзя построить позицию без каталога — как `missing` в auto-fill).
- Чужой рынок → фильтр аспекта не найдёт тендер → 404 (+ явная сверка).
- Фронт: ошибку показать через `notify.error(err.error?.message)`.

## Тестирование (TDD)

`WinnerAssignmentServiceTest` (`@SpringBootTest @Transactional`, паттерн `ApplyAutoFillServiceTest`):
- **get-or-create:** нет заявки → создаётся draft + позиция; повторный вызов по другому лоту → та же заявка, 2 позиции.
- **upsert-замена:** назначить победителя по лоту, потом другого по тому же лоту → 1 позиция, распределитель/цена обновлены (не дубль).
- **offeredCost = закупка:** markup не передан → `offeredCost == responsePrice` (без ×1.25).
- **не самый дешёвый:** два ответа 850k/900k, назначить 900k → в заявке 900k-поставщик (доказательство ручного override поверх авто-минимума).
- **гарды:** лот без цены → 400; `medEquipment == null` → 400.
- **markup опционально:** markup=20 → `offeredCost == responsePrice × 1.2`.

Плюс починенные 2 `ApplyAutoFillServiceTest` (зеленеют от дефолта 0.0). Живая проверка (Playwright) на тендере 3122: назначить поставщика в модалке сравнения → бейдж + позиция в `/applies`.

## Затрагиваемые файлы

- Create: `service/WinnerAssignmentService.java`, `dto/request/AssignWinnerRequest.java`, `dto/response/AssignWinnerResponse.java`, `test/.../WinnerAssignmentServiceTest.java`.
- Modify: `controller/TenderController.java` (эндпоинт `assign-winner`), `service/ApplyAutoFillService.java` (дефолт 0.0), `frontend/.../offer-comparison.component.ts` (кнопка+бейдж), `frontend/.../api.service.ts` (`assignWinner`).

## Открытые вопросы / риски

- Несколько draft-заявок на тендер: берём первую DRAFT. Приемлемо (обычно одна). При росте — выбор заявки в UI (в бэклог).
- offer-comparison открывается в контексте тендера → `tenderId` у модалки есть (`compareTenderId`).
