# Воронка работы по тендеру — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Показать и фильтровать тендеры по нашей стадии работы (не начат → запрос отправлен → есть цены → победитель выбран), вычисляемой из существующих данных.

**Architecture:** Backend `TenderWorkStageService` считает стадию пакетно (3 агрегатных JPQL-запроса, market явным параметром) и отдаёт `GET /api/tenders/work-stages` → `{tenderId: stage}`. Frontend мержит карту в список тендеров: чип на карточке + фильтр «Стадия». Без изменения схемы.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA/JPQL, JUnit 5 + AssertJ (`@SpringBootTest @Transactional` на nirdb), Angular 21 standalone.

## Global Constraints

- Стадия считается ПАКЕТНО (3 запроса на весь рынок), не по строке (§11 CLAUDE.md: список не считает по строке).
- Рынок — ЯВНЫМ параметром в запросах (`PriceRequestItem`/`TenderLot` НЕ market-scoped → аспект их не отфильтрует; урок winner-гарда). Market из `MarketContext.get()`.
- Стадии (enum values): `NOT_STARTED`, `REQUESTED`, `PRICED`, `WINNER_SELECTED`. Максимально достигнутая перекрывает младшую.
- ⚠️ **Style-бюджет `tenders.component` = 15.96/16 kB (error ceiling)** → чип стадии ТОЛЬКО инлайн-стилем (`[style]`), НЕ CSS-классами (иначе `npm run build` упадёт с budget-error). Фильтр использует существующий класс `.filter-select` (новый CSS не добавляем).
- Коммит-трейлер: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Тесты — Bash `dangerouslyDisableSandbox: true`, перед прогоном `lsof -ti :8080 | xargs kill -9`.

---

### Task 1: Backend — `WorkStage` + `TenderWorkStageService` + эндпоинт

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/WorkStage.java`
- Create: `src/main/java/com/vladoose/nir/service/TenderWorkStageService.java`
- Modify: `src/main/java/com/vladoose/nir/repository/PriceRequestRepository.java` (+2 метода)
- Modify: `src/main/java/com/vladoose/nir/repository/ActivityApplyRepository.java` (+1 метод)
- Modify: `src/main/java/com/vladoose/nir/controller/TenderController.java` (поле + ctor + эндпоинт)
- Test: `src/test/java/com/vladoose/nir/service/TenderWorkStageServiceTest.java`

**Interfaces:**
- Consumes: `MarketContext.get() → Market`; `PriceRequest{tender, market, items}`, `PriceRequestItem{priceRequest, responsePrice}`, `ActivityApply{tender, market, items}`.
- Produces: `TenderWorkStageService.stagesForMarket() → Map<Long, WorkStage>` (только затронутые тендеры); enum `WorkStage{NOT_STARTED, REQUESTED, PRICED, WINNER_SELECTED}`; `GET /api/tenders/work-stages → Map<Long, String>`.

- [ ] **Step 1: Написать падающий тест**

Create `src/test/java/com/vladoose/nir/service/TenderWorkStageServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenderWorkStageServiceTest {

    @Autowired TenderWorkStageService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired ActivityApplyRepository applyRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired FacilityRepository facilityRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private String u(String p) { return p + "-" + UUID.randomUUID().toString().substring(0, 8); }

    private Tender newTender() {
        Facility fac = facilityRepository.save(Facility.builder().name(u("Клин")).build());
        return tenderRepository.save(Tender.builder().tenderNumber(u("T")).facility(fac).status("ACTIVE").build());
    }

    private TenderLot lot(Tender t) {
        TenderLot lot = TenderLot.builder().tender(t).lotNumber(1).equipName(u("Лот")).quantity(1).build();
        t.getLots().add(lot);
        return tenderRepository.save(t).getLots().get(0);
    }

    private PriceRequest kp(Tender t, TenderLot lot, BigDecimal price) {
        Distributor d = distributorRepository.save(Distributor.builder().name(u("Дистр")).build());
        MedEquipment me = medEquipmentRepository.save(MedEquipment.builder().name(u("Обор")).manufact("M").build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status("SENT").build();
        pr.getItems().add(PriceRequestItem.builder()
                .priceRequest(pr).tenderLot(lot).medEquipment(me).requestedQuantity(1).responsePrice(price).build());
        return priceRequestRepository.save(pr);
    }

    @Test
    void notStarted_absentFromMap() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        assertThat(service.stagesForMarket()).doesNotContainKey(t.getId());
    }

    @Test
    void kpWithoutPrice_isRequested() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        kp(t, lot(t), null);   // КП без цены
        assertThat(service.stagesForMarket().get(t.getId())).isEqualTo(WorkStage.REQUESTED);
    }

    @Test
    void kpWithPrice_isPriced() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        kp(t, lot(t), BigDecimal.valueOf(100000));
        assertThat(service.stagesForMarket().get(t.getId())).isEqualTo(WorkStage.PRICED);
    }

    @Test
    void applyWithItem_isWinnerSelected() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        TenderLot l = lot(t);
        MedEquipment me = medEquipmentRepository.save(MedEquipment.builder().name(u("Обор")).manufact("M").build());
        Distributor d = distributorRepository.save(Distributor.builder().name(u("Дистр")).build());
        ActivityApply apply = ActivityApply.builder().tender(t).status("DRAFT").build();
        apply.getItems().add(ApplyItem.builder().apply(apply).tenderLot(l).medEquipment(me)
                .distributor(d).offeredCost(BigDecimal.valueOf(100000)).quantity(1).build());
        applyRepository.save(apply);
        assertThat(service.stagesForMarket().get(t.getId())).isEqualTo(WorkStage.WINNER_SELECTED);
    }

    @Test
    void monotonic_highestStageWins() {
        MarketContext.set(Market.KZ);
        Tender t = newTender();
        TenderLot l = lot(t);
        kp(t, l, BigDecimal.valueOf(100000));     // есть цена → PRICED
        MedEquipment me = medEquipmentRepository.save(MedEquipment.builder().name(u("Обор")).manufact("M").build());
        Distributor d = distributorRepository.save(Distributor.builder().name(u("Дистр")).build());
        ActivityApply apply = ActivityApply.builder().tender(t).status("DRAFT").build();
        apply.getItems().add(ApplyItem.builder().apply(apply).tenderLot(l).medEquipment(me)
                .distributor(d).offeredCost(BigDecimal.valueOf(100000)).quantity(1).build());
        applyRepository.save(apply);              // + заявка → WINNER_SELECTED старше
        assertThat(service.stagesForMarket().get(t.getId())).isEqualTo(WorkStage.WINNER_SELECTED);
    }

    @Test
    void marketIsolation_rfKpNotInKzMap() {
        MarketContext.set(Market.RF);
        Tender rf = newTender();
        kp(rf, lot(rf), BigDecimal.valueOf(100000));
        MarketContext.set(Market.KZ);
        Map<Long, WorkStage> kzMap = service.stagesForMarket();
        assertThat(kzMap).doesNotContainKey(rf.getId());
    }
}
```

- [ ] **Step 2: Запустить тест — не компилируется/падает**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.service.TenderWorkStageServiceTest"`
Expected: FAIL — нет `WorkStage`, `TenderWorkStageService`.

