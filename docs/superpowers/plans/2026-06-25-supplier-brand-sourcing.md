# Группировка КП по поставщикам (Блок C) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Справочник «поставщик ↔ бренды» + автоподбор: группировать строки частной заявки по поставщикам, у кого есть бренд строки, и запрашивать КП по группам.

**Architecture:** `Distributor` получает `@ElementCollection<String> brands` (таблица `distributor_brand`), редактируется в карточке поставщика через существующий update. Новый `PrivateRequestSourcingService.buildSourcing(id)` группирует строки заявки по поставщикам, чей бренд совпадает с `tender_lot.manufact` (case-insensitive), + «строки без поставщика». UI в карточке заявки — блок «Подобрать поставщиков» с отправкой КП по группе (переиспуем `POST /api/price-requests`). Спека: `docs/superpowers/specs/2026-06-25-supplier-brand-sourcing-design.md`.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA/Hibernate 6, MapStruct, Lombok, PostgreSQL; Angular 21 (standalone, SCSS).

## Global Constraints

- Java **17**, Spring Boot **3.5.6**, Gradle **8.14** — не менять.
- Backend `com.vladoose.nir`. Entity на Lombok; сервисы `@Service` + constructor injection без `@Autowired`, `@Transactional` на записи; контроллеры `@RestController`, «голые» DTO, записи под `@PreAuthorize("hasRole('ADMIN')")`; мапперы MapStruct.
- БД PostgreSQL `localhost:5432/nirdb` (UTF-8). `ddl-auto: none`, `schema.sql` пересоздаёт таблицы, сид `data.sql`. **Редактировать `src/main/resources/schema.sql`** (не `build/...`). Новые таблицы — в DROP-список (child перед parent) и CREATE.
- Рыночный скоуп уже работает (блок A): `distributor` несёт `market` + `@Filter` + `@PrePersist`-листенер. `distributor_brand` — дочерняя коллекция distributor, своей колонки `market` НЕ нужно (доступ только через distributor).
- **КРИТИЧНО (среда):** Bash-sandbox блокирует :5432 → ЛЮБЫЕ `./gradlew`/DB-команды с `dangerouslyDisableSandbox: true`. `psql` = `/Library/PostgreSQL/17/bin/psql`, `PGPASSWORD=admin`.
- **Известные PRE-EXISTING падения, НЕ наши:** `ApplyAutoFillServiceTest` (2). `./gradlew test` = BUILD FAILED ровно с этими 2 → норма; гейт: «компилируется + только эти 2».
- Фронт: Angular standalone, инлайн-шаблон + `styles: []`, `ApiService` (база `/api`, прокси :8080), `NotificationService.success/error`. Фронт-тестов нет → гейт = `npm run build` + ручная проверка.
- Каждый commit заканчивать: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Ветка `feat/supplier-brand-sourcing` (содержит спеку). Рабочее дерево содержит несвязанный WIP пользователя — НЕ трогать; коммитить только файлы своей задачи.

---

### Task 1: Backend — бренды поставщика (`@ElementCollection` + схема + DTO) (TDD)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/entity/Distributor.java`
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/java/com/vladoose/nir/dto/request/DistributorRequest.java`, `src/main/java/com/vladoose/nir/dto/response/DistributorResponse.java`
- Test: `src/test/java/com/vladoose/nir/sourcing/DistributorBrandsTest.java`

**Interfaces:**
- Produces: `Distributor.getBrands()/setBrands(List<String>)` (таблица `distributor_brand`); `DistributorRequest.brands` (List<String>), `DistributorResponse.brands` (List<String>); update дистрибьютора сохраняет/заменяет бренды.

- [ ] **Step 1: Написать падающий тест на round-trip брендов**

Create `src/test/java/com/vladoose/nir/sourcing/DistributorBrandsTest.java`:
```java
package com.vladoose.nir.sourcing;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.repository.DistributorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DistributorBrandsTest {

    @Autowired DistributorRepository repository;

    @Test
    void brands_persistAndLoad() {
        Distributor d = Distributor.builder()
                .name("ZZBRANDS Поставщик")
                .brands(new java.util.ArrayList<>(List.of("Mindray", "Hamilton")))
                .build();
        Distributor saved = repository.save(d);
        repository.flush();

        Distributor loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getBrands()).containsExactlyInAnyOrder("Mindray", "Hamilton");
    }
}
```

- [ ] **Step 2: Запустить — падает (нет поля `brands`)**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.sourcing.DistributorBrandsTest"`
Expected: FAIL — `brands(...)` нет в билдере `Distributor` (ошибка компиляции).

