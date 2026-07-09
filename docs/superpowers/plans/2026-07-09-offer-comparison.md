# Сравнение предложений поставщиков (часть 3a) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read-only сводка по тендеру — матрица лоты×поставщики с ценами ответов КП, подсветкой минимума по лоту и итогами по поставщикам, в модалке из карточки тендера.

**Architecture:** `OfferComparisonService.build(tenderId)` пивотит существующие `PriceRequest`+`items` тендера в DTO (лоты/поставщики/ячейки/мин-по-лоту/итоги) — только поставщики и лоты, где есть введённые `responsePrice`. `GET /api/tenders/{id}/offer-comparison` (гард рынка через тендер). Фронт — отдельный standalone-компонент `app-offer-comparison` (свой style-бюджет, не трогает 16 kB карточки), кнопка «Сравнить предложения» в секции «Запросы КП». Схема БД не меняется.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA, JUnit 5, Angular 21 (standalone), Lombok.

## Global Constraints

- **Спек:** `docs/superpowers/specs/2026-07-09-offer-comparison-design.md` (источник истины).
- **Read-only:** только чтение существующих `PriceRequest.responsePrice`; НЕТ новых полей/миграций; схема БД не меняется.
- **Матрица без пустот:** `suppliers` = PR с ≥1 позицией с непустой `responsePrice`; `lots` = лоты с ≥1 ценой; `bestByLot` = PR с мин. `responsePrice` по лоту (тай-брейк — меньший `priceRequestId`); `totalsBySupplier` = Σ(`responsePrice × quantity`).
- **Гард рынка (§6 CLAUDE.md):** тендер через `tenderService.findById` (== `em.find`, минует hibernate-фильтр) → `NotFoundException`, если `tender.getMarket() != MarketContext.get()`. Эталон — `LotSourcingService`/`setProposedEquipment`.
- **OSIV:** `PriceRequest.items` LAZY → сервис под HTTP-OSIV / `@Transactional`-тест (сессия открыта, как везде). `tender`/`distributor` — EAGER.
- **Кнопка** «Сравнить предложения» видна при ≥2 КП с ≥1 введённой `responsePrice` (данные уже в `priceRequests` карточки).
- **БД/сборка:** `./gradlew` — с `dangerouslyDisableSandbox: true`; kill lingering `lsof -ti :8080 | xargs kill -9`. git/gradlew из корня репо. Фронт-гейт — `cd frontend && npm run build`.
- **Тест-гейт (§13 CLAUDE.md):** зелёный = падают ТОЛЬКО 2 пред-существующих `ApplyAutoFillServiceTest`.
- **Коммиты:** ветка `feat/offer-comparison` (создана); каждый заканчивать `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **Субагенты — наследуют модель сессии (§2 CLAUDE.md), `model` не переопределять.**

## File Structure

**Создаём:**
- `src/main/java/com/vladoose/nir/dto/response/OfferComparisonResponse.java` — DTO (вложенные Lot/Supplier/Cell).
- `src/main/java/com/vladoose/nir/service/OfferComparisonService.java` — пивот/мин/итоги.
- `src/test/java/com/vladoose/nir/pricerequest/OfferComparisonServiceTest.java`
- `frontend/src/app/pages/tenders/offer-comparison.component.ts` — standalone-модалка.

**Меняем:**
- `src/main/java/com/vladoose/nir/controller/TenderController.java` — эндпоинт + инъекция сервиса.
- `frontend/src/app/services/api.service.ts` — `getOfferComparison`.
- `frontend/src/app/pages/tenders/tenders.component.ts` — кнопка + тег `<app-offer-comparison>` + состояние + import.
- `CLAUDE.md` — §8/§15 (финальная задача).

---

### Task 1: Бэкенд — DTO + `OfferComparisonService` + эндпоинт

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/OfferComparisonResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/OfferComparisonService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderController.java`
- Test: `src/test/java/com/vladoose/nir/pricerequest/OfferComparisonServiceTest.java`