- [ ] **Step 3: Создать enum `WorkStage`**

Create `src/main/java/com/vladoose/nir/entity/WorkStage.java`:

```java
package com.vladoose.nir.entity;

/** Наша стадия работы по тендеру (воронка) — производная, не хранится. */
public enum WorkStage {
    NOT_STARTED,      // нет КП по тендеру
    REQUESTED,        // есть КП, но нет ни одной responsePrice
    PRICED,           // есть ≥1 responsePrice
    WINNER_SELECTED   // есть ActivityApply с ≥1 позицией
}
```

- [ ] **Step 4: Добавить методы в `PriceRequestRepository`**

В `src/main/java/com/vladoose/nir/repository/PriceRequestRepository.java` добавить (внутри интерфейса; проверить, что `Market` и `@Query`/`@Param` импортированы — если нет, добавить `import com.vladoose.nir.entity.Market;`, `import org.springframework.data.jpa.repository.Query;`, `import org.springframework.data.repository.query.Param;`, `import java.util.List;`):

```java
    @Query("SELECT DISTINCT pr.tender.id FROM PriceRequest pr WHERE pr.market = :market")
    List<Long> findTenderIdsWithPriceRequest(@Param("market") Market market);

    @Query("SELECT DISTINCT pri.priceRequest.tender.id FROM PriceRequestItem pri "
            + "WHERE pri.responsePrice IS NOT NULL AND pri.priceRequest.market = :market")
    List<Long> findTenderIdsWithResponsePrice(@Param("market") Market market);
```

- [ ] **Step 5: Добавить метод в `ActivityApplyRepository`**

В `src/main/java/com/vladoose/nir/repository/ActivityApplyRepository.java` добавить (импорты `Market`/`@Query`/`@Param`/`List` при необходимости):

```java
    @Query("SELECT DISTINCT aa.tender.id FROM ActivityApply aa JOIN aa.items ai WHERE aa.market = :market")
    List<Long> findTenderIdsWithApplyItems(@Param("market") com.vladoose.nir.entity.Market market);
```

- [ ] **Step 6: Создать `TenderWorkStageService`**

