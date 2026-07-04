# «Взять из реестра в работу» + чистка UI карточки тендера — план имплементации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Кнопка «Взять в работу» в панели «Реестр» превращает реестр-кандидата в позицию каталога + предложенную модель лота и открывает КП-панель; карточка тендера чистится от мёртвых полей/кнопок/колонок.

**Architecture:** `RegistryMatchService.adoptForLot` (@Transactional: гард рынка лота → РУ → дедуп по `registration.regNumber` → create/reuse `MedEquipment` REGISTERED → `lot.setProposedEquipment`) + `POST /api/lots/{id}/adopt-registry`. Фронт: кнопка в registry-panel → тост → КП-панель; правило «пусто — не рисуем» для инфо-полей и колонок лотов; каталожные кнопки — по критериям.

**Tech Stack:** Java 17 / Spring Boot 3.5.6, Angular 21. Без миграций и новых зависимостей.

**Spec:** `docs/superpowers/specs/2026-07-04-registry-adopt-ui-design.md`.

## Global Constraints

- Ветка: `feat/registry-adopt-ui` (активна). Sandbox off для `./gradlew`/psql; перед полным прогоном `lsof -ti :8080 | xargs kill -9 || true`.
- Гейт: `./gradlew test` — только 2 известных `ApplyAutoFillServiceTest`; `cd frontend && npm run build`.
- Миграций НЕТ; `@FilterDef` не трогаем; правка полей лота через save — безопасно; дедуп-finder ДОЛЖЕН выполняться под `@Transactional` (рыночный фильтр аспектом).
- `med_equipment.name/manufact` — `VARCHAR(255) NOT NULL` → обрезка 255, producer null → «не указан».
- Тестовые данные — префикс `ZZ`; `MarketContext.set(...)` + `@AfterEach clear()`.
- Trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Субагенты — Fable 5.

---

### Task 1: Бэкенд — `adoptForLot` + эндпоинт (TDD)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/RegistryMatchService.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/AdoptRegistryRequest.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderLotController.java`
- Test: `src/test/java/com/vladoose/nir/service/RegistryAdoptTest.java`

**Interfaces:**
- Produces: `RegistryMatchService.adoptForLot(Long lotId, String regNumber): TenderLot`;
  `MedEquipmentRepository.findFirstByRegistrationRegNumber(String): Optional<MedEquipment>`;
  `POST /api/lots/{id}/adopt-registry` `{regNumber}` (ADMIN) → `TenderLotResponse` (c `proposedEquipment.regNumber`).