**Interfaces:**
- Consumes: `PriceRequestRepository.findByTenderId(Long)`, `TenderService.findById` (== em.find), `MarketContext.get()`, `PriceRequest.getItems()/getDistributor()/getStatus()`, `PriceRequestItem.getTenderLot()/getResponsePrice()/getRequestedQuantity()`, `TenderLot.getId()/getLotNumber()/getEquipName()`.
- Produces:
  - `OfferComparisonResponse { List<Lot> lots; List<Supplier> suppliers; List<Cell> cells; Map<Long,Long> bestByLot; Map<Long,BigDecimal> totalsBySupplier; }`
    - `Lot { Long lotId; Integer lotNumber; String lotName; Integer quantity; }`
    - `Supplier { Long priceRequestId; String distributorName; String status; }`
    - `Cell { Long lotId; Long priceRequestId; BigDecimal responsePrice; Integer quantity; }`
  - `OfferComparisonService.build(Long tenderId) → OfferComparisonResponse`.
  - `GET /api/tenders/{id}/offer-comparison`.

- [ ] **Step 1: DTO `OfferComparisonResponse`**

```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferComparisonResponse {
    private List<Lot> lots;
    private List<Supplier> suppliers;
    private List<Cell> cells;
    private Map<Long, Long> bestByLot;                 // lotId → priceRequestId (мин. цена)
    private Map<Long, BigDecimal> totalsBySupplier;    // priceRequestId → Σ(price×qty)

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Lot { private Long lotId; private Integer lotNumber; private String lotName; private Integer quantity; }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Supplier { private Long priceRequestId; private String distributorName; private String status; }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Cell { private Long lotId; private Long priceRequestId; private BigDecimal responsePrice; private Integer quantity; }
}
```

- [ ] **Step 2: Failing-тест сервиса**

```java
package com.vladoose.nir.pricerequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.OfferComparisonResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.OfferComparisonService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class OfferComparisonServiceTest {

    @Autowired OfferComparisonService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender tender() {
        return tenderRepository.save(Tender.builder()
                .tenderNumber("T-" + System.nanoTime()).status("NEW").market(Market.KZ).build());
    }
    private TenderLot lot(Tender t, int num, String name) {
        return lotRepository.save(TenderLot.builder().tender(t).lotNumber(num).equipName(name).quantity(2).build());
    }
    private Distributor dist(String name) {
        return distributorRepository.save(Distributor.builder().name(name + System.nanoTime()).market(Market.KZ).build());
    }
    private PriceRequest pr(Tender t, Distributor d, List<PriceRequestItem> items) {
        PriceRequest p = PriceRequest.builder().tender(t).distributor(d).market(Market.KZ).status("RESPONDED").build();
        items.forEach(i -> i.setPriceRequest(p));
        p.setItems(items);
        return priceRequestRepository.save(p);
    }
    private PriceRequestItem item(TenderLot lot, String price) {
        return PriceRequestItem.builder().tenderLot(lot).requestedQuantity(lot.getQuantity())
                .responsePrice(price == null ? null : new BigDecimal(price)).build();
    }

    @Test
    void pivotsWithBestPerLotAndTotals() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        TenderLot l1 = lot(t, 1, "УЗИ"), l2 = lot(t, 2, "Рентген");
        Distributor a = dist("A"), b = dist("B");
        PriceRequest pa = pr(t, a, List.of(item(l1, "100"), item(l2, "300")));  // A: l1=100, l2=300
        PriceRequest pb = pr(t, b, List.of(item(l1, "120"), item(l2, "250")));  // B: l1=120, l2=250

        OfferComparisonResponse r = service.build(t.getId());

        assertThat(r.getLots()).extracting(OfferComparisonResponse.Lot::getLotId)
                .containsExactlyInAnyOrder(l1.getId(), l2.getId());
        assertThat(r.getSuppliers()).extracting(OfferComparisonResponse.Supplier::getPriceRequestId)
                .containsExactlyInAnyOrder(pa.getId(), pb.getId());
        // мин по лоту: l1 → A(100), l2 → B(250)
        assertThat(r.getBestByLot().get(l1.getId())).isEqualTo(pa.getId());
        assertThat(r.getBestByLot().get(l2.getId())).isEqualTo(pb.getId());
        // итоги: A = 100*2 + 300*2 = 800; B = 120*2 + 250*2 = 740
        assertThat(r.getTotalsBySupplier().get(pa.getId())).isEqualByComparingTo("800");
        assertThat(r.getTotalsBySupplier().get(pb.getId())).isEqualByComparingTo("740");
    }

    @Test
    void tieBreakSmallerPriceRequestId() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        TenderLot l1 = lot(t, 1, "УЗИ");
        PriceRequest pa = pr(t, dist("A"), List.of(item(l1, "100")));
        PriceRequest pb = pr(t, dist("B"), List.of(item(l1, "100"))); // равная цена
        OfferComparisonResponse r = service.build(t.getId());
        assertThat(r.getBestByLot().get(l1.getId())).isEqualTo(Math.min(pa.getId(), pb.getId()));
    }

    @Test
    void excludesSuppliersAndLotsWithoutPrices() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        TenderLot l1 = lot(t, 1, "УЗИ"), l2 = lot(t, 2, "Рентген");
        PriceRequest priced = pr(t, dist("A"), List.of(item(l1, "100"), item(l2, null))); // l2 без цены
        PriceRequest empty = pr(t, dist("B"), List.of(item(l1, null)));                    // без цен вовсе
        OfferComparisonResponse r = service.build(t.getId());
        // поставщик без цен исключён
        assertThat(r.getSuppliers()).extracting(OfferComparisonResponse.Supplier::getPriceRequestId)
                .containsExactly(priced.getId());
        // лот l2 (без единой цены) исключён
        assertThat(r.getLots()).extracting(OfferComparisonResponse.Lot::getLotId).containsExactly(l1.getId());
    }

    @Test
    void emptyWhenNoResponses() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        TenderLot l1 = lot(t, 1, "УЗИ");
        pr(t, dist("A"), List.of(item(l1, null)));
        OfferComparisonResponse r = service.build(t.getId());
        assertThat(r.getLots()).isEmpty();
        assertThat(r.getSuppliers()).isEmpty();
        assertThat(r.getCells()).isEmpty();
    }

    @Test
    void foreignMarketRejected() {
        MarketContext.set(Market.KZ);
        Tender t = tender();
        Long id = t.getId();
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.build(id)).isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 3: Прогнать — падает**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.pricerequest.OfferComparisonServiceTest'` (sandbox off).
Expected: FAIL — класса `OfferComparisonService` нет.