Create `src/main/java/com/vladoose/nir/service/TenderWorkStageService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.WorkStage;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/** Стадия работы по тендеру (воронка), вычисляемая пакетно из существующих данных. */
@Service
public class TenderWorkStageService {

    private final PriceRequestRepository priceRequestRepository;
    private final ActivityApplyRepository applyRepository;

    public TenderWorkStageService(PriceRequestRepository priceRequestRepository,
                                  ActivityApplyRepository applyRepository) {
        this.priceRequestRepository = priceRequestRepository;
        this.applyRepository = applyRepository;
    }

    /** tenderId → стадия; только затронутые тендеры (отсутствие = NOT_STARTED). */
    @Transactional(readOnly = true)
    public Map<Long, WorkStage> stagesForMarket() {
        Market market = MarketContext.get();
        Map<Long, WorkStage> map = new HashMap<>();
        // порядок = монотонность: старшая стадия перекрывает младшую
        for (Long id : priceRequestRepository.findTenderIdsWithPriceRequest(market)) map.put(id, WorkStage.REQUESTED);
        for (Long id : priceRequestRepository.findTenderIdsWithResponsePrice(market)) map.put(id, WorkStage.PRICED);
        for (Long id : applyRepository.findTenderIdsWithApplyItems(market)) map.put(id, WorkStage.WINNER_SELECTED);
        return map;
    }
}
```

- [ ] **Step 7: Добавить эндпоинт в `TenderController`**

(a) Импорты (рядом с прочими service-импортами):

```java
import com.vladoose.nir.service.TenderWorkStageService;
import com.vladoose.nir.entity.WorkStage;
import java.util.Map;
import java.util.stream.Collectors;
```
(проверить: `java.util.Map`/`Collectors` могут быть уже импортированы — не дублировать.)

(b) Поле рядом с `winnerAssignmentService`:

```java
    private final TenderWorkStageService workStageService;
```
В сигнатуру конструктора добавить последним параметром `TenderWorkStageService workStageService`, в тело — `this.workStageService = workStageService;`.

(c) Эндпоинт (рядом с `assignWinner`):

```java
    /** Стадия воронки по каждому тендеру рынка (tenderId → стадия). Только затронутые тендеры. */
    @GetMapping("/work-stages")
    public Map<Long, String> workStages() {
        return workStageService.stagesForMarket().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().name()));
    }
```

- [ ] **Step 8: Запустить тест — зелёный**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.service.TenderWorkStageServiceTest"`
Expected: PASS (6 тестов).

- [ ] **Step 9: Компиляция + коммит**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/java/com/vladoose/nir/entity/WorkStage.java \
        src/main/java/com/vladoose/nir/service/TenderWorkStageService.java \
        src/main/java/com/vladoose/nir/repository/PriceRequestRepository.java \
        src/main/java/com/vladoose/nir/repository/ActivityApplyRepository.java \
        src/main/java/com/vladoose/nir/controller/TenderController.java \
        src/test/java/com/vladoose/nir/service/TenderWorkStageServiceTest.java
git commit -m "feat(work-stage): WorkStage + TenderWorkStageService + GET /tenders/work-stages

Пакетная стадия воронки (NOT_STARTED/REQUESTED/PRICED/WINNER_SELECTED) из существующих
данных (3 агрегатных JPQL, market явным параметром); только затронутые тендеры в карте.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Frontend — чип стадии + фильтр «Стадия»

**Files:**
- Modify: `frontend/src/app/services/api.service.ts` (метод `getTenderWorkStages`)
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts` (поля, загрузка, чип, фильтр)

**Interfaces:**
- Consumes: `GET /api/tenders/work-stages → {[tenderId: number]: string}` из Task 1.
- Produces: (UI) чип стадии на карточке + фильтр «Стадия» в панели.

- [ ] **Step 1: Метод в `ApiService`**

В `frontend/src/app/services/api.service.ts` рядом с `getTenders` добавить:

```typescript
  getTenderWorkStages(): Observable<{ [tenderId: number]: string }> {
    return this.http.get<{ [tenderId: number]: string }>(`${this.base}/tenders/work-stages`);
  }
```

- [ ] **Step 2: Поля + загрузка стадий**

В `tenders.component.ts` рядом с `filterStatus = '';` (строка ~836) добавить:

```typescript
  filterStage = '';
  stageByTenderId: { [id: number]: string } = {};
```

В `loadTenders()` (строка ~1060) — грузить стадии после тендеров:

```typescript
  loadTenders() {
    this.api.getTenders().subscribe({
      next: data => { this.tenders = data; this.applyTendersFilter(); this.cdr.detectChanges(); },
      error: err => this.notify.error('Ошибка загрузки тендеров: ' + (err.error?.message || err.message))
    });
    this.api.getTenderWorkStages().subscribe({
      next: (m) => { this.stageByTenderId = m || {}; this.applyTendersFilter(); this.cdr.detectChanges(); },
      error: () => { /* стадии не критичны — список работает без них */ }
    });
  }