- [ ] **Step 3: Добавить `brands` в entity**

В `src/main/java/com/vladoose/nir/entity/Distributor.java` ПОСЛЕ поля `equipmentTypes` (перед `}`) добавить (импорты `jakarta.persistence.*` и `java.util.*` уже есть):
```java
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "distributor_brand", joinColumns = @JoinColumn(name = "distributor_id"))
    @Column(name = "brand", nullable = false, length = 255)
    @Builder.Default
    private List<String> brands = new ArrayList<>();
```

- [ ] **Step 4: Таблица в `schema.sql`**

В `src/main/resources/schema.sql`:
1. В блок DROP, рядом с `DROP TABLE IF EXISTS distributor_equipment_type CASCADE;` добавить (перед `DROP ... distributor CASCADE`):
```sql
DROP TABLE IF EXISTS distributor_brand CASCADE;
```
2. После блока `CREATE TABLE distributor_equipment_type (...)` добавить:
```sql
CREATE TABLE distributor_brand (
    distributor_id BIGINT NOT NULL REFERENCES distributor(id) ON DELETE CASCADE,
    brand          VARCHAR(255) NOT NULL
);
```

- [ ] **Step 5: Добавить `brands` в DTO**

`src/main/java/com/vladoose/nir/dto/request/DistributorRequest.java`: добавить поле `private java.util.List<String> brands;` (рядом с `equipmentTypeIds`).
`src/main/java/com/vladoose/nir/dto/response/DistributorResponse.java`: добавить `private java.util.List<String> brands;` (рядом с `equipmentTypes`).
(Маппер `DistributorMapper` менять НЕ нужно — `brands` маппится по имени авто в toResponse/toEntity/updateEntity. `updateEntity` использует `NullValuePropertyMappingStrategy.IGNORE` → null `brands` в запросе не затрёт; фронт всегда шлёт список, в т.ч. пустой для очистки.)

- [ ] **Step 6: Запустить — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.sourcing.DistributorBrandsTest"`
Expected: PASS. Затем контекст-лоад `./gradlew test --tests "com.vladoose.nir.Nir2ApplicationTests"` — PASS (schema + data.sql грузятся, новая таблица создаётся).

- [ ] **Step 7: Полный suite + проверка структуры**