- [ ] **Step 4: Реализовать `OfferComparisonService`**

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.OfferComparisonResponse;
import com.vladoose.nir.dto.response.OfferComparisonResponse.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.PriceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** Сводка предложений по тендеру: матрица лоты×поставщики, мин. цена по лоту, итоги. Read-only. */
@Service
public class OfferComparisonService {

    private final TenderService tenderService;
    private final PriceRequestRepository priceRequestRepository;

    public OfferComparisonService(TenderService tenderService, PriceRequestRepository priceRequestRepository) {
        this.tenderService = tenderService;
        this.priceRequestRepository = priceRequestRepository;
    }

    @Transactional(readOnly = true)
    public OfferComparisonResponse build(Long tenderId) {
        Tender tender = tenderService.findById(tenderId); // em.find обходит фильтр рынка
        if (tender.getMarket() != null && tender.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Тендер не найден: id=" + tenderId);
        }

        List<PriceRequest> prs = priceRequestRepository.findByTenderId(tenderId);

        List<Supplier> suppliers = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        LinkedHashMap<Long, Lot> lotsById = new LinkedHashMap<>();

        for (PriceRequest pr : prs) {
            boolean anyPriced = false;
            BigDecimal total = BigDecimal.ZERO;
            for (PriceRequestItem it : pr.getItems()) {
                if (it.getResponsePrice() == null || it.getTenderLot() == null) continue;
                anyPriced = true;
                TenderLot lot = it.getTenderLot();
                int qty = it.getRequestedQuantity() != null ? it.getRequestedQuantity() : 1;
                lotsById.putIfAbsent(lot.getId(),
                        new Lot(lot.getId(), lot.getLotNumber(), lot.getEquipName(), it.getRequestedQuantity()));
                cells.add(new Cell(lot.getId(), pr.getId(), it.getResponsePrice(), it.getRequestedQuantity()));
                total = total.add(it.getResponsePrice().multiply(BigDecimal.valueOf(qty)));
            }
            if (anyPriced) {
                suppliers.add(new Supplier(pr.getId(), pr.getDistributor().getName(), pr.getStatus()));
                totals.put(pr.getId(), total);
            }
        }

        Map<Long, Long> bestByLot = new LinkedHashMap<>();
        Map<Long, BigDecimal> bestPriceByLot = new HashMap<>();
        for (Cell c : cells) {
            BigDecimal cur = bestPriceByLot.get(c.getLotId());
            int cmp = cur == null ? -1 : c.getResponsePrice().compareTo(cur);
            if (cmp < 0 || (cmp == 0 && c.getPriceRequestId() < bestByLot.get(c.getLotId()))) {
                bestPriceByLot.put(c.getLotId(), c.getResponsePrice());
                bestByLot.put(c.getLotId(), c.getPriceRequestId());
            }
        }

        return new OfferComparisonResponse(new ArrayList<>(lotsById.values()), suppliers, cells, bestByLot, totals);
    }
}
```

> ⚠ Файл-паттерн (§14 CLAUDE.md): после написания — `./gradlew compileJava`.

- [ ] **Step 5: Эндпоинт в `TenderController`**

Заинжектить `OfferComparisonService offerComparisonService` (поле + конструктор + импорт `com.vladoose.nir.service.OfferComparisonService`, `com.vladoose.nir.dto.response.OfferComparisonResponse`). Добавить метод рядом с `lotSourcing`:
```java
    /** Сводка предложений поставщиков по тендеру (матрица лоты×поставщики, мин. цена по лоту). */
    @GetMapping("/{id}/offer-comparison")
    public OfferComparisonResponse offerComparison(@PathVariable Long id) {
        return offerComparisonService.build(id);
    }