```

- [ ] **Step 3: Хелперы стадии (label + инлайн-стиль чипа)**

В классе `tenders.component.ts` (рядом с `getStatusLabel`, или в конце класса) добавить:

```typescript
  stageLabel(code: string): string {
    return { REQUESTED: 'Запрос отправлен', PRICED: 'Есть цены', WINNER_SELECTED: 'Победитель' }[code] || '';
  }

  // Инлайн-стиль (НЕ CSS-класс: style-бюджет tenders.component исчерпан, §12 CLAUDE.md)
  stageChipStyle(code: string): string {
    const c: { [k: string]: string } = {
      REQUESTED: 'background:#fef3c7;color:#92400e',
      PRICED: 'background:#dbeafe;color:#1e40af',
      WINNER_SELECTED: 'background:#d1fae5;color:#065f46',
    };
    return (c[code] || 'background:#e5e7eb;color:#374151')
      + ';display:inline-block;padding:2px 8px;border-radius:10px;font-size:11px;font-weight:600;margin-left:6px';
  }
```

- [ ] **Step 4: Чип на карточке тендера**

В шаблоне после статус-бейджа (строка ~139: `<span class="badge" [class]="'badge-' + t.status">{{ getStatusLabel(t.status) }}</span>`) добавить чип стадии:

```html
              <span class="badge" [class]="'badge-' + t.status">{{ getStatusLabel(t.status) }}</span>
              <span *ngIf="stageByTenderId[t.id]" [style]="stageChipStyle(stageByTenderId[t.id])">{{ stageLabel(stageByTenderId[t.id]) }}</span>
```

- [ ] **Step 5: Фильтр «Стадия» в панели**

После блока сортировки `sortMode` (строка ~35-38, `<select ... sortMode ...>`) добавить новый select (использует существующий класс `.filter-select`):

```html
        <select [(ngModel)]="filterStage" (change)="applyTendersFilter()" class="filter-select" title="Стадия работы">
          <option value="">Все стадии</option>
          <option value="NOT_STARTED">Не начат</option>
          <option value="REQUESTED">Запрос отправлен</option>
          <option value="PRICED">Есть цены</option>
          <option value="WINNER_SELECTED">Победитель выбран</option>
        </select>
```

- [ ] **Step 6: Логика фильтра в `applyTendersFilter`**

В `applyTendersFilter()` (строка ~1110) после строки `if (this.filterStatus && t.status !== this.filterStatus) return false;` добавить:

```typescript
      if (this.filterStage) {
        const stage = this.stageByTenderId[t.id] || 'NOT_STARTED';
        if (stage !== this.filterStage) return false;
      }
```

- [ ] **Step 7: Сброс фильтра**

В `resetTendersFilter()` (строка ~1349, рядом с `this.filterStatus = '';`) добавить:

```typescript
    this.filterStage = '';
```

- [ ] **Step 8: Сборка фронта — зелёная (в т.ч. бюджет стилей)**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: `Application bundle generation complete`. ⚠️ Проверить, что НЕ появилось `anyComponentStyle ... maximumError` по `tenders.component` (мы не добавляли CSS — чип инлайн). Warning про 8kB — пред-существующий, ок.

- [ ] **Step 9: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/services/api.service.ts \
        frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(work-stage): чип стадии на карточке тендера + фильтр «Стадия»

getTenderWorkStages → stageByTenderId; чип (инлайн-стиль, style-бюджет) рядом со
статусом площадки; фильтр «Стадия» в панели (клиентский, как filterStatus).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- Enum 4 стадии → Task 1 Step 3 ✓.
- 3 пакетных запроса, market явным параметром → Task 1 Steps 4-6 ✓.
- Эндпоинт work-stages → Task 1 Step 7 ✓.
- Чип на карточке (только затронутые) → Task 2 Step 4 (`*ngIf="stageByTenderId[t.id]"`) ✓.
- Фильтр «Стадия» (клиентский, NOT_STARTED = отсутствие в карте) → Task 2 Steps 5-6 ✓.
- Перф (пакетно) → Task 1 Step 6 ✓. Style-бюджет (инлайн-чип) → Task 2 Step 3-4 ✓.
- Тесты: derivation + монотонность + изоляция рынка → Task 1 Step 1 ✓.

**2. Placeholder scan:** нет TBD/«handle errors» — весь код приведён. ✓

**3. Type consistency:** `stagesForMarket() → Map<Long, WorkStage>`; репо-методы `findTenderIdsWith*(Market) → List<Long>`; эндпоинт `Map<Long, String>`; фронт `stageByTenderId: {[id]: string}`, `filterStage: string`, `stageLabel/stageChipStyle(code)` — согласованы между Task 1 и Task 2. ✓
