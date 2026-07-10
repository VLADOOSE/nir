# Ручной выбор победителя по лоту — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать оператору назначать победителя по лоту вручную (любого поставщика, не только самого дешёвого) из модалки «Сравнить предложения» → позиция в заявке.

**Architecture:** Новый `WinnerAssignmentService` + эндпоинт `POST /api/tenders/{id}/assign-winner` (get-or-create draft `ActivityApply`, upsert `ApplyItem` по лоту, `offeredCost=закупка`). Fix дефолта наценки в `ApplyAutoFillService` (25→0) чинит 2 теста. Frontend — кнопка «Назначить» + бейдж победителя в `app-offer-comparison`.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA, JUnit 5 + AssertJ (`@SpringBootTest @Transactional` на nirdb), Angular 21 standalone.

## Global Constraints

- Гард рынка через тендер: `tenderService.findById(id)` (аспект отсеет чужой рынок → `NotFoundException`) + явная сверка `tender.getMarket() != MarketContext.get()` → `NotFoundException` (defense-in-depth, паттерн `OfferComparisonService`).
- `ApplyItem` управлять через коллекцию `apply.getItems()` + `applyRepository.save(apply)` (cascade ALL + orphanRemoval — §7 CLAUDE.md), НЕ `applyItemRepository.delete`.
- `offeredCost = responsePrice × (1 + markup/100)`, `setScale(2, HALF_UP)`; markup null/<0 → 0. Записи `@PreAuthorize("hasRole('ADMIN')")`.
- Коммит-трейлер: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Тесты — Bash `dangerouslyDisableSandbox: true`, перед прогоном `lsof -ti :8080 | xargs kill -9`.

---

### Task 1: Fix дефолта наценки в `ApplyAutoFillService` (2 теста зеленеют)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/ApplyAutoFillService.java:45`
- Test: `src/test/java/com/vladoose/nir/service/ApplyAutoFillServiceTest.java` (существующий, не меняем — он уже ждёт цену без наценки)

**Interfaces:**
- Produces: `autoFill(Long applyId)` теперь = `autoFill(applyId, 0.0)` (было 25.0).

- [ ] **Step 1: Запустить 2 теста — убедиться, что падают (текущий дефолт 25%)**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.service.ApplyAutoFillServiceTest"`
Expected: FAIL — `autoFill_picksCheapestResponsePerLot` ждёт `offeredCost=850000`, получает `1062500` (850000×1.25); `autoFill_reportsLotsWithoutResponse` ждёт `720000`, получает `900000`.

- [ ] **Step 2: Поменять дефолт наценки на 0**

В `ApplyAutoFillService.java` метод без markup (строка ~45):

```java
    @Transactional
    public AutoFillResponse autoFill(Long applyId) {
        return autoFill(applyId, 0.0);   // дефолт без наценки: offeredCost = закупка (наценка — в offer-comparison / КП клиенту)
    }
```

- [ ] **Step 3: Запустить тесты — убедиться, что проходят**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.service.ApplyAutoFillServiceTest"`
Expected: PASS (3 теста зелёные).