Run (sandbox off): `./gradlew test` → только 2 известных `ApplyAutoFillServiceTest`.
```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -h localhost -U postgres -d nirdb -c "\d distributor_brand" 2>&1 | head
```
Expected: таблица `distributor_brand` существует.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/entity/Distributor.java src/main/resources/schema.sql src/main/java/com/vladoose/nir/dto/request/DistributorRequest.java src/main/java/com/vladoose/nir/dto/response/DistributorResponse.java src/test/java/com/vladoose/nir/sourcing/DistributorBrandsTest.java
git commit -m "$(cat <<'EOF'
feat(sourcing): бренды поставщика — @ElementCollection distributor_brand + DTO

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Backend — `PrivateRequestSourcingService` + DTO + эндпоинт (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/SourcingGroupResponse.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/SourcingPreviewResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/PrivateRequestSourcingService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java`
- Test: `src/test/java/com/vladoose/nir/sourcing/PrivateRequestSourcingServiceTest.java`

**Interfaces:**
- Consumes: `PrivateRequestService.linesWithRegistration(id)` (→ `List<PrivateRequestLineResponse>` с `manufact`, `lotId`), `DistributorService.findAll()` (рыночно скоуплен), `DistributorMapper.toResponse`, `Distributor.getBrands()`.
- Produces:
  - `SourcingGroupResponse { DistributorResponse distributor, List<PrivateRequestLineResponse> lines }`.
  - `SourcingPreviewResponse { List<SourcingGroupResponse> groups, List<PrivateRequestLineResponse> unmatchedLines }`.
  - `PrivateRequestSourcingService.buildSourcing(Long privateRequestId) : SourcingPreviewResponse`.
  - HTTP `GET /api/private-requests/{id}/sourcing` → `SourcingPreviewResponse`.

- [ ] **Step 1: DTO**

Create `src/main/java/com/vladoose/nir/dto/response/SourcingGroupResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class SourcingGroupResponse {
    private DistributorResponse distributor;
    private List<PrivateRequestLineResponse> lines;
}
```
Create `src/main/java/com/vladoose/nir/dto/response/SourcingPreviewResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class SourcingPreviewResponse {
    private List<SourcingGroupResponse> groups;
    private List<PrivateRequestLineResponse> unmatchedLines;
}
```

- [ ] **Step 2: Написать падающий тест сервиса**

Create `src/test/java/com/vladoose/nir/sourcing/PrivateRequestSourcingServiceTest.java`:
```java
package com.vladoose.nir.sourcing;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.SourcingPreviewResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.PrivateRequestSourcingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PrivateRequestSourcingServiceTest {

    @Autowired PrivateRequestSourcingService sourcing;
    @Autowired PrivateRequestService privateRequestService;
    @Autowired DistributorRepository distributorRepository;
    @Autowired FacilityRepository facilityRepository;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void buildSourcing_groupsByBrand_andListsUnmatched() {
        // поставщик с брендом Mindray
        distributorRepository.save(Distributor.builder()
                .name("ZZSRC МедСнаб").brands(new ArrayList<>(List.of("Mindray"))).build());
        // заявка: строка Mindray (закрывается) + строка КриоСпейс (нет поставщика)
        Long clientId = facilityRepository.save(Facility.builder().name("ZZSRC Клиника").build()).getId();
        PrivateRequestCreate dto = new PrivateRequestCreate();
        dto.setClientFacilityId(clientId);
        PrivateRequestCreate.Line l1 = new PrivateRequestCreate.Line();
        l1.setName("Электрокардиограф BeneHeart R12"); l1.setManufact("Mindray"); l1.setQuantity(2);
        PrivateRequestCreate.Line l2 = new PrivateRequestCreate.Line();
        l2.setName("Криосауна CryoSpace"); l2.setManufact("КриоСпейс"); l2.setQuantity(1);
        dto.setLines(List.of(l1, l2));
        Tender t = privateRequestService.createFromLines(dto);

        SourcingPreviewResponse preview = sourcing.buildSourcing(t.getId());

        assertThat(preview.getGroups()).hasSize(1);
        assertThat(preview.getGroups().get(0).getDistributor().getName()).isEqualTo("ZZSRC МедСнаб");
        assertThat(preview.getGroups().get(0).getLines())
                .extracting(line -> line.getManufact()).containsExactly("Mindray");
        assertThat(preview.getUnmatchedLines())
                .extracting(line -> line.getManufact()).containsExactly("КриоСпейс");
    }
}
```

- [ ] **Step 3: Запустить — падает**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.sourcing.PrivateRequestSourcingServiceTest"`
Expected: FAIL — нет `PrivateRequestSourcingService` (ошибка компиляции).

- [ ] **Step 4: Реализовать сервис**

Create `src/main/java/com/vladoose/nir/service/PrivateRequestSourcingService.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PrivateRequestLineResponse;
import com.vladoose.nir.dto.response.SourcingGroupResponse;
import com.vladoose.nir.dto.response.SourcingPreviewResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.mapper.DistributorMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Группирует строки частной заявки по поставщикам, у кого есть бренд строки (case-insensitive). */
@Service
public class PrivateRequestSourcingService {

    private final PrivateRequestService privateRequestService;
    private final DistributorService distributorService;
    private final DistributorMapper distributorMapper;

    public PrivateRequestSourcingService(PrivateRequestService privateRequestService,
                                         DistributorService distributorService,
                                         DistributorMapper distributorMapper) {
        this.privateRequestService = privateRequestService;
        this.distributorService = distributorService;
        this.distributorMapper = distributorMapper;
    }

    public SourcingPreviewResponse buildSourcing(Long privateRequestId) {
        List<PrivateRequestLineResponse> lines = privateRequestService.linesWithRegistration(privateRequestId);
        List<Distributor> distributors = distributorService.findAll(); // скоуплен рынком

        Map<Long, SourcingGroupResponse> byDistributor = new LinkedHashMap<>();
        List<PrivateRequestLineResponse> unmatched = new ArrayList<>();

        for (PrivateRequestLineResponse line : lines) {
            List<Distributor> matching = new ArrayList<>();
            for (Distributor d : distributors) {
                if (carriesBrand(d, line.getManufact())) {
                    matching.add(d);
                }
            }
            if (matching.isEmpty()) {
                unmatched.add(line);
                continue;
            }
            for (Distributor d : matching) {
                SourcingGroupResponse g = byDistributor.computeIfAbsent(d.getId(), k -> {
                    SourcingGroupResponse ng = new SourcingGroupResponse();
                    ng.setDistributor(distributorMapper.toResponse(d));
                    ng.setLines(new ArrayList<>());
                    return ng;
                });
                g.getLines().add(line);
            }
        }

        SourcingPreviewResponse preview = new SourcingPreviewResponse();
        preview.setGroups(new ArrayList<>(byDistributor.values()));
        preview.setUnmatchedLines(unmatched);
        return preview;
    }

    /** Поставщик «закрывает» строку, если её бренд (manufact) содержит любой бренд поставщика (case-insensitive). */
    private boolean carriesBrand(Distributor d, String manufact) {
        if (manufact == null || manufact.isBlank() || d.getBrands() == null) {
            return false;
        }
        String m = manufact.toLowerCase();
        for (String b : d.getBrands()) {
            if (b != null && !b.isBlank() && m.contains(b.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 5: Запустить — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.sourcing.PrivateRequestSourcingServiceTest"`
Expected: PASS.

- [ ] **Step 6: Эндпоинт в `PrivateRequestController`**

В `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java`:
1. Импорт `import com.vladoose.nir.dto.response.SourcingPreviewResponse;` и `import com.vladoose.nir.service.PrivateRequestSourcingService;`.
2. Добавить зависимость `PrivateRequestSourcingService sourcingService` в конструктор (присвоить).
3. Метод:
```java
    @GetMapping("/{id}/sourcing")
    public SourcingPreviewResponse sourcing(@PathVariable Long id) {
        return sourcingService.buildSourcing(id);
    }
```

- [ ] **Step 7: Полный suite**

Run (sandbox off): `./gradlew test`
Expected: только 2 известных `ApplyAutoFillServiceTest`, ничего нового.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/response/SourcingGroupResponse.java src/main/java/com/vladoose/nir/dto/response/SourcingPreviewResponse.java src/main/java/com/vladoose/nir/service/PrivateRequestSourcingService.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/test/java/com/vladoose/nir/sourcing/PrivateRequestSourcingServiceTest.java
git commit -m "$(cat <<'EOF'
feat(sourcing): PrivateRequestSourcingService — группировка строк по поставщикам + эндпоинт

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Фронт — редактор брендов в карточке поставщика

**Files:**
- Modify: `frontend/src/app/pages/distributors/distributors.component.ts`

**Interfaces:**
- Consumes: `DistributorResponse.brands`, update дистрибьютора (поле `brands`).
- Produces: в форме поставщика — добавление/удаление брендов; сохраняется как `brands: string[]`.

- [ ] **Step 1: Редактор брендов в форме**

В `frontend/src/app/pages/distributors/distributors.component.ts` (паттерн зеркалит `selectedTypeIds`/«Специализация»):
1. Поле компонента: `brands: string[] = [];` и `newBrand = '';`.
2. В `onAdd()` сбросить: `this.brands = [];`. В `onEdit(d)` заполнить: `this.brands = [...(d.brands || [])];`.
3. Методы:
```typescript
  addBrand() {
    const b = (this.newBrand || '').trim();
    if (b && !this.brands.some(x => x.toLowerCase() === b.toLowerCase())) {
      this.brands.push(b);
    }
    this.newBrand = '';
  }
  removeBrand(i: number) { this.brands.splice(i, 1); }
```
4. В `onSave()` в `body` добавить `brands: this.brands` (рядом с `equipmentTypeIds`):
```typescript
    const body: any = { ...this.form.value, equipmentTypeIds: Array.from(this.selectedTypeIds), brands: this.brands };
```
5. В шаблоне формы (рядом с блоком «Специализация») добавить редактор брендов:
```html
      <label class="specialization-label">Бренды (что возит)</label>
      <div class="brands-block">
        <span class="brand-chip" *ngFor="let b of brands; let i = index">
          {{ b }} <button type="button" class="brand-x" (click)="removeBrand(i)">×</button>
        </span>
      </div>
      <div class="brand-add">
        <input [(ngModel)]="newBrand" [ngModelOptions]="{standalone: true}" placeholder="напр. Mindray" (keyup.enter)="addBrand()" />
        <button type="button" class="btn-line" (click)="addBrand()">+ бренд</button>
      </div>
```
6. Стили (в `styles: []`):
```scss
    .brands-block { display: flex; flex-wrap: wrap; gap: 6px; margin: 6px 0; }
    .brand-chip { display: inline-flex; align-items: center; gap: 4px; background: #eef2ff; color: #3730a3; border-radius: 999px; padding: 3px 10px; font-size: 12px; }
    .brand-x { background: none; border: none; color: #6366f1; cursor: pointer; font-size: 14px; line-height: 1; }
    .brand-add { display: flex; gap: 8px; align-items: center; }
    .brand-add input { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
    .btn-line { background: #fff; border: 1px dashed #9ca3af; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; color: #374151; }
```
(`FormsModule`, `NgFor`, `NgIf` уже в imports компонента.)

- [ ] **Step 2: Сборка**

Run: `cd frontend && npm run build` (sandbox off при необходимости).
Expected: компиляция без ошибок.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/pages/distributors/distributors.component.ts
git commit -m "$(cat <<'EOF'
feat(sourcing): фронт — редактор брендов в карточке поставщика

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Фронт — «Подобрать поставщиков» в карточке частной заявки

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/private-requests/private-request-card.component.ts`

**Interfaces:**
- Consumes: `GET /api/private-requests/{id}/sourcing` → `{ groups: [{distributor:{id,name}, lines:[{lotId,name,manufact,quantity}]}], unmatchedLines: [...] }`; `createPriceRequest`.
- Produces: `ApiService.getPrivateRequestSourcing(id)`; блок «Подобрать поставщиков» с отправкой КП по группе.

- [ ] **Step 1: Метод ApiService**

В `frontend/src/app/services/api.service.ts` после `getPrivateRequest`:
```typescript
  getPrivateRequestSourcing(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/private-requests/${id}/sourcing`);
  }
```

- [ ] **Step 2: Блок «Подобрать поставщиков» в карточке**

В `frontend/src/app/pages/private-requests/private-request-card.component.ts`:
1. Поля: `sourcing: any = null;` (preview), `sendingGroupId: number | null = null;`.
2. В `loadAll(id)` дополнительно грузить sourcing:
```typescript
    this.api.getPrivateRequestSourcing(id).subscribe({
      next: (s) => { this.sourcing = s || null; this.cdr.detectChanges(); },
      error: () => { this.sourcing = null; }
    });
```
3. Метод отправки по группе (зеркалит `requestPrice`, но по строкам группы):
```typescript
  requestGroup(group: any) {
    if (this.requestId == null || !group?.distributor?.id || !group.lines?.length) return;
    this.sendingGroupId = group.distributor.id;
    this.api.createPriceRequest({
      tenderId: this.requestId,
      distributorId: group.distributor.id,
      status: 'SENT',
      sentAt: new Date().toISOString(),
      items: group.lines.map((l: any) => ({ tenderLotId: l.lotId, medEquipmentId: null, requestedQuantity: l.quantity }))
    }).subscribe({
      next: () => {
        this.sendingGroupId = null;
        this.notify.success('КП запрошено у «' + group.distributor.name + '»');
        this.loadPriceRequests(this.requestId as number);
        this.api.getPrivateRequestSourcing(this.requestId as number).subscribe({ next: s => { this.sourcing = s; this.cdr.detectChanges(); } });
      },
      error: (e) => { this.sendingGroupId = null; this.notify.error('Ошибка: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); }
    });
  }
```
4. В шаблоне ПЕРЕД секцией «Запросить КП» (ручной фолбэк) добавить секцию «Подобрать поставщиков»:
```html
          <!-- Подобрать поставщиков (по брендам) -->
          <section class="section" *ngIf="!loading && sourcing">
            <h3 class="section-title">Подобрать поставщиков</h3>
            <div class="empty" *ngIf="!sourcing.groups?.length">Нет поставщиков с подходящими брендами. Добавьте бренды в карточках поставщиков или запросите вручную ниже.</div>
            <div class="src-group" *ngFor="let g of sourcing.groups">
              <div class="src-head">
                <span class="src-dist">{{ g.distributor?.name }}</span>
                <button class="btn-primary" type="button" (click)="requestGroup(g)" [disabled]="sendingGroupId === g.distributor?.id">
                  Запросить КП ({{ g.lines?.length || 0 }})
                </button>
              </div>
              <ul class="src-lines">
                <li *ngFor="let l of g.lines">{{ l.name }} <span class="src-brand">· {{ l.manufact }}</span> × {{ l.quantity }}</li>
              </ul>
            </div>
            <div class="src-unmatched" *ngIf="sourcing.unmatchedLines?.length">
              <div class="src-unmatched-title">Без поставщика ({{ sourcing.unmatchedLines.length }}):</div>
              <ul class="src-lines">
                <li *ngFor="let l of sourcing.unmatchedLines">{{ l.name }} <span class="src-brand">· {{ l.manufact || 'бренд не указан' }}</span></li>
              </ul>
            </div>
          </section>
```
5. Стили:
```scss
    .src-group { border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; }
    .src-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
    .src-dist { font-weight: 600; color: #111827; font-size: 14px; }
    .src-lines { margin: 0; padding-left: 18px; font-size: 13px; color: #374151; }
    .src-brand { color: #6b7280; }
    .src-unmatched { margin-top: 10px; padding: 10px 12px; background: #f9fafb; border: 1px dashed #e5e7eb; border-radius: 8px; }
    .src-unmatched-title { font-size: 12px; color: #92400e; font-weight: 600; margin-bottom: 4px; }
```

- [ ] **Step 3: Сборка**

Run: `cd frontend && npm run build` (sandbox off при необходимости).
Expected: компиляция без ошибок.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/pages/private-requests/private-request-card.component.ts
git commit -m "$(cat <<'EOF'
feat(sourcing): фронт — «Подобрать поставщиков» в карточке заявки + отправка КП по группе

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Финальная проверка (после всех задач)

- [ ] `./gradlew test` (sandbox off) — только 2 известных `ApplyAutoFillServiceTest`, все sourcing-тесты зелёные.
- [ ] `cd frontend && npm run build` — фронт собирается.
- [ ] **E2E-смоук (рынок KZ):** в карточке поставщика «ТОО «МедСнаб Казахстан»» добавить бренд «Mindray» → открыть `ЧЗ-2026-0001` → блок «Подобрать поставщиков» показывает МедСнаб со строкой «Электрокардиограф BeneHeart R12», «Криосауна» — в «Без поставщика» → «Запросить КП» по группе → появляется в «Существующих КП».