```

- [ ] **Step 6: Прогнать — зелёный + регресс**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricerequest.OfferComparisonServiceTest'` (sandbox off).
Expected: PASS (5). Затем `./gradlew test` — падают ТОЛЬКО 2 `ApplyAutoFillServiceTest`.

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/response/OfferComparisonResponse.java src/main/java/com/vladoose/nir/service/OfferComparisonService.java src/main/java/com/vladoose/nir/controller/TenderController.java src/test/java/com/vladoose/nir/pricerequest/OfferComparisonServiceTest.java && git commit -m "feat(offer-comparison): OfferComparisonService + GET /api/tenders/{id}/offer-comparison (пивот, мин по лоту, итоги)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Фронт — модалка `app-offer-comparison` + кнопка + проводка

**Files:**
- Create: `frontend/src/app/pages/tenders/offer-comparison.component.ts`
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `GET /api/tenders/{id}/offer-comparison` (Task 1), `MarketService.symbol()`, `NotificationService`.
- Produces: `ApiService.getOfferComparison(tenderId)`; `<app-offer-comparison [tenderId] (close)>`; кнопка «Сравнить предложения» + `compareTenderId` в карточке; геттер `canCompare()`.

- [ ] **Step 1: `ApiService` — метод**

Добавить (рядом с `getPriceRequestsByTender`):
```ts
  getOfferComparison(tenderId: number): Observable<any> {
    return this.http.get<any>(`${this.base}/tenders/${tenderId}/offer-comparison`);
  }
```

- [ ] **Step 2: Компонент-модалка `offer-comparison.component.ts`**