- [ ] **Step 4: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/ApplyAutoFillService.java
git commit -m "fix(apply): дефолт наценки auto-fill 25→0 (offeredCost=закупка); чинит 2 теста

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `WinnerAssignmentService` + DTO + эндпоинт

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/AssignWinnerRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/AssignWinnerResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/WinnerAssignmentService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderController.java` (поле + ctor-параметр + эндпоинт)
- Test: `src/test/java/com/vladoose/nir/service/WinnerAssignmentServiceTest.java`

**Interfaces:**
- Consumes: `tenderService.findById(Long) → Tender`; `MarketContext.get()`; `NotFoundException(String)`, `BadRequestException(String)`; `priceRequestItemRepository.findByPriceRequestId(Long) → List<PriceRequestItem>`; `activityApplyRepository.findByTenderId(Long) → List<ActivityApply>`, `.save(ActivityApply)`.
- Produces: `WinnerAssignmentService.assignWinner(Long tenderId, Long lotId, Long priceRequestId, Double markupPercent) → AssignWinnerResponse`; `record AssignWinnerResponse(Long applyId, Long applyItemId, Long lotId, String distributorName, BigDecimal offeredCost)`.

- [ ] **Step 1: Написать падающий тест**

Create `src/test/java/com/vladoose/nir/service/WinnerAssignmentServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.AssignWinnerResponse;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class WinnerAssignmentServiceTest {

    @Autowired WinnerAssignmentService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired ActivityApplyRepository applyRepository;
    @Autowired ApplyItemRepository applyItemRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository priceRequestItemRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired FacilityRepository facilityRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private String u(String p) { return p + "-" + UUID.randomUUID().toString().substring(0, 8); }

    /** Создаёт тендер с 1 лотом и 1 КП-ответом цены; возвращает [tenderId, lotId, priceRequestId]. */
    private long[] fixture(BigDecimal price, boolean withEquip) {
        Facility fac = facilityRepository.save(Facility.builder().name(u("Клин")).build());
        Tender t = tenderRepository.save(Tender.builder().tenderNumber(u("T")).facility(fac).status("ACTIVE").build());
        TenderLot lot = TenderLot.builder().tender(t).lotNumber(1).equipName(u("Лот")).quantity(2).build();
        t.getLots().add(lot);
        t = tenderRepository.save(t);
        TenderLot savedLot = t.getLots().get(0);
        MedEquipment me = withEquip ? medEquipmentRepository.save(
                MedEquipment.builder().name(u("Обор")).manufact("M").build()) : null;
        Distributor d = distributorRepository.save(Distributor.builder().name(u("Дистр")).build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status("RESPONDED").build();
        pr.getItems().add(PriceRequestItem.builder()
                .priceRequest(pr).tenderLot(savedLot).medEquipment(me).requestedQuantity(1).responsePrice(price).build());
        pr = priceRequestRepository.save(pr);
        return new long[]{ t.getId(), savedLot.getId(), pr.getId() };
    }

    @Test
    void assign_createsDraftApply_andItem_withProcurementPrice() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(850_000), true);

        AssignWinnerResponse resp = service.assignWinner(f[0], f[1], f[2], null);

        assertThat(resp.applyId()).isNotNull();
        assertThat(resp.offeredCost()).isEqualByComparingTo("850000");   // закупка, без наценки
        ActivityApply apply = applyRepository.findById(resp.applyId()).orElseThrow();
        assertThat(apply.getStatus()).isEqualTo("DRAFT");
        List<ApplyItem> items = applyItemRepository.findByApplyId(resp.applyId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getTenderLot().getId()).isEqualTo(f[1]);
        assertThat(items.get(0).getQuantity()).isEqualTo(2);             // из lot.quantity
    }

    @Test
    void assign_reusesDraftApply_andUpsertsByLot_noDuplicate() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(900_000), true);
        // второй поставщик по тому же лоту, дешевле
        Distributor d2 = distributorRepository.save(Distributor.builder().name(u("Дистр2")).build());
        Tender t = tenderRepository.findById(f[0]).orElseThrow();
        TenderLot lot = lotRepository.findById(f[1]).orElseThrow();
        MedEquipment me2 = medEquipmentRepository.save(MedEquipment.builder().name(u("Обор2")).manufact("M").build());
        PriceRequest pr2 = PriceRequest.builder().tender(t).distributor(d2).status("RESPONDED").build();
        pr2.getItems().add(PriceRequestItem.builder().priceRequest(pr2).tenderLot(lot)
                .medEquipment(me2).requestedQuantity(1).responsePrice(BigDecimal.valueOf(800_000)).build());
        pr2 = priceRequestRepository.save(pr2);

        AssignWinnerResponse first = service.assignWinner(f[0], f[1], f[2], null);      // 900k поставщик
        AssignWinnerResponse second = service.assignWinner(f[0], f[1], pr2.getId(), null); // заменить на 800k

        assertThat(second.applyId()).isEqualTo(first.applyId());          // та же заявка
        List<ApplyItem> items = applyItemRepository.findByApplyId(first.applyId());
        assertThat(items).hasSize(1);                                     // upsert, не дубль
        assertThat(items.get(0).getOfferedCost()).isEqualByComparingTo("800000");
        assertThat(items.get(0).getDistributor().getId()).isEqualTo(d2.getId());
    }

    @Test
    void assign_manualOverride_picksNonCheapest() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(900_000), true);           // назначаем именно 900k (не минимум)
        AssignWinnerResponse resp = service.assignWinner(f[0], f[1], f[2], null);
        assertThat(resp.offeredCost()).isEqualByComparingTo("900000");
    }

    @Test
    void assign_appliesMarkup_whenProvided() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(1_000_000), true);
        AssignWinnerResponse resp = service.assignWinner(f[0], f[1], f[2], 20.0);
        assertThat(resp.offeredCost()).isEqualByComparingTo("1200000");  // ×1.2
    }

    @Test
    void assign_rejectsWhenNoEquipment() {
        MarketContext.set(Market.KZ);
        long[] f = fixture(BigDecimal.valueOf(500_000), false);          // med_equipment == null
        assertThatThrownBy(() -> service.assignWinner(f[0], f[1], f[2], null))
                .isInstanceOf(BadRequestException.class);
    }
}
```

- [ ] **Step 2: Запустить тест — убедиться, что не компилируется/падает**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.service.WinnerAssignmentServiceTest"`
Expected: FAIL — нет `WinnerAssignmentService`, `AssignWinnerResponse`.