- Consumes: `TenderLot.proposedEquipment`, `MedEquipment.registration/registrationStatus`, `MedRegistryRepository.findByRegNumber`, `MarketContext`.

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/service/RegistryAdoptTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RegistryAdoptTest {

    @Autowired RegistryMatchService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired MedRegistryRepository medRegistryRepository;

    MedRegistry reg;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        reg = medRegistryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РУ-ADOPT-1")
                .name(("ZZ Оцифровщик рентгеновских изображений " + "x".repeat(300)))
                .producer(null) // проверяем NOT NULL-заглушку manufact
                .build());
        lot = makeLot("ZZ Устройство оцифровки");
    }

    private TenderLot makeLot(String name) {
        Tender t = new Tender();
        t.setTenderNumber("ZZ-ADOPT-" + System.nanoTime());
        t.setStatus("ACTIVE");
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName(name);
        t.getLots().add(l);
        tenderRepository.save(t);
        return t.getLots().get(0);
    }

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void adoptCreatesCatalogItemAndProposesForLot() {
        TenderLot updated = service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-1");

        MedEquipment eq = updated.getProposedEquipment();
        assertThat(eq).isNotNull();
        assertThat(eq.getName()).hasSizeLessThanOrEqualTo(255).startsWith("ZZ Оцифровщик");
        assertThat(eq.getManufact()).isEqualTo("не указан"); // producer=null
        assertThat(eq.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(eq.getRegistration().getRegNumber()).isEqualTo("ZZ-РУ-ADOPT-1");
        assertThat(eq.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void adoptSameRegNumberTwice_reusesCatalogItem() {
        service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-1");
        TenderLot lot2 = makeLot("ZZ Второй лот");
        service.adoptForLot(lot2.getId(), "ZZ-РУ-ADOPT-1");

        long count = medEquipmentRepository.findAll().stream()
                .filter(e -> e.getRegistration() != null
                        && "ZZ-РУ-ADOPT-1".equals(e.getRegistration().getRegNumber()))
                .count();
        assertThat(count).isEqualTo(1); // дубль не создан
    }

    @Test
    void adoptAnotherRegNumber_replacesProposedModel() {
        medRegistryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РУ-ADOPT-2").name("ZZ Сканер пластин").producer("ZZ Producer").build());
        service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-1");
        TenderLot updated = service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-2");
        assertThat(updated.getProposedEquipment().getRegistration().getRegNumber()).isEqualTo("ZZ-РУ-ADOPT-2");
        assertThat(updated.getProposedEquipment().getManufact()).isEqualTo("ZZ Producer");
    }

    @Test
    void unknownRegNumberRejected() {
        assertThatThrownBy(() -> service.adoptForLot(lot.getId(), "ZZ-НЕТ-ТАКОГО"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void foreignMarketLotHidden() {
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.adoptForLot(lot.getId(), "ZZ-РУ-ADOPT-1"))
                .isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Прогнать — падает (компиляция: нет adoptForLot).**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.RegistryAdoptTest"`
Expected: FAIL (compilation).

- [ ] **Step 3: Finder в репозитории**

В `MedEquipmentRepository.java` добавить (импорт `java.util.Optional` есть? — добавить при отсутствии):

```java
    /** Позиция каталога, уже привязанная к РУ (дедуп при «Взять из реестра»); рыночный фильтр — аспектом. */
    Optional<MedEquipment> findFirstByRegistrationRegNumber(String regNumber);
```

- [ ] **Step 4: `adoptForLot` в `RegistryMatchService`**

Импорты добавить: `com.vladoose.nir.context.MarketContext`, `com.vladoose.nir.entity.Market` (если нет). Метод (рядом с `candidatesForLot`):

```java
    /**
     * «Взять из реестра в работу»: РУ → позиция каталога (create/reuse) → предложенная модель лота.
     * Каталог KZ наполняется по ходу работы с тендерами; оператор подтверждает кандидата вручную.
     */
    @Transactional
    public TenderLot adoptForLot(Long lotId, String regNumber) {
        TenderLot lot = tenderLotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        // findById = em.find обходит фильтр рынка → явный гард (паттерн proposed-equipment)
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }
        MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ не найдено в реестре: " + regNumber));

        MedEquipment eq = equipmentRepository.findFirstByRegistrationRegNumber(regNumber)
                .orElseGet(() -> {
                    MedEquipment e = new MedEquipment();
                    e.setName(trim255(reg.getName()));
                    e.setManufact(reg.getProducer() != null && !reg.getProducer().isBlank()
                            ? trim255(reg.getProducer()) : "не указан");
                    e.setRegistrationStatus(RegistrationStatus.REGISTERED);
                    e.setRegistration(reg);
                    e.setRegistrationCheckedAt(OffsetDateTime.now());
                    e.setMarket(MarketContext.get()); // пред-штамп (defense-in-depth к листенеру)
                    return equipmentRepository.save(e);
                });

        lot.setProposedEquipment(eq);
        return tenderLotRepository.save(lot);
    }

    private static String trim255(String s) {
        return s != null && s.length() > 255 ? s.substring(0, 255) : s;
    }
```

- [ ] **Step 5: DTO + эндпоинт**

`src/main/java/com/vladoose/nir/dto/request/AdoptRegistryRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdoptRegistryRequest {
    @NotBlank(message = "Не указан номер РУ")
    private String regNumber;
}
```

В `TenderLotController.java` (RegistryMatchService уже заинжекчен как `registryMatchService`; импорт `com.vladoose.nir.dto.request.AdoptRegistryRequest`):

```java
    /** «Взять из реестра в работу»: РУ → позиция каталога → предложенная модель лота. */
    @PostMapping("/{id}/adopt-registry")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse adoptRegistry(@PathVariable Long id,
                                           @Valid @RequestBody AdoptRegistryRequest request) {
        return mapper.toResponse(registryMatchService.adoptForLot(id, request.getRegNumber()));
    }
```

- [ ] **Step 6: Прогнать — зелёный** (+ соседи)

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.RegistryAdoptTest" --tests "com.vladoose.nir.service.RegistryLotMatchTest" --tests "com.vladoose.nir.tender.LotProposedEquipmentTest"`
Expected: PASS (5/5 + соседи).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java \
  src/main/java/com/vladoose/nir/service/RegistryMatchService.java \
  src/main/java/com/vladoose/nir/dto/request/AdoptRegistryRequest.java \
  src/main/java/com/vladoose/nir/controller/TenderLotController.java \
  src/test/java/com/vladoose/nir/service/RegistryAdoptTest.java
git commit -m "feat(registry): «Взять из реестра в работу» — adoptForLot + POST /api/lots/{id}/adopt-registry

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Фронт — кнопка «Взять в работу» + чистка UI

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `POST /api/lots/{id}/adopt-registry` (T1), существующие `openKpPanelFor/loadLots/closeRegistryPanel/isKz/isImportedTender`.
- Produces: `ApiService.adoptRegistryForLot(lotId, regNumber)`; хелперы `hasAnyType()/hasAnyDims()/hasAnyWeight()/lotHasCriteria(l)`.

- [ ] **Step 1: ApiService**

После `parseLotTechSpec` добавить:

```ts
  /** «Взять из реестра в работу»: РУ → позиция каталога → предложенная модель лота. */
  adoptRegistryForLot(lotId: number, regNumber: string): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/adopt-registry`, { regNumber });
  }
```

- [ ] **Step 2: Кнопка в панели «Реестр»**

Шапка registry-table: `<tr><th>Похожесть</th>…<th>Действует</th></tr>` → добавить в конец `<th></th>`.
Строка кандидата: после ячейки «Действует» добавить:

```html
              <td><button class="btn btn-adopt" [disabled]="adoptBusy" (click)="adoptFromRegistry(c)">Взять в работу</button></td>
```

Стиль (рядом с `.btn-registry`): `.btn-adopt { background: #0e9f6e; color: #fff; }`

TS (поле `adoptBusy = false;` рядом с `registryPanel`; метод после `onLotRegistry/closeRegistryPanel`):

```ts
  adoptFromRegistry(c: any) {
    const lot = this.registryPanel?.lot;
    if (!lot || !c?.regNumber) return;
    this.adoptBusy = true;
    this.api.adoptRegistryForLot(lot.id, c.regNumber).subscribe({
      next: () => {
        this.adoptBusy = false;
        this.notify.success(`Модель из реестра предложена для лота: ${c.name}`);
        this.closeRegistryPanel();
        this.loadLots();
        this.openKpPanelFor(lot); // сразу к запросу КП (предотметка по бренду производителя)
      },
      error: (e) => {
        this.adoptBusy = false;
        this.notify.error(e.error?.message || 'Не удалось взять РУ в работу');
        this.cdr.detectChanges();
      }
    });
  }
```

- [ ] **Step 3: Чистка инфо-сетки (пусто — не рисуем)**

Заменить строки 189–196 (блок info-grid карточки, якорь — «Способ закупки»):

```html
          <div class="info-item" *ngIf="selectedTender.purchaseType"><span class="info-label">Способ закупки</span><span>{{ getPurchaseTypeLabel(selectedTender.purchaseType) }}</span></div>
          <div class="info-item"><span class="info-label">Начальная цена (по лотам)</span><span class="price">{{ selectedTender.totalCost | money }}</span></div>
          <div class="info-item" *ngIf="selectedTender.publishDate"><span class="info-label">Дата публикации</span><span>{{ formatDate(selectedTender.publishDate) }}</span></div>
          <div class="info-item" *ngIf="selectedTender.deadline"><span class="info-label">Окончание приёма заявок</span><span class="deadline" [class.overdue]="isOverdue(selectedTender.deadline)">{{ formatDate(selectedTender.deadline) }}</span></div>
          <div class="info-item" *ngIf="selectedTender.deliveryAddress"><span class="info-label">Адрес поставки</span><span>{{ selectedTender.deliveryAddress }}</span></div>
          <div class="info-item" *ngIf="hasContactPerson()"><span class="info-label">Контактное лицо</span><span>{{ formatContact(selectedTender) }}</span></div>
          <div class="info-item" *ngIf="selectedTender.contactPhone"><span class="info-label">Телефон</span><span>{{ selectedTender.contactPhone }}</span></div>
          <div class="info-item" *ngIf="selectedTender.contactEmail"><span class="info-label">Эл. почта</span><span>{{ selectedTender.contactEmail }}</span></div>
```

TS-хелпер: `hasContactPerson(): boolean { return this.formatContact(this.selectedTender) !== '—'; }` (`formatContact` подтверждён: на пустых контактах возвращает '—', `tenders.component.ts:766-769`).

- [ ] **Step 4: Динамические колонки таблицы лотов**

Хелперы в TS:

```ts
  hasAnyType(): boolean { return (this.lots || []).some((l: any) => l.equipmentType?.name); }
  hasAnyDims(): boolean { return (this.lots || []).some((l: any) => l.maxLengthMm || l.maxWidthMm || l.maxHeightMm); }
  hasAnyWeight(): boolean { return (this.lots || []).some((l: any) => l.maxWeightKg); }
  lotHasCriteria(l: any): boolean {
    return !!(l.equipmentType || l.maxLengthMm || l.maxWidthMm || l.maxHeightMm || l.maxWeightKg);
  }
```

Шапка: `<th *ngIf="hasAnyType()">Тип</th>`, `<th *ngIf="hasAnyDims()">Габариты (макс.)</th>`, `<th *ngIf="hasAnyWeight()">Макс. вес</th>`.
Ячейки строки: тип → `<td *ngIf="hasAnyType()">{{ l.equipmentType?.name || '—' }}</td>` (фикс мёртвого `l.equipType`); габариты/вес — те же `*ngIf`.

- [ ] **Step 5: Кнопки по критериям**

- «Подобрать»: `<button class="btn btn-match" *ngIf="lotHasCriteria(l)" (click)="onMatch(l)">Подобрать</button>`
- bulk: на кнопке «Запросить КП по всему тендеру» добавить `*ngIf="lots.length > 0 && !isImportedTender()"` (заменить текущий `*ngIf="lots.length > 0"`).
- «Реестр»: `*ngIf="isKz()"` на кнопке.

- [ ] **Step 6: Сборка фронта**

Run: `cd frontend && npm run build` → успех (из корня репо; sandbox off при необходимости).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(ui): «Взять в работу» из реестра → модель лота → КП-панель; чистка карточки тендера (пусто — не рисуем)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Гейт + live + CLAUDE.md + мерж

- [ ] **Step 1:** `lsof -ti :8080 | xargs kill -9 || true && ./gradlew test` → только 2 известных.
- [ ] **Step 2:** live (Playwright, бэк с токеном, KZ):
  1. `/tenders?openId=852` → карточка БЕЗ «Способ закупки/Контакт/Телефон/Почта» («—» исчезли); таблица лотов БЕЗ колонок Тип/Габариты/Вес (пусто у лота); «Подобрать» скрыта (нет критериев); bulk-кнопки нет; «Реестр» есть.
  2. «Реестр» → у REGIUS «Взять в работу» → тост → бейдж «Предложено: … РУ ✓» → КП-панель открылась.
  3. RF `/tenders?openId=1`: поля/колонки на месте (данные есть), «Подобрать»/bulk на месте, «Реестр» скрыт.
  Скриншоты.
- [ ] **Step 3:** CLAUDE.md — §8 пункт (adopt-мост + правило «пусто — не рисуем» + кнопки по критериям), §15 `/api/lots/{id}/adopt-registry` (POST), §16: закрыть пункт «Наполнение KZ-каталога из реестр-кандидата» (в roadmap-хвосте §16/HANDOFF он упоминался), добавить дефект «хардкод-селект типов в форме лота → справочник».
- [ ] **Step 4:** Commit docs → whole-branch review (Fable) → фиксы → `git checkout main && git merge --ff-only feat/registry-adopt-ui && git branch -d …` → тур.

## Порядок

T1 → T2 → T3 (линейно).