```ts
import { Component, EventEmitter, Input, Output, OnChanges, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';

@Component({
  selector: 'app-offer-comparison',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="oc-overlay" *ngIf="tenderId != null" (click)="onOverlay($event)">
      <div class="oc-window" (click)="$event.stopPropagation()">
        <div class="oc-head">
          <h2>Сравнение предложений</h2>
          <button class="oc-close" (click)="close.emit()">&times;</button>
        </div>
        <div *ngIf="loading" class="oc-loading">Загрузка…</div>
        <div *ngIf="!loading">
          <div class="oc-controls">
            <label>Наценка: <input type="number" [(ngModel)]="markup" min="0" class="oc-markup" /> %</label>
            <span class="oc-hint">Зелёным — минимальная цена по лоту. «с наценкой» = цена × (1 + наценка/100).</span>
          </div>
          <div class="oc-empty" *ngIf="!data || !data.lots?.length">Нет ответов с ценами для сравнения.</div>
          <table class="oc-table" *ngIf="data && data.lots?.length">
            <thead>
              <tr>
                <th>Лот</th>
                <th *ngFor="let s of data.suppliers">{{ s.distributorName }}</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let lot of data.lots">
                <td class="oc-lot">№{{ lot.lotNumber || '—' }} {{ lot.lotName }} <small>×{{ lot.quantity }}</small></td>
                <td *ngFor="let s of data.suppliers"
                    [class.oc-best]="data.bestByLot[lot.lotId] === s.priceRequestId">
                  <ng-container *ngIf="price(lot.lotId, s.priceRequestId) as p">
                    {{ p | number:'1.0-0' }} {{ sym }}
                    <small class="oc-marked">→ {{ withMarkup(p) | number:'1.0-0' }}</small>
                  </ng-container>
                  <span *ngIf="!price(lot.lotId, s.priceRequestId)">—</span>
                </td>
              </tr>
              <tr class="oc-totals">
                <td>Итого</td>
                <td *ngFor="let s of data.suppliers">
                  {{ (data.totalsBySupplier[s.priceRequestId] || 0) | number:'1.0-0' }} {{ sym }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .oc-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
    .oc-window { background: #fff; border-radius: 10px; padding: 20px; width: min(960px, 94vw); max-height: 88vh; overflow: auto; }
    .oc-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
    .oc-close { background: none; border: none; font-size: 24px; cursor: pointer; color: #6b7280; }
    .oc-loading, .oc-empty { color: #6b7280; padding: 20px 0; }
    .oc-controls { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; flex-wrap: wrap; }
    .oc-markup { width: 64px; padding: 5px 8px; border: 1px solid #d1d5db; border-radius: 6px; }
    .oc-hint { color: #6b7280; font-size: 12px; }
    .oc-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .oc-table th, .oc-table td { border: 1px solid #e5e7eb; padding: 7px 10px; text-align: left; }
    .oc-table thead th { background: #f9fafb; }
    .oc-lot { max-width: 320px; }
    .oc-best { background: #ecfdf5; font-weight: 600; }
    .oc-marked { color: #6b7280; }
    .oc-totals td { background: #f3f4f6; font-weight: 600; }
  `],
})
export class OfferComparisonComponent implements OnChanges {
  @Input() tenderId: number | null = null;
  @Output() close = new EventEmitter<void>();

  data: any = null;
  loading = false;
  markup = 25;
  sym = '';

  constructor(private api: ApiService, private notify: NotificationService,
              private market: MarketService, private cdr: ChangeDetectorRef) {
    this.sym = this.market.symbol();
  }