- [ ] **Step 3: Создать `AssignWinnerRequest`**

Create `src/main/java/com/vladoose/nir/dto/request/AssignWinnerRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignWinnerRequest {
    @NotNull(message = "lotId обязателен")
    private Long lotId;
    @NotNull(message = "priceRequestId обязателен")
    private Long priceRequestId;
    private Double markupPercent;   // опционально; null → 0 (offeredCost = закупка)
}
```

- [ ] **Step 4: Создать `AssignWinnerResponse`**

Create `src/main/java/com/vladoose/nir/dto/response/AssignWinnerResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;

public record AssignWinnerResponse(
        Long applyId, Long applyItemId, Long lotId, String distributorName, BigDecimal offeredCost) {}
```

- [ ] **Step 5: Создать `WinnerAssignmentService`**

Create `src/main/java/com/vladoose/nir/service/WinnerAssignmentService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.AssignWinnerResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Ручное назначение победителя по лоту: выбранный поставщик из сравнения → позиция заявки. */
@Service
public class WinnerAssignmentService {

    private final TenderService tenderService;
    private final PriceRequestItemRepository priceRequestItemRepository;
    private final ActivityApplyRepository applyRepository;

    public WinnerAssignmentService(TenderService tenderService,
                                   PriceRequestItemRepository priceRequestItemRepository,
                                   ActivityApplyRepository applyRepository) {
        this.tenderService = tenderService;
        this.priceRequestItemRepository = priceRequestItemRepository;
        this.applyRepository = applyRepository;
    }

    @Transactional
    public AssignWinnerResponse assignWinner(Long tenderId, Long lotId, Long priceRequestId, Double markupPercent) {
        Tender tender = tenderService.findById(tenderId); // аспект отсеет чужой рынок; гард ниже — defense-in-depth
        if (tender.getMarket() != null && tender.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Тендер не найден: id=" + tenderId);
        }

        PriceRequestItem item = priceRequestItemRepository.findByPriceRequestId(priceRequestId).stream()
                .filter(i -> i.getTenderLot() != null && i.getTenderLot().getId().equals(lotId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Нет предложения поставщика по лоту"));
        if (item.getResponsePrice() == null) {
            throw new BadRequestException("У поставщика нет введённой цены по лоту");
        }
        if (item.getMedEquipment() == null) {
            throw new BadRequestException("У предложения нет привязки к каталогу оборудования — назначьте модель");
        }

        TenderLot lot = item.getTenderLot();
        double markup = markupPercent != null && markupPercent >= 0 ? markupPercent : 0.0;
        BigDecimal offered = item.getResponsePrice()
                .multiply(BigDecimal.valueOf(1.0 + markup / 100.0))
                .setScale(2, RoundingMode.HALF_UP);

        ActivityApply apply = applyRepository.findByTenderId(tenderId).stream()
                .filter(a -> "DRAFT".equals(a.getStatus()))
                .findFirst()
                .orElseGet(() -> ActivityApply.builder().tender(tender).status("DRAFT").build());

        ApplyItem target = apply.getItems().stream()
                .filter(ai -> ai.getTenderLot() != null && ai.getTenderLot().getId().equals(lotId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            target = ApplyItem.builder().apply(apply).tenderLot(lot).build();
            apply.getItems().add(target);
        }
        target.setMedEquipment(item.getMedEquipment());
        target.setDistributor(item.getPriceRequest().getDistributor());
        target.setOfferedCost(offered);
        target.setQuantity(lot.getQuantity());

        ActivityApply saved = applyRepository.save(apply); // cascade ALL сохранит/обновит item
        ApplyItem persisted = saved.getItems().stream()
                .filter(ai -> ai.getTenderLot() != null && ai.getTenderLot().getId().equals(lotId))
                .findFirst().orElse(target);

        return new AssignWinnerResponse(saved.getId(), persisted.getId(), lotId,
                item.getPriceRequest().getDistributor().getName(), offered);
    }
}
```

- [ ] **Step 6: Добавить эндпоинт в `TenderController`**

(a) Импорты (после существующих import DTO/сервисов):

```java
import com.vladoose.nir.dto.request.AssignWinnerRequest;
import com.vladoose.nir.dto.response.AssignWinnerResponse;
import com.vladoose.nir.service.WinnerAssignmentService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
```
(проверить, что `@Valid`/`@PreAuthorize` ещё не импортированы — если есть, не дублировать.)

(b) Поле + параметр конструктора. Добавить поле рядом с `offerComparisonService`:

```java
    private final WinnerAssignmentService winnerAssignmentService;
```
В сигнатуру конструктора добавить последним параметром `WinnerAssignmentService winnerAssignmentService`, в тело — `this.winnerAssignmentService = winnerAssignmentService;`.

(c) Эндпоинт (рядом с `offerComparison`):

```java
    /** Ручное назначение победителя по лоту (выбранный поставщик из сравнения → позиция заявки). */
    @PostMapping("/{id}/assign-winner")
    @PreAuthorize("hasRole('ADMIN')")
    public AssignWinnerResponse assignWinner(@PathVariable Long id, @Valid @RequestBody AssignWinnerRequest req) {
        return winnerAssignmentService.assignWinner(id, req.getLotId(), req.getPriceRequestId(), req.getMarkupPercent());
    }
```

- [ ] **Step 7: Запустить тест сервиса — зелёный**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.service.WinnerAssignmentServiceTest"`
Expected: PASS (5 тестов).

- [ ] **Step 8: Компиляция всего (контроллер) + коммит**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/java/com/vladoose/nir/dto/request/AssignWinnerRequest.java \
        src/main/java/com/vladoose/nir/dto/response/AssignWinnerResponse.java \
        src/main/java/com/vladoose/nir/service/WinnerAssignmentService.java \
        src/main/java/com/vladoose/nir/controller/TenderController.java \
        src/test/java/com/vladoose/nir/service/WinnerAssignmentServiceTest.java
git commit -m "feat(winner): WinnerAssignmentService + POST /tenders/{id}/assign-winner

Ручное назначение победителя по лоту: get-or-create draft-заявка, upsert ApplyItem
по лоту (offeredCost=закупка×(1+markup), markup дефолт 0), гарды нет-цены/нет-каталога/рынок.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Frontend — кнопка «Назначить» + бейдж победителя

**Files:**
- Modify: `frontend/src/app/services/api.service.ts` (метод `assignWinner`)
- Modify: `frontend/src/app/pages/tenders/offer-comparison.component.ts` (кнопка, бейдж, обработчик)

**Interfaces:**
- Consumes: `POST /api/tenders/{tenderId}/assign-winner {lotId, priceRequestId}` → `{applyId, applyItemId, lotId, distributorName, offeredCost}`.
- Produces: (UI-поведение) назначение победителя из модалки сравнения.

- [ ] **Step 1: Добавить метод в `ApiService`**

В `frontend/src/app/services/api.service.ts` рядом с `getOfferComparison` добавить:

```typescript
  assignWinner(tenderId: number, body: { lotId: number; priceRequestId: number; markupPercent?: number }): Observable<any> {
    return this.http.post<any>(`${this.base}/tenders/${tenderId}/assign-winner`, body);
  }
```

- [ ] **Step 2: Добавить кнопку + бейдж в ячейку таблицы**

В `offer-comparison.component.ts`, в `<td>` ячейки поставщика (блок со строк 36-43), заменить содержимое `<ng-container *ngIf="price(...) as p">` на версию с кнопкой и бейджем:

```html
                <td *ngFor="let s of data.suppliers"
                    [class.oc-best]="data.bestByLot[lot.lotId] === s.priceRequestId"
                    [class.oc-winner]="assignedByLot[lot.lotId] === s.priceRequestId">
                  <ng-container *ngIf="price(lot.lotId, s.priceRequestId) as p">
                    {{ p | number:'1.0-0' }} {{ sym }}
                    <small class="oc-marked">→ {{ withMarkup(p) | number:'1.0-0' }}</small>
                    <div class="oc-actions">
                      <span *ngIf="assignedByLot[lot.lotId] === s.priceRequestId" class="oc-badge">★ победитель</span>
                      <button *ngIf="assignedByLot[lot.lotId] !== s.priceRequestId" class="oc-assign"
                              (click)="assign(lot, s)">✓ Назначить</button>
                    </div>
                  </ng-container>
                  <span *ngIf="!price(lot.lotId, s.priceRequestId)">—</span>
                </td>
```