  ngOnChanges() {
    if (this.tenderId == null) { this.data = null; return; }
    this.loading = true; this.cdr.detectChanges();
    this.api.getOfferComparison(this.tenderId).subscribe({
      next: (r) => { this.data = r; this.loading = false; this.cdr.detectChanges(); },
      error: (e) => { this.loading = false; this.notify.error('Ошибка сравнения: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); },
    });
  }

  onOverlay(_: Event) { this.close.emit(); }

  price(lotId: number, prId: number): number | null {
    const c = (this.data?.cells || []).find((x: any) => x.lotId === lotId && x.priceRequestId === prId);
    return c ? Number(c.responsePrice) : null;
  }
  withMarkup(p: number): number { return p * (1 + (Number(this.markup) || 0) / 100); }
}
```

> ⚠ `NumberPipe` (`| number`) требует `DecimalPipe`. В `imports` компонента добавить `DecimalPipe` из `@angular/common` (иначе NG8004 на сборке — тот же кейс, что был в шаблоне КП). Т.е. `imports: [NgIf, NgFor, FormsModule, DecimalPipe]` + импорт `DecimalPipe`.

- [ ] **Step 3: Проводка в `tenders.component.ts`**

- В импорты компонента (строка ~11) добавить: `import { OfferComparisonComponent } from './offer-comparison.component';`
- В `@Component imports` (строка ~18) добавить `OfferComparisonComponent`.
- Рядом с `<app-bulk-price-modal ...>` (строка ~175) добавить тег:
```html
      <app-offer-comparison [tenderId]="compareTenderId" (close)="compareTenderId = null"></app-offer-comparison>
```
- Объявить состояние (рядом с `priceRequests`): `compareTenderId: number | null = null;`
- Геттер видимости кнопки (в классе):
```ts
  get canCompare(): boolean {
    const withPrice = (this.priceRequests || []).filter((pr: any) =>
      (pr.items || []).some((it: any) => it.responsePrice != null));
    return withPrice.length >= 2;
  }
```
- Кнопку в шапку секции «Запросы КП» (строки ~543-545), рядом с «Проверить ответы»:
```html
          <button class="btn btn-line" *ngIf="canCompare" (click)="compareTenderId = selectedTender.id">Сравнить предложения</button>
```

- [ ] **Step 4: Сборка фронта**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`.
Expected: BUILD SUCCESS. Компонент `offer-comparison` — свой style-бюджет (не влияет на 16 kB `tenders.component`); карточка получила лишь кнопку + 1 тег.

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/tenders/offer-comparison.component.ts frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts && git commit -m "feat(offer-comparison): модалка сравнения предложений + кнопка «Сравнить» + наценка

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Живая проверка (Playwright) + CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (§8, §15)

- [ ] **Step 1: Поднять стек**

Backend: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew bootRun` (sandbox off, фон). Frontend: `cd frontend && npm start` (фон). Дождаться :8080/:4200.

- [ ] **Step 2: Подготовить данные + Playwright**

Нужен KZ-тендер с ≥2 КП, у которых введены `responsePrice` по ≥1 общему лоту. Если такого нет — создать быстро: на тендере с несколькими поставщиками отправить КП двум (или переиспользовать существующие КП), развернуть карточку КП → в «Калькулятор наценки» вписать «Цена ответа» по лотам у ДВУХ поставщиков → «Сохранить ответы». Затем кнопка **«Сравнить предложения»** в секции «Запросы КП» → модалка: матрица лоты×поставщики, у каждого лота **зелёным** подсвечена минимальная цена, строка «Итого» по поставщикам корректна; сменить «Наценка %» → «с наценкой» пересчитывается. Снять скриншот `offer-comparison.png`.
> Если подготовка данных в UI затянется — можно вбить `responsePrice` напрямую в БД (`UPDATE price_request_item SET response_price=… WHERE …`) для двух КП одного тендера, затем проверить модалку. Живой результат — матрица с подсветкой минимума.

- [ ] **Step 3: Обновить CLAUDE.md**

§8: добавить пункт про сравнение предложений (модалка `app-offer-comparison`, `GET /api/tenders/{id}/offer-comparison` → `OfferComparisonService`: пивот лоты×поставщики из `responsePrice`, мин по лоту зелёным, итоги, единый контрол наценки; кнопка при ≥2 КП с ценами; read-only). §15 API: `/api/tenders/{id}/offer-comparison` (GET). Отметить в §16, что часть 3a сделана; 3b (авто-парс цены) — следующая.

- [ ] **Step 4: Commit + финиш**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md offer-comparison.png 2>/dev/null; git add CLAUDE.md; git commit -m "docs: CLAUDE.md — сравнение предложений поставщиков (часть 3a)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Затем — whole-branch review (superpowers:requesting-code-review) и finishing-a-development-branch (merge --ff-only в main, удалить ветку).

---

## Self-Review (выполнено при написании)

- **Покрытие спека:** §4 DTO+сервис+эндпоинт+фильтр suppliers/lots+bestByLot+totals+гард → Task 1; §5 модалка+кнопка+наценка+условие ≥2 с ценами → Task 2; §6 тесты (пивот/тай-брейк/исключение пустых/пустой/гард) → Task 1 Step 2 + живая → Task 3; §7 без миграций — соблюдено.
- **Отклонения:** нет.
- **Плейсхолдеры:** нет; весь код конкретный. `DecimalPipe`-нюанс явно отмечен (NG8004).
- **Согласованность типов:** `OfferComparisonResponse{lots,suppliers,cells,bestByLot,totalsBySupplier}` + вложенные `Lot/Supplier/Cell` (Task 1) ↔ фронт читает `data.lots/suppliers/cells/bestByLot/totalsBySupplier`, `lot.lotId/lotNumber/lotName/quantity`, `s.priceRequestId/distributorName`, `c.lotId/priceRequestId/responsePrice` (Task 2); `build(Long)→OfferComparisonResponse` (Task 1) ↔ эндпоинт ↔ `getOfferComparison` (Task 2); `canCompare` геттер ↔ кнопка.
- **Риск (отмечен):** `PriceRequest.items` LAZY — сервис `@Transactional(readOnly=true)`, тест `@Transactional` (сессия открыта). Фронт `getPriceRequestsByTender` уже отдаёт items с `responsePrice` (для `canCompare`).