- [ ] **Step 3: Добавить ссылку «Открыть заявку» под таблицей**

После `</table>` (перед `</div>` закрытия `*ngIf="!loading"`) добавить:

```html
          <div class="oc-apply-link" *ngIf="assignedApplyId">
            Победители сохранены в заявку.
            <a [routerLink]="['/applies']" [queryParams]="{ openId: assignedApplyId }">Открыть заявку →</a>
          </div>
```

- [ ] **Step 4: Добавить стили, RouterLink-импорт, поля и метод `assign`**

(a) В `imports` компонента добавить `RouterLink`; импорт вверху файла:

```typescript
import { RouterLink } from '@angular/router';
```
Изменить строку `imports: [NgIf, NgFor, FormsModule, DecimalPipe],` → `imports: [NgIf, NgFor, FormsModule, DecimalPipe, RouterLink],`

(b) В массив `styles` добавить:

```css
    .oc-winner { background: #d1fae5; }
    .oc-actions { margin-top: 4px; }
    .oc-badge { color: #059669; font-weight: 600; font-size: 11px; }
    .oc-assign { font-size: 11px; padding: 2px 6px; border: 1px solid #059669; color: #059669; background: #fff; border-radius: 4px; cursor: pointer; }
    .oc-assign:hover { background: #ecfdf5; }
    .oc-apply-link { margin-top: 14px; font-size: 13px; }
    .oc-apply-link a { color: #2563eb; }
```

(c) Поля класса (рядом с `markup = 25;`):

```typescript
  assignedByLot: { [lotId: number]: number } = {};
  assignedApplyId: number | null = null;
```

(d) Метод `assign` (после `withMarkup`):

```typescript
  assign(lot: any, s: any) {
    if (this.tenderId == null) return;
    this.api.assignWinner(this.tenderId, { lotId: lot.lotId, priceRequestId: s.priceRequestId }).subscribe({
      next: (r) => {
        this.assignedByLot[lot.lotId] = s.priceRequestId;
        this.assignedApplyId = r.applyId;
        this.notify.success(`Назначен ${r.distributorName} по лоту №${lot.lotNumber || '—'}`);
        this.cdr.detectChanges();
      },
      error: (e) => this.notify.error('Не удалось назначить: ' + (e.error?.message || e.message)),
    });
  }
```

- [ ] **Step 5: Сборка фронта — зелёная**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: build SUCCESS (без ошибок TS/шаблона; уложиться в бюджет — `app-offer-comparison` свой стиль-бюджет).

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/services/api.service.ts \
        frontend/src/app/pages/tenders/offer-comparison.component.ts
git commit -m "feat(winner): кнопка «Назначить победителем» + бейдж в offer-comparison

Ячейка лот×поставщик → assignWinner; зелёный бейдж «★ победитель», смена по лоту
переставляет; ссылка «Открыть заявку». api.assignWinner.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- Эндпоинт + сервис (get-or-create, upsert, offeredCost=закупка) → Task 2 ✓.
- Гарды нет-цены/нет-каталога/рынок → Task 2 Step 5 ✓.
- Fix 2 тестов (дефолт 25→0) → Task 1 ✓.
- Frontend кнопка+бейдж+ссылка → Task 3 ✓.
- markup опционально (дефолт 0) → Task 2 (`markup null/<0 → 0`) + тест `assign_appliesMarkup_whenProvided` ✓.
- Ручной override не-минимума → тест `assign_manualOverride_picksNonCheapest` ✓.

**2. Placeholder scan:** нет TBD/«handle errors» — весь код приведён. ✓

**3. Type consistency:** `assignWinner(Long,Long,Long,Double) → AssignWinnerResponse`; `AssignWinnerResponse(applyId, applyItemId, lotId, distributorName, offeredCost)`; фронт `assignWinner(tenderId, {lotId, priceRequestId, markupPercent?})`; поля `assignedByLot`/`assignedApplyId` — согласованы между Task 2 и Task 3. ✓

**Примечание:** `quantity = lot.getQuantity()` (как auto-fill), не `requestedQuantity` из КП — сознательно (кол-во заявки = кол-во лота).
