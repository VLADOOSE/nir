# Bulk Pricing + Equipment Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Внедрить справочник типов оборудования, специализацию дистрибьюторов, массовый подбор по тендеру с группировкой по дистрибьюторам, карточку оборудования с историей КП и рейтингом, автосборку заявки из принятых КП.

**Architecture:** Backend — Spring Boot 3.5 + JPA + MapStruct + Spring Security (session-based). Frontend — Angular 21 (standalone components, zone.js, reactive forms). Изменения вносятся поверх существующего проекта с DTOs/мапперами по образцу уже принятого паттерна.

**Tech Stack:** Java 17, Spring Boot, JPA/Hibernate, PostgreSQL, MapStruct, Lombok, Angular 21, RxJS, JavaMailSender.

**Дизайн-источник:** `docs/superpowers/specs/2026-05-24-bulk-pricing-equipment-types-design.md`

---

## File Structure Overview

### Создаются (backend)
```
src/main/java/com/vladoose/nir/
├── entity/EquipmentType.java
├── entity/PriceRequestItem.java
├── repository/EquipmentTypeRepository.java
├── repository/PriceRequestItemRepository.java
├── service/EquipmentTypeService.java
├── service/BulkPriceRequestService.java
├── service/ApplyAutoFillService.java
├── service/EquipmentStatsService.java
├── controller/EquipmentTypeController.java
├── controller/BulkPriceController.java
├── dto/request/EquipmentTypeRequest.java
├── dto/request/BulkPriceSendRequest.java
├── dto/request/PriceRequestItemRequest.java
├── dto/response/EquipmentTypeResponse.java
├── dto/response/PriceRequestItemResponse.java
├── dto/response/BulkPricePreviewResponse.java
├── dto/response/EquipmentStatsResponse.java
├── dto/response/AutoFillResponse.java
└── mapper/EquipmentTypeMapper.java
└── mapper/PriceRequestItemMapper.java

src/test/java/com/vladoose/nir/service/
├── BulkPriceRequestServiceTest.java
└── ApplyAutoFillServiceTest.java
```

### Модифицируются (backend)
```
src/main/resources/schema.sql            — новые таблицы и ALTER
src/main/resources/data.sql              — equip_type_id вместо строк
src/main/java/com/vladoose/nir/
├── entity/MedEquipment.java             — equipType: String → ManyToOne EquipmentType
├── entity/TenderLot.java                — то же
├── entity/Distributor.java              — + @ManyToMany List<EquipmentType>
├── entity/PriceRequest.java             — убрать item-поля, +tender, +note, +items
├── repository/MedEquipmentRepository.java  — findMatchingEquipment по type_id
├── repository/DistributorRepository.java   — findEligibleForType
├── repository/PriceRequestRepository.java  — findByTenderId(Long)
├── service/DataInitializer.java         — сидинг equipment_type
├── controller/MedEquipmentController.java  — endpoint /api/equipment/{id}/stats
├── controller/ActivityApplyController.java — endpoint /api/applies/{id}/auto-fill
├── controller/PriceRequestController.java  — работа с item-структурой
├── dto/request/MedEquipmentRequest.java    — equipTypeId вместо equipType
├── dto/request/TenderLotRequest.java       — то же
├── dto/request/DistributorRequest.java     — + List<Long> equipmentTypeIds
├── dto/request/PriceRequestRequest.java    — структура с items
├── dto/response/MedEquipmentResponse.java  — equipmentType: EquipmentTypeResponse
├── dto/response/TenderLotResponse.java     — то же
├── dto/response/DistributorResponse.java   — + equipmentTypes
├── dto/response/PriceRequestResponse.java  — + items
└── mapper/*Mapper.java                  — соответствующие обновления
```

### Создаются (frontend)
```
frontend/src/app/
├── pages/equipment-types/equipment-types.component.ts
├── pages/tenders/bulk-price-modal.component.ts
└── components/equipment-detail-modal/equipment-detail-modal.component.ts
```

### Модифицируются (frontend)
```
frontend/src/app/
├── services/api.service.ts             — методы для всех новых endpoints
├── app.routes.ts                       — роут /equipment-types
├── layout/layout.component.ts          — пункт меню «Типы оборудования» (ADMIN)
├── pages/distributors/distributors.component.ts  — форма со specialization
├── pages/equipment/equipment.component.ts        — select типов из API, открытие карточки
├── pages/tenders/tenders.component.ts            — кнопка «Запросить КП по всему тендеру», переделка секции КП
└── pages/applies/applies.component.ts            — фильтр по типу, кнопка «Собрать из КП»
```

---

## Phase 1: БД и Entity

### Task 1.1: Миграция schema.sql + сидинг data.sql

**Files:**
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/resources/data.sql`

- [ ] **Step 1: Добавить таблицы в schema.sql (перед PriceRequest)**

Открыть `src/main/resources/schema.sql`, в начале (после `DROP TABLE` блока) добавить новые DROP:
```sql
DROP TABLE IF EXISTS price_request_item CASCADE;
DROP TABLE IF EXISTS distributor_equipment_type CASCADE;
DROP TABLE IF EXISTS equipment_type CASCADE;
```
Перед `CREATE TABLE med_equipment` добавить:
```sql
CREATE TABLE equipment_type (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL
);
```

- [ ] **Step 2: Заменить equip_type на equip_type_id в med_equipment**

В `CREATE TABLE med_equipment` заменить строку:
```sql
equip_type VARCHAR(100),
```
на:
```sql
equip_type_id BIGINT REFERENCES equipment_type(id),
```

- [ ] **Step 3: То же в tender_lot**

В `CREATE TABLE tender_lot` заменить:
```sql
equip_type    VARCHAR(100),
```
на:
```sql
equip_type_id BIGINT REFERENCES equipment_type(id),
```

- [ ] **Step 4: Добавить distributor_equipment_type после distributor**

После `CREATE TABLE distributor (...)`:
```sql
CREATE TABLE distributor_equipment_type (
    distributor_id    BIGINT NOT NULL REFERENCES distributor(id) ON DELETE CASCADE,
    equipment_type_id BIGINT NOT NULL REFERENCES equipment_type(id) ON DELETE CASCADE,
    PRIMARY KEY (distributor_id, equipment_type_id)
);
```

- [ ] **Step 5: Перестроить price_request + добавить price_request_item**

В `CREATE TABLE price_request` удалить колонки `tender_lot_id`, `med_equip_id`, `response_price`, `response_note` (FK references на них тоже исчезают), добавить `tender_id BIGINT NOT NULL REFERENCES tender(id)` и `note TEXT`. Полный вид:
```sql
CREATE TABLE price_request (
    id             BIGSERIAL PRIMARY KEY,
    tender_id      BIGINT NOT NULL REFERENCES tender(id),
    distributor_id BIGINT NOT NULL REFERENCES distributor(id),
    status         VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    sent_at        TIMESTAMPTZ,
    response_date  DATE,
    note           TEXT,
    created_at     TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE price_request_item (
    id                 BIGSERIAL PRIMARY KEY,
    price_request_id   BIGINT NOT NULL REFERENCES price_request(id) ON DELETE CASCADE,
    tender_lot_id      BIGINT NOT NULL REFERENCES tender_lot(id),
    med_equipment_id   BIGINT NOT NULL REFERENCES med_equipment(id),
    requested_quantity INTEGER NOT NULL,
    response_price     NUMERIC(15, 2),
    response_note      TEXT
);
```

- [ ] **Step 6: В data.sql сидировать equipment_type ПЕРЕД оборудованием**

В `src/main/resources/data.sql` в самом начале (до INSERT facility):
```sql
-- 0. Типы оборудования
INSERT INTO equipment_type (name) VALUES ('УЗИ'), ('Рентген'), ('ИВЛ'), ('Монитор');
```

- [ ] **Step 7: В data.sql заменить equip_type строки на подзапросы**

В блоке INSERT med_equipment заменить столбец `equip_type` на `equip_type_id`. Каждая строка `'УЗИ'`/`'Рентген'`/`'ИВЛ'`/`'Монитор'` заменяется на `(SELECT id FROM equipment_type WHERE name='...')`. Полный пример первой строки:
```sql
INSERT INTO med_equipment (name, manufact, equip_type_id, cost, length_mm, width_mm, height_mm, weight_kg, spec) VALUES
('Аппарат УЗИ SonoAce R7', 'Samsung Medison', (SELECT id FROM equipment_type WHERE name='УЗИ'), 850000, 520, 480, 1350, 85.00, 'Портативный УЗИ аппарат с цветным допплером'),
...
```
Применить аналогично ко всем 8 строкам и ко всем 5 строкам INSERT tender_lot (там тоже `equip_type` → `equip_type_id`).

- [ ] **Step 8: Запустить приложение для проверки схемы**

```bash
./gradlew bootRun
```
Ожидаем: успешный старт, в логах строки `Hibernate: insert into equipment_type ...` и `Hibernate: insert into med_equipment ...`. Остановить (Ctrl+C).

- [ ] **Step 9: Commit**
```bash
git add src/main/resources/schema.sql src/main/resources/data.sql
git commit -m "feat(db): equipment_type справочник + distributor specialization + price_request_item"
```

---

### Task 1.2: Entity для нового справочника и связи

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/EquipmentType.java`
- Create: `src/main/java/com/vladoose/nir/entity/PriceRequestItem.java`
- Modify: `src/main/java/com/vladoose/nir/entity/MedEquipment.java`
- Modify: `src/main/java/com/vladoose/nir/entity/TenderLot.java`
- Modify: `src/main/java/com/vladoose/nir/entity/Distributor.java`
- Modify: `src/main/java/com/vladoose/nir/entity/PriceRequest.java`

- [ ] **Step 1: Создать EquipmentType.java**

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "equipment_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EquipmentType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100, unique = true, nullable = false)
    private String name;
}
```

- [ ] **Step 2: Создать PriceRequestItem.java**

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "price_request_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "price_request_id", nullable = false)
    private PriceRequest priceRequest;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tender_lot_id", nullable = false)
    private TenderLot tenderLot;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "med_equipment_id", nullable = false)
    private MedEquipment medEquipment;

    @Column(name = "requested_quantity", nullable = false)
    private Integer requestedQuantity;

    @Column(name = "response_price", precision = 15, scale = 2)
    private BigDecimal responsePrice;

    @Column(name = "response_note", columnDefinition = "TEXT")
    private String responseNote;
}
```

- [ ] **Step 3: Обновить MedEquipment.java — equipType: String → @ManyToOne**

Удалить:
```java
@Column(name = "equip_type", length = 100)
private String equipType;
```
Заменить на:
```java
@ManyToOne(fetch = FetchType.EAGER)
@JoinColumn(name = "equip_type_id")
private EquipmentType equipmentType;
```

- [ ] **Step 4: Обновить TenderLot.java аналогично**

Удалить `private String equipType` блок, заменить на:
```java
@ManyToOne(fetch = FetchType.EAGER)
@JoinColumn(name = "equip_type_id")
private EquipmentType equipmentType;
```

- [ ] **Step 5: Обновить Distributor.java — M2M со специализацией**

В конец класса добавить (перед закрывающей `}`):
```java
@ManyToMany(fetch = FetchType.EAGER)
@JoinTable(
    name = "distributor_equipment_type",
    joinColumns = @JoinColumn(name = "distributor_id"),
    inverseJoinColumns = @JoinColumn(name = "equipment_type_id")
)
@Builder.Default
private java.util.List<EquipmentType> equipmentTypes = new java.util.ArrayList<>();
```

- [ ] **Step 6: Обновить PriceRequest.java — убрать item-поля, добавить tender + items**

Удалить блоки `tenderLot`, `medEquipment`, `responsePrice`, `responseNote`. Добавить:
```java
@ManyToOne(fetch = FetchType.EAGER)
@JoinColumn(name = "tender_id", nullable = false)
private Tender tender;

@Column(columnDefinition = "TEXT")
private String note;

@OneToMany(mappedBy = "priceRequest", cascade = CascadeType.ALL, orphanRemoval = true)
@Builder.Default
private java.util.List<PriceRequestItem> items = new java.util.ArrayList<>();
```
Остающиеся поля: `id`, `distributor`, `status`, `sentAt`, `responseDate`, `createdAt`. Импорты подчистить.

- [ ] **Step 7: Compile check**

```bash
./gradlew compileJava 2>&1 | tail -20
```
Ожидаем: BUILD SUCCESSFUL. Если есть ссылки на `priceRequest.getTenderLot()` или `med_equip_id`/`equipType` — это в сервисах/мапперах (исправим в задачах 1.4 и Phase 2). Допустимо если ошибки только там.

- [ ] **Step 8: Commit**
```bash
git add src/main/java/com/vladoose/nir/entity/
git commit -m "feat(entity): EquipmentType + PriceRequestItem + специализация дистрибьютора"
```

---

### Task 1.3: Репозитории

**Files:**
- Create: `src/main/java/com/vladoose/nir/repository/EquipmentTypeRepository.java`
- Create: `src/main/java/com/vladoose/nir/repository/PriceRequestItemRepository.java`
- Modify: `src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java`
- Modify: `src/main/java/com/vladoose/nir/repository/DistributorRepository.java`
- Modify: `src/main/java/com/vladoose/nir/repository/PriceRequestRepository.java`

- [ ] **Step 1: EquipmentTypeRepository.java**

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.EquipmentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EquipmentTypeRepository extends JpaRepository<EquipmentType, Long> {
    Optional<EquipmentType> findByName(String name);
    boolean existsByName(String name);
}
```

- [ ] **Step 2: PriceRequestItemRepository.java**

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceRequestItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceRequestItemRepository extends JpaRepository<PriceRequestItem, Long> {
    List<PriceRequestItem> findByMedEquipmentId(Long medEquipmentId);
    List<PriceRequestItem> findByPriceRequest_Tender_Id(Long tenderId);
    List<PriceRequestItem> findByPriceRequestId(Long priceRequestId);
}
```

- [ ] **Step 3: Обновить MedEquipmentRepository — findMatchingEquipment по type_id**

Открыть `src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java`. В JPQL запросе `findMatchingEquipment` заменить условие сравнения по `equipType` (строка) на `equipmentType.id`:
```java
@Query("SELECT e FROM MedEquipment e WHERE " +
       "(:equipTypeId IS NULL OR e.equipmentType.id = :equipTypeId) AND " +
       "(:maxLengthMm IS NULL OR e.lengthMm <= :maxLengthMm) AND " +
       "(:maxWidthMm IS NULL OR e.widthMm <= :maxWidthMm) AND " +
       "(:maxHeightMm IS NULL OR e.heightMm <= :maxHeightMm) AND " +
       "(:maxWeightKg IS NULL OR e.weightKg <= :maxWeightKg) AND " +
       "(:maxCost IS NULL OR e.cost <= :maxCost) " +
       "ORDER BY e.cost ASC")
List<MedEquipment> findMatchingEquipment(
        @Param("equipTypeId") Long equipTypeId,
        @Param("maxLengthMm") Integer maxLengthMm,
        @Param("maxWidthMm") Integer maxWidthMm,
        @Param("maxHeightMm") Integer maxHeightMm,
        @Param("maxWeightKg") BigDecimal maxWeightKg,
        @Param("maxCost") Integer maxCost);
```
Также добавить метод `List<MedEquipment> findByEquipmentTypeId(Long typeId)`.

- [ ] **Step 4: Обновить DistributorRepository — findEligibleForType**

В `src/main/java/com/vladoose/nir/repository/DistributorRepository.java` добавить:
```java
@Query("SELECT DISTINCT d FROM Distributor d LEFT JOIN d.equipmentTypes et " +
       "WHERE et.id = :typeId OR SIZE(d.equipmentTypes) = 0")
List<Distributor> findEligibleForType(@Param("typeId") Long typeId);
```

- [ ] **Step 5: Обновить PriceRequestRepository — findByTenderId**

В `src/main/java/com/vladoose/nir/repository/PriceRequestRepository.java` добавить метод (если его ещё нет):
```java
List<PriceRequest> findByTenderId(Long tenderId);
```

- [ ] **Step 6: Compile check**
```bash
./gradlew compileJava 2>&1 | tail -15
```

- [ ] **Step 7: Commit**
```bash
git add src/main/java/com/vladoose/nir/repository/
git commit -m "feat(repo): репозитории для типов + специализации + price_request_item"
```

---

### Task 1.4: Обновить DataInitializer + service-методы которые ломаются от смены типа

**Files:**
- Modify: `src/main/java/com/vladoose/nir/config/DataInitializer.java`
- Modify: `src/main/java/com/vladoose/nir/service/MedEquipmentService.java`
- Modify: `src/main/java/com/vladoose/nir/service/PriceRequestService.java` (если есть упоминания старой структуры)

- [ ] **Step 1: DataInitializer — добавить сидинг типов**

В `DataInitializer.run`:
```java
@Override
public void run(String... args) {
    if (equipmentTypeRepository.count() == 0) {
        for (String name : List.of("УЗИ", "Рентген", "ИВЛ", "Монитор")) {
            equipmentTypeRepository.save(EquipmentType.builder().name(name).build());
        }
    }
    if (userRepository.count() == 0) {
        // существующий код создания admin/operator
    }
}
```
Добавить inject `EquipmentTypeRepository equipmentTypeRepository` в конструктор. Не забыть импорты `EquipmentType` и `List`.

- [ ] **Step 2: Обновить MedEquipmentService.findMatchingForLot — передать typeId**

В `findMatchingForLot(TenderLot lot)`:
```java
public List<MedEquipment> findMatchingForLot(TenderLot lot) {
    Integer maxCost = lot.getMaxCost() != null ? lot.getMaxCost().intValue() : null;
    Long typeId = lot.getEquipmentType() != null ? lot.getEquipmentType().getId() : null;
    return repository.findMatchingEquipment(
            typeId,
            lot.getMaxLengthMm(),
            lot.getMaxWidthMm(),
            lot.getMaxHeightMm(),
            lot.getMaxWeightKg(),
            maxCost
    );
}
```

- [ ] **Step 3: Проверить PriceRequestService и аналогичные на ссылки к старой структуре**

```bash
grep -rn "getTenderLot\|getMedEquipment\|getResponsePrice\|setResponsePrice\|getResponseNote\|setResponseNote" /Users/vlad/IdeaProjects/nir2/src/main/java/com/vladoose/nir/service/
```
Если что-то осталось, относящееся к `PriceRequest` (не к `PriceRequestItem`) — это надо переключить либо на новый API (через items), либо временно закомментировать (полностью переделаем в Phase 3).

- [ ] **Step 4: Compile check**
```bash
./gradlew compileJava 2>&1 | tail -15
```
Если падает в сервисах/контроллерах — допустимо: они переделываются в Phase 2-3 вместе с DTOs/мапперами.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/vladoose/nir/config/DataInitializer.java src/main/java/com/vladoose/nir/service/
git commit -m "feat(service): сидинг типов в DataInitializer + matching по equipTypeId"
```

---

## Phase 2: DTOs + Mappers

### Task 2.1: EquipmentType DTO + Mapper

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/EquipmentTypeRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/EquipmentTypeResponse.java`
- Create: `src/main/java/com/vladoose/nir/mapper/EquipmentTypeMapper.java`

- [ ] **Step 1: EquipmentTypeRequest**
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EquipmentTypeRequest {
    @NotBlank(message = "Название обязательно")
    @Size(max = 100)
    private String name;
}
```

- [ ] **Step 2: EquipmentTypeResponse**
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class EquipmentTypeResponse {
    private Long id;
    private String name;
}
```

- [ ] **Step 3: EquipmentTypeMapper**
```java
package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.EquipmentTypeRequest;
import com.vladoose.nir.dto.response.EquipmentTypeResponse;
import com.vladoose.nir.entity.EquipmentType;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface EquipmentTypeMapper {
    EquipmentTypeResponse toResponse(EquipmentType entity);
    List<EquipmentTypeResponse> toResponseList(List<EquipmentType> list);
    EquipmentType toEntity(EquipmentTypeRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntity(EquipmentTypeRequest request, @MappingTarget EquipmentType entity);
}
```

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/vladoose/nir/dto/ src/main/java/com/vladoose/nir/mapper/EquipmentTypeMapper.java
git commit -m "feat(dto): EquipmentType DTO + mapper"
```

---

### Task 2.2: Обновить MedEquipment DTO + Mapper

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/MedEquipmentRequest.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/MedEquipmentResponse.java`
- Modify: `src/main/java/com/vladoose/nir/mapper/MedEquipmentMapper.java`

- [ ] **Step 1: MedEquipmentRequest — equipType: String → equipTypeId: Long**

Найти и заменить:
```java
private String equipType;
```
на:
```java
private Long equipTypeId;
```

- [ ] **Step 2: MedEquipmentResponse — добавить EquipmentTypeResponse**

Заменить `private String equipType;` на:
```java
private com.vladoose.nir.dto.response.EquipmentTypeResponse equipmentType;
```

- [ ] **Step 3: MedEquipmentMapper — qualifiedByName для equipmentType**

В мапере добавить:
- В `toEntity` — `@Mapping(target = "equipmentType", source = "equipTypeId", qualifiedByName = "equipmentTypeFromId")`.
- В `updateEntity` — то же.
- Default-метод:
```java
@org.mapstruct.Named("equipmentTypeFromId")
default com.vladoose.nir.entity.EquipmentType equipmentTypeFromId(Long id) {
    if (id == null) return null;
    com.vladoose.nir.entity.EquipmentType e = new com.vladoose.nir.entity.EquipmentType();
    e.setId(id);
    return e;
}
```
В `toResponse` маппинг автоматический если оба свойства называются `equipmentType` (MapStruct видит EquipmentTypeMapper через `uses = EquipmentTypeMapper.class` — добавь к `@Mapper`).

- [ ] **Step 4: Compile check**
```bash
./gradlew compileJava 2>&1 | tail -15
```

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/vladoose/nir/dto/request/MedEquipmentRequest.java src/main/java/com/vladoose/nir/dto/response/MedEquipmentResponse.java src/main/java/com/vladoose/nir/mapper/MedEquipmentMapper.java
git commit -m "feat(dto): MedEquipment — equipTypeId вместо строки"
```

---

### Task 2.3: Обновить TenderLot DTO + Mapper (аналогично 2.2)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/TenderLotRequest.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/TenderLotShortResponse.java`
- Modify: `src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java`

- [ ] **Step 1-4:** То же что Task 2.2 но для TenderLot.
  - В `TenderLotRequest`: `private String equipType;` → `private Long equipTypeId;`
  - В `TenderLotResponse` и `TenderLotShortResponse`: `private String equipType;` → `private EquipmentTypeResponse equipmentType;`
  - В `TenderLotMapper`: добавить `uses = EquipmentTypeMapper.class`, `@Mapping(target = "equipmentType", source = "equipTypeId", qualifiedByName = "equipmentTypeFromId")` в toEntity/updateEntity, и `equipmentTypeFromId` default-метод (или импорт из общего helper).

- [ ] **Step 5: Compile check**

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/vladoose/nir/dto/request/TenderLotRequest.java src/main/java/com/vladoose/nir/dto/response/TenderLot*.java src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java
git commit -m "feat(dto): TenderLot — equipTypeId вместо строки"
```

---

### Task 2.4: Обновить Distributor DTO + Mapper (specialization)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/DistributorRequest.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/DistributorResponse.java`
- Modify: `src/main/java/com/vladoose/nir/mapper/DistributorMapper.java`

- [ ] **Step 1: DistributorRequest — добавить equipmentTypeIds**

В конец полей:
```java
private java.util.List<Long> equipmentTypeIds;
```

- [ ] **Step 2: DistributorResponse — добавить equipmentTypes**

```java
private java.util.List<EquipmentTypeResponse> equipmentTypes;
```

- [ ] **Step 3: DistributorMapper — маппинг M2M**

Добавить `uses = EquipmentTypeMapper.class` к `@Mapper`. В toEntity/updateEntity:
```java
@Mapping(target = "equipmentTypes", source = "equipmentTypeIds", qualifiedByName = "equipmentTypesFromIds")
```
И default-метод:
```java
@Named("equipmentTypesFromIds")
default List<EquipmentType> equipmentTypesFromIds(List<Long> ids) {
    if (ids == null) return new ArrayList<>();
    return ids.stream().map(id -> {
        EquipmentType e = new EquipmentType();
        e.setId(id);
        return e;
    }).toList();
}
```
Импорты добавить.

- [ ] **Step 4: Compile + Commit**
```bash
./gradlew compileJava 2>&1 | tail -10
git add src/main/java/com/vladoose/nir/dto/request/DistributorRequest.java src/main/java/com/vladoose/nir/dto/response/DistributorResponse.java src/main/java/com/vladoose/nir/mapper/DistributorMapper.java
git commit -m "feat(dto): Distributor — specialization (equipmentTypeIds/equipmentTypes)"
```

---

### Task 2.5: PriceRequestItem DTO + обновить PriceRequest DTO

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/PriceRequestItemRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/PriceRequestItemResponse.java`
- Create: `src/main/java/com/vladoose/nir/mapper/PriceRequestItemMapper.java`
- Modify: `src/main/java/com/vladoose/nir/dto/request/PriceRequestRequest.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/PriceRequestResponse.java`
- Modify: `src/main/java/com/vladoose/nir/mapper/PriceRequestMapper.java`

- [ ] **Step 1: PriceRequestItemRequest**
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class PriceRequestItemRequest {
    private Long id;
    @NotNull private Long tenderLotId;
    @NotNull private Long medEquipmentId;
    @NotNull @Positive private Integer requestedQuantity;
    @PositiveOrZero private BigDecimal responsePrice;
    private String responseNote;
}
```

- [ ] **Step 2: PriceRequestItemResponse**
```java
package com.vladoose.nir.dto.response;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PriceRequestItemResponse {
    private Long id;
    private TenderLotShortResponse tenderLot;
    private MedEquipmentResponse medEquipment;
    private Integer requestedQuantity;
    private BigDecimal responsePrice;
    private String responseNote;
}
```

- [ ] **Step 3: PriceRequestItemMapper**
```java
package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.PriceRequestItemRequest;
import com.vladoose.nir.dto.response.PriceRequestItemResponse;
import com.vladoose.nir.entity.*;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = {TenderLotMapper.class, MedEquipmentMapper.class})
public interface PriceRequestItemMapper {
    PriceRequestItemResponse toResponse(PriceRequestItem entity);
    List<PriceRequestItemResponse> toResponseList(List<PriceRequestItem> list);

    @Mapping(target = "priceRequest", ignore = true)
    @Mapping(target = "tenderLot", source = "tenderLotId", qualifiedByName = "tenderLotFromId")
    @Mapping(target = "medEquipment", source = "medEquipmentId", qualifiedByName = "medEquipmentFromId")
    PriceRequestItem toEntity(PriceRequestItemRequest request);

    @Named("tenderLotFromId")
    default TenderLot tenderLotFromId(Long id) { if (id == null) return null; TenderLot t = new TenderLot(); t.setId(id); return t; }

    @Named("medEquipmentFromId")
    default MedEquipment medEquipmentFromId(Long id) { if (id == null) return null; MedEquipment m = new MedEquipment(); m.setId(id); return m; }
}
```

- [ ] **Step 4: PriceRequestRequest — переписать на структуру с items**

Удалить старые поля `tenderLotId`, `medEquipmentId`. Полный новый вид:
```java
@Data
public class PriceRequestRequest {
    @NotNull private Long tenderId;
    @NotNull private Long distributorId;
    private String status;
    private String note;
    private java.time.OffsetDateTime sentAt;
    private java.time.LocalDate responseDate;
    @Valid private java.util.List<PriceRequestItemRequest> items = new java.util.ArrayList<>();
}
```

- [ ] **Step 5: PriceRequestResponse — заменить структуру**

Полный новый вид:
```java
@Data
public class PriceRequestResponse {
    private Long id;
    private TenderShortResponse tender;
    private DistributorResponse distributor;
    private String status;
    private java.time.OffsetDateTime sentAt;
    private java.time.LocalDate responseDate;
    private String note;
    private java.time.OffsetDateTime createdAt;
    private java.util.List<PriceRequestItemResponse> items;
}
```

- [ ] **Step 6: PriceRequestMapper — переписать**

```java
@Mapper(componentModel = "spring", uses = {TenderMapper.class, DistributorMapper.class, PriceRequestItemMapper.class})
public interface PriceRequestMapper {
    PriceRequestResponse toResponse(PriceRequest entity);
    List<PriceRequestResponse> toResponseList(List<PriceRequest> list);

    @Mapping(target = "tender", source = "tenderId", qualifiedByName = "tenderFromId")
    @Mapping(target = "distributor", source = "distributorId", qualifiedByName = "distributorFromId")
    @Mapping(target = "items", ignore = true)
    PriceRequest toEntity(PriceRequestRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "tender", ignore = true)
    @Mapping(target = "distributor", ignore = true)
    @Mapping(target = "items", ignore = true)
    void updateEntity(PriceRequestRequest request, @MappingTarget PriceRequest entity);

    @Named("tenderFromId") default Tender tenderFromId(Long id) { if (id == null) return null; Tender t = new Tender(); t.setId(id); return t; }
    @Named("distributorFromId") default Distributor distributorFromId(Long id) { if (id == null) return null; Distributor d = new Distributor(); d.setId(id); return d; }
}
```
Item-ы создаются вручную в сервисе (т.к. нужна установка priceRequest reference и cascade save).

- [ ] **Step 7: Compile + Commit**
```bash
./gradlew compileJava 2>&1 | tail -10
git add src/main/java/com/vladoose/nir/dto/ src/main/java/com/vladoose/nir/mapper/PriceRequest*
git commit -m "feat(dto): PriceRequest со структурой items"
```

---

## Phase 3: REST endpoints

### Task 3.1: EquipmentTypeService + Controller

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/EquipmentTypeService.java`
- Create: `src/main/java/com/vladoose/nir/controller/EquipmentTypeController.java`

- [ ] **Step 1: EquipmentTypeService**
```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EquipmentTypeService {

    private final EquipmentTypeRepository repository;
    private final MedEquipmentRepository medEquipmentRepository;
    private final TenderLotRepository tenderLotRepository;

    public EquipmentTypeService(EquipmentTypeRepository repository,
                                 MedEquipmentRepository medEquipmentRepository,
                                 TenderLotRepository tenderLotRepository) {
        this.repository = repository;
        this.medEquipmentRepository = medEquipmentRepository;
        this.tenderLotRepository = tenderLotRepository;
    }

    public List<EquipmentType> findAll() { return repository.findAll(); }

    public EquipmentType findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Тип не найден: " + id));
    }

    @Transactional
    public EquipmentType save(EquipmentType type) { return repository.save(type); }

    @Transactional
    public void deleteById(Long id) {
        if (!medEquipmentRepository.findByEquipmentTypeId(id).isEmpty()) {
            throw new BadRequestException("Невозможно удалить: тип используется в оборудовании");
        }
        if (!tenderLotRepository.findByEquipmentTypeId(id).isEmpty()) {
            throw new BadRequestException("Невозможно удалить: тип используется в лотах тендеров");
        }
        repository.deleteById(id);
    }
}
```
В `TenderLotRepository` нужен `findByEquipmentTypeId` — если нет, добавить аналогично `MedEquipmentRepository`.

- [ ] **Step 2: EquipmentTypeController**
```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.EquipmentTypeRequest;
import com.vladoose.nir.dto.response.EquipmentTypeResponse;
import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.mapper.EquipmentTypeMapper;
import com.vladoose.nir.service.EquipmentTypeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/equipment-types")
public class EquipmentTypeController {
    private final EquipmentTypeService service;
    private final EquipmentTypeMapper mapper;

    public EquipmentTypeController(EquipmentTypeService service, EquipmentTypeMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<EquipmentTypeResponse> findAll() { return mapper.toResponseList(service.findAll()); }

    @GetMapping("/{id}")
    public EquipmentTypeResponse findById(@PathVariable Long id) { return mapper.toResponse(service.findById(id)); }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public EquipmentTypeResponse create(@Valid @RequestBody EquipmentTypeRequest request) {
        return mapper.toResponse(service.save(mapper.toEntity(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EquipmentTypeResponse update(@PathVariable Long id, @Valid @RequestBody EquipmentTypeRequest request) {
        EquipmentType existing = service.findById(id);
        mapper.updateEntity(request, existing);
        return mapper.toResponse(service.save(existing));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) { service.deleteById(id); }
}
```

- [ ] **Step 3: Compile + smoke-test через curl**
```bash
./gradlew bootRun &  # отдельный терминал
# подождать старта
curl -i -s -u admin:admin -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' \
  http://localhost:8080/api/auth/login | head -10
# скопировать JSESSIONID
curl -s -b 'JSESSIONID=<id>' http://localhost:8080/api/equipment-types
# должны прийти 4 типа
```

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/vladoose/nir/service/EquipmentTypeService.java src/main/java/com/vladoose/nir/controller/EquipmentTypeController.java
git commit -m "feat: EquipmentType CRUD endpoint + защита от удаления"
```

---

### Task 3.2: BulkPriceRequestService + тесты

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/BulkPriceRequestService.java`
- Create: `src/test/java/com/vladoose/nir/service/BulkPriceRequestServiceTest.java`

- [ ] **Step 1: Скелет сервиса**
```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BulkPriceRequestService {

    private final TenderRepository tenderRepository;
    private final TenderLotRepository tenderLotRepository;
    private final MedEquipmentService medEquipmentService;
    private final DistributorRepository distributorRepository;
    private final PriceRequestRepository priceRequestRepository;
    private final PriceRequestItemRepository priceRequestItemRepository;
    private final EmailService emailService;

    public BulkPriceRequestService(TenderRepository tr, TenderLotRepository tlr,
                                    MedEquipmentService mes, DistributorRepository dr,
                                    PriceRequestRepository prr, PriceRequestItemRepository prir,
                                    EmailService es) {
        this.tenderRepository = tr; this.tenderLotRepository = tlr; this.medEquipmentService = mes;
        this.distributorRepository = dr; this.priceRequestRepository = prr;
        this.priceRequestItemRepository = prir; this.emailService = es;
    }

    public record GroupItem(TenderLot lot, MedEquipment equipment, boolean exceedsBudget) {}
    public record DistributorGroup(Distributor distributor, List<GroupItem> items) {}
    public record Preview(List<DistributorGroup> groups, List<TenderLot> lotsWithoutMatch, List<TenderLot> lotsWithoutDistributor) {}

    public Preview buildPreview(Long tenderId) {
        Tender tender = tenderRepository.findById(tenderId).orElseThrow(() -> new NotFoundException("Тендер не найден"));
        List<TenderLot> lots = tenderLotRepository.findByTenderId(tenderId);
        Map<Long, List<GroupItem>> byDistributor = new LinkedHashMap<>();
        List<TenderLot> lotsNoMatch = new ArrayList<>();
        List<TenderLot> lotsNoDist = new ArrayList<>();

        for (TenderLot lot : lots) {
            List<MedEquipment> models = medEquipmentService.findMatchingForLot(lot);
            if (models.isEmpty()) { lotsNoMatch.add(lot); continue; }
            if (lot.getEquipmentType() == null) { lotsNoMatch.add(lot); continue; }
            List<Distributor> eligible = distributorRepository.findEligibleForType(lot.getEquipmentType().getId());
            if (eligible.isEmpty()) { lotsNoDist.add(lot); continue; }
            for (Distributor d : eligible) {
                for (MedEquipment m : models) {
                    boolean exceeds = lot.getMaxCost() != null && m.getCost() != null
                            && java.math.BigDecimal.valueOf(m.getCost()).compareTo(lot.getMaxCost()) > 0;
                    byDistributor.computeIfAbsent(d.getId(), k -> new ArrayList<>()).add(new GroupItem(lot, m, exceeds));
                }
            }
        }

        List<DistributorGroup> groups = byDistributor.entrySet().stream()
                .map(e -> new DistributorGroup(distributorRepository.findById(e.getKey()).orElseThrow(), e.getValue()))
                .collect(Collectors.toList());
        return new Preview(groups, lotsNoMatch, lotsNoDist);
    }

    @Transactional
    public PriceRequest sendGroup(Long tenderId, Long distributorId, List<SendItem> items) {
        Tender tender = tenderRepository.findById(tenderId).orElseThrow();
        Distributor dist = distributorRepository.findById(distributorId).orElseThrow();

        PriceRequest pr = PriceRequest.builder()
                .tender(tender)
                .distributor(dist)
                .status("SENT")
                .sentAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build();
        priceRequestRepository.save(pr);

        for (SendItem si : items) {
            TenderLot lot = new TenderLot(); lot.setId(si.tenderLotId());
            MedEquipment eq = new MedEquipment(); eq.setId(si.medEquipmentId());
            PriceRequestItem item = PriceRequestItem.builder()
                    .priceRequest(pr).tenderLot(lot).medEquipment(eq)
                    .requestedQuantity(si.requestedQuantity()).build();
            priceRequestItemRepository.save(item);
        }

        String body = buildEmailBody(tender, dist, items);
        emailService.sendEmail(dist.getEmail(),
                "Запрос КП по тендеру №" + tender.getTenderNumber(), body);
        return pr;
    }

    public record SendItem(Long tenderLotId, Long medEquipmentId, Integer requestedQuantity) {}

    String buildEmailBody(Tender tender, Distributor dist, List<SendItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("Уважаемый(ая) ").append(dist.getLastName() != null ? dist.getLastName() : "")
          .append(" ").append(dist.getFirstName() != null ? dist.getFirstName() : "").append("!\n\n");
        sb.append("ООО «Регион-Мед» по тендеру №").append(tender.getTenderNumber())
          .append(" просит предоставить КП на следующие позиции:\n\n");
        for (SendItem it : items) {
            TenderLot lot = tenderLotRepository.findById(it.tenderLotId()).orElseThrow();
            MedEquipment eq = medEquipmentService.findById(it.medEquipmentId());
            sb.append("- Лот ").append(lot.getLotNumber()).append(": ")
              .append(eq.getName()).append(" (").append(eq.getManufact()).append(") × ")
              .append(it.requestedQuantity()).append(" шт.\n");
        }
        sb.append("\nПросим указать: цену за единицу, сроки поставки, условия оплаты, гарантию.\n\n");
        sb.append("С уважением,\nООО «Регион-Мед»");
        return sb.toString();
    }
}
```

- [ ] **Step 2: Тест BulkPriceRequestServiceTest — группировка**

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class BulkPriceRequestServiceTest {

    @Autowired BulkPriceRequestService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired EquipmentTypeRepository typeRepository;
    @Autowired MedEquipmentRepository equipmentRepository;
    @Autowired DistributorRepository distributorRepository;

    @Test
    void preview_groups_by_distributor_and_skips_lots_without_match() {
        EquipmentType uziType = typeRepository.findByName("УЗИ").orElseThrow();
        Tender t = tenderRepository.save(Tender.builder()
                .tenderNumber("TEST-1").status("ACTIVE").purchaseType("ELECTRONIC_AUCTION")
                .deadline(java.time.LocalDate.now().plusDays(30)).build());
        TenderLot lotUzi = lotRepository.save(TenderLot.builder()
                .tender(t).lotNumber(1).equipName("УЗИ").quantity(2).maxCost(new BigDecimal("1000000"))
                .equipmentType(uziType).build());
        MedEquipment eqUzi = equipmentRepository.save(MedEquipment.builder()
                .name("УЗИ-X").manufact("M").cost(800000).equipmentType(uziType).build());
        Distributor specialist = distributorRepository.save(Distributor.builder()
                .name("УЗИ-Спец").equipmentTypes(List.of(uziType)).build());
        Distributor universal = distributorRepository.save(Distributor.builder()
                .name("Универсал").build());

        var preview = service.buildPreview(t.getId());

        assertThat(preview.groups()).hasSize(2);
        assertThat(preview.lotsWithoutMatch()).isEmpty();
    }
}
```

- [ ] **Step 3: Запустить тест**
```bash
./gradlew test --tests BulkPriceRequestServiceTest 2>&1 | tail -20
```
Ожидаем PASS. Если падает на NPE в каких-то полях — в Tender.builder и TenderLot.builder заполнить недостающие nullable=false поля.

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/vladoose/nir/service/BulkPriceRequestService.java src/test/
git commit -m "feat(service): BulkPriceRequestService — подбор и группировка"
```

---

### Task 3.3: BulkPriceController + DTOs

**Files:**
- Create: `src/main/java/com/vladoose/nir/controller/BulkPriceController.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/BulkPriceSendRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/BulkPricePreviewResponse.java`

- [ ] **Step 1: BulkPriceSendRequest**
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class BulkPriceSendRequest {
    @NotNull private Long tenderId;
    @NotNull private Long distributorId;
    @NotEmpty private List<Item> items;

    @Data
    public static class Item {
        @NotNull private Long tenderLotId;
        @NotNull private Long medEquipmentId;
        @NotNull @Positive private Integer requestedQuantity;
    }
}
```

- [ ] **Step 2: BulkPricePreviewResponse**
```java
package com.vladoose.nir.dto.response;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class BulkPricePreviewResponse {
    private List<Group> groups;
    private List<TenderLotShortResponse> lotsWithoutMatch;
    private List<TenderLotShortResponse> lotsWithoutDistributor;

    @Data
    public static class Group {
        private DistributorResponse distributor;
        private List<Item> items;
    }

    @Data
    public static class Item {
        private TenderLotShortResponse lot;
        private MedEquipmentResponse equipment;
        private BigDecimal lotMaxCost;
        private boolean exceedsBudget;
    }
}
```

- [ ] **Step 3: BulkPriceController**
```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.BulkPriceSendRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.mapper.*;
import com.vladoose.nir.service.BulkPriceRequestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bulk-price")
public class BulkPriceController {
    private final BulkPriceRequestService service;
    private final DistributorMapper distributorMapper;
    private final MedEquipmentMapper medEquipmentMapper;
    private final TenderLotMapper tenderLotMapper;

    public BulkPriceController(BulkPriceRequestService service, DistributorMapper dm,
                                MedEquipmentMapper mem, TenderLotMapper tlm) {
        this.service = service; this.distributorMapper = dm;
        this.medEquipmentMapper = mem; this.tenderLotMapper = tlm;
    }

    @GetMapping("/preview/{tenderId}")
    public BulkPricePreviewResponse preview(@PathVariable Long tenderId) {
        var raw = service.buildPreview(tenderId);
        BulkPricePreviewResponse r = new BulkPricePreviewResponse();
        r.setLotsWithoutMatch(tenderLotMapper.toShortResponseList(raw.lotsWithoutMatch()));
        r.setLotsWithoutDistributor(tenderLotMapper.toShortResponseList(raw.lotsWithoutDistributor()));
        r.setGroups(raw.groups().stream().map(g -> {
            var dto = new BulkPricePreviewResponse.Group();
            dto.setDistributor(distributorMapper.toResponse(g.distributor()));
            dto.setItems(g.items().stream().map(it -> {
                var i = new BulkPricePreviewResponse.Item();
                i.setLot(tenderLotMapper.toShortResponse(it.lot()));
                i.setEquipment(medEquipmentMapper.toResponse(it.equipment()));
                i.setLotMaxCost(it.lot().getMaxCost());
                i.setExceedsBudget(it.exceedsBudget());
                return i;
            }).collect(Collectors.toList()));
            return dto;
        }).collect(Collectors.toList()));
        return r;
    }

    @PostMapping("/send")
    public Long send(@Valid @RequestBody BulkPriceSendRequest req) {
        var items = req.getItems().stream()
                .map(i -> new BulkPriceRequestService.SendItem(i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
        var pr = service.sendGroup(req.getTenderId(), req.getDistributorId(), items);
        return pr.getId();
    }
}
```
Если в `TenderLotMapper` нет `toShortResponseList/toShortResponse` — добавить (signature по образцу `toResponseList`).

- [ ] **Step 4: Compile + Commit**
```bash
./gradlew compileJava 2>&1 | tail -10
git add src/main/java/com/vladoose/nir/controller/BulkPriceController.java src/main/java/com/vladoose/nir/dto/
git commit -m "feat: BulkPriceController с preview и send endpoints"
```

---

### Task 3.4: ApplyAutoFillService + endpoint + тест

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/ApplyAutoFillService.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/AutoFillResponse.java`
- Modify: `src/main/java/com/vladoose/nir/controller/ActivityApplyController.java`
- Create: `src/test/java/com/vladoose/nir/service/ApplyAutoFillServiceTest.java`

- [ ] **Step 1: AutoFillResponse**
```java
package com.vladoose.nir.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class AutoFillResponse {
    private int addedItems;
    private List<String> lotsWithoutResponse;  // строки "Лот N: УЗИ"
}
```

- [ ] **Step 2: ApplyAutoFillService**
```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.dto.response.AutoFillResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ApplyAutoFillService {

    private final ActivityApplyRepository applyRepository;
    private final TenderLotRepository lotRepository;
    private final PriceRequestItemRepository itemRepository;
    private final ApplyItemRepository applyItemRepository;

    public ApplyAutoFillService(ActivityApplyRepository ar, TenderLotRepository lr,
                                 PriceRequestItemRepository ir, ApplyItemRepository air) {
        this.applyRepository = ar; this.lotRepository = lr; this.itemRepository = ir;
        this.applyItemRepository = air;
    }

    @Transactional
    public AutoFillResponse autoFill(Long applyId) {
        ActivityApply apply = applyRepository.findById(applyId).orElseThrow();
        List<TenderLot> lots = lotRepository.findByTenderId(apply.getTender().getId());
        Set<Long> existingLotIds = applyItemRepository.findByApplyId(applyId).stream()
                .map(i -> i.getTenderLot() != null ? i.getTenderLot().getId() : null)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        int added = 0;
        List<String> missing = new ArrayList<>();
        for (TenderLot lot : lots) {
            if (existingLotIds.contains(lot.getId())) continue;
            List<PriceRequestItem> candidates = itemRepository.findByPriceRequest_Tender_Id(apply.getTender().getId()).stream()
                    .filter(i -> i.getTenderLot().getId().equals(lot.getId()))
                    .filter(i -> i.getResponsePrice() != null)
                    .toList();
            if (candidates.isEmpty()) {
                missing.add("Лот " + lot.getLotNumber() + ": " + lot.getEquipName());
                continue;
            }
            PriceRequestItem best = candidates.stream()
                    .min(Comparator.comparing(PriceRequestItem::getResponsePrice))
                    .orElseThrow();
            ApplyItem ai = ApplyItem.builder()
                    .apply(apply).tenderLot(lot)
                    .medEquipment(best.getMedEquipment())
                    .distributor(best.getPriceRequest().getDistributor())
                    .offeredCost(best.getResponsePrice())
                    .quantity(lot.getQuantity()).build();
            applyItemRepository.save(ai);
            added++;
        }

        AutoFillResponse resp = new AutoFillResponse();
        resp.setAddedItems(added);
        resp.setLotsWithoutResponse(missing);
        return resp;
    }
}
```

- [ ] **Step 3: Endpoint в ActivityApplyController**
```java
@PostMapping("/{id}/auto-fill")
public AutoFillResponse autoFill(@PathVariable Long id) {
    return autoFillService.autoFill(id);
}
```
Inject `ApplyAutoFillService autoFillService` в конструктор. Импорт `AutoFillResponse`.

- [ ] **Step 4: Тест ApplyAutoFillServiceTest**

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ApplyAutoFillServiceTest {

    @Autowired ApplyAutoFillService service;
    @Autowired TenderRepository tr;
    @Autowired TenderLotRepository lr;
    @Autowired ActivityApplyRepository ar;
    @Autowired EquipmentTypeRepository tpr;
    @Autowired MedEquipmentRepository er;
    @Autowired DistributorRepository dr;
    @Autowired PriceRequestRepository prr;
    @Autowired PriceRequestItemRepository prir;
    @Autowired ApplyItemRepository air;

    @Test
    void picks_cheapest_response_per_lot() {
        EquipmentType uzi = tpr.findByName("УЗИ").orElseThrow();
        Tender t = tr.save(Tender.builder().tenderNumber("X").status("ACTIVE").purchaseType("E").deadline(LocalDate.now().plusDays(10)).build());
        TenderLot lot = lr.save(TenderLot.builder().tender(t).lotNumber(1).equipName("УЗИ").quantity(2).equipmentType(uzi).build());
        MedEquipment eq = er.save(MedEquipment.builder().name("EQ").manufact("M").cost(800000).equipmentType(uzi).build());
        Distributor d1 = dr.save(Distributor.builder().name("D1").build());
        Distributor d2 = dr.save(Distributor.builder().name("D2").build());
        ActivityApply apply = ar.save(ActivityApply.builder().tender(t).status("DRAFT").build());

        PriceRequest pr1 = prr.save(PriceRequest.builder().tender(t).distributor(d1).status("RESPONDED").build());
        prir.save(PriceRequestItem.builder().priceRequest(pr1).tenderLot(lot).medEquipment(eq).requestedQuantity(2).responsePrice(new BigDecimal("900000")).build());
        PriceRequest pr2 = prr.save(PriceRequest.builder().tender(t).distributor(d2).status("RESPONDED").build());
        prir.save(PriceRequestItem.builder().priceRequest(pr2).tenderLot(lot).medEquipment(eq).requestedQuantity(2).responsePrice(new BigDecimal("850000")).build());

        var resp = service.autoFill(apply.getId());

        assertThat(resp.getAddedItems()).isEqualTo(1);
        var items = air.findByApplyId(apply.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getOfferedCost()).isEqualByComparingTo("850000");
        assertThat(items.get(0).getDistributor().getName()).isEqualTo("D2");
    }
}
```

- [ ] **Step 5: Запуск тестов**
```bash
./gradlew test --tests ApplyAutoFillServiceTest 2>&1 | tail -15
```

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/vladoose/nir/service/ApplyAutoFillService.java src/main/java/com/vladoose/nir/dto/response/AutoFillResponse.java src/main/java/com/vladoose/nir/controller/ActivityApplyController.java src/test/
git commit -m "feat: ApplyAutoFillService — автосборка из ответов КП"
```

---

### Task 3.5: EquipmentStatsService + endpoint

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/EquipmentStatsService.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/EquipmentStatsResponse.java`
- Modify: `src/main/java/com/vladoose/nir/controller/MedEquipmentController.java`

- [ ] **Step 1: EquipmentStatsResponse**
```java
package com.vladoose.nir.dto.response;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class EquipmentStatsResponse {
    private List<DistributorResponse> potentialDistributors;
    private Summary summary;
    private List<DistributorRating> ranking;
    private List<HistoryEntry> history;

    @Data public static class Summary {
        private int requestsCount;
        private int distinctDistributors;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        private BigDecimal avgPrice;
    }

    @Data public static class DistributorRating {
        private DistributorResponse distributor;
        private int responsesCount;
        private BigDecimal avgPrice;
    }

    @Data public static class HistoryEntry {
        private OffsetDateTime date;
        private DistributorResponse distributor;
        private String tenderNumber;
        private Integer requestedQuantity;
        private BigDecimal responsePrice;
        private String status;
    }
}
```

- [ ] **Step 2: EquipmentStatsService**
```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.EquipmentStatsResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.repository.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EquipmentStatsService {
    private final MedEquipmentRepository equipmentRepository;
    private final DistributorRepository distributorRepository;
    private final PriceRequestItemRepository itemRepository;
    private final DistributorMapper distributorMapper;

    public EquipmentStatsService(MedEquipmentRepository er, DistributorRepository dr,
                                  PriceRequestItemRepository ir, DistributorMapper dm) {
        this.equipmentRepository = er; this.distributorRepository = dr;
        this.itemRepository = ir; this.distributorMapper = dm;
    }

    public EquipmentStatsResponse buildStats(Long equipmentId) {
        MedEquipment eq = equipmentRepository.findById(equipmentId).orElseThrow();
        Long typeId = eq.getEquipmentType() != null ? eq.getEquipmentType().getId() : null;
        List<Distributor> potential = typeId != null ? distributorRepository.findEligibleForType(typeId) : List.of();

        List<PriceRequestItem> items = itemRepository.findByMedEquipmentId(equipmentId);
        List<PriceRequestItem> withPrice = items.stream().filter(i -> i.getResponsePrice() != null).toList();

        EquipmentStatsResponse r = new EquipmentStatsResponse();
        r.setPotentialDistributors(potential.stream().map(distributorMapper::toResponse).toList());

        var summary = new EquipmentStatsResponse.Summary();
        summary.setRequestsCount(items.size());
        summary.setDistinctDistributors((int) items.stream()
                .map(i -> i.getPriceRequest().getDistributor().getId()).distinct().count());
        if (!withPrice.isEmpty()) {
            BigDecimal min = withPrice.stream().map(PriceRequestItem::getResponsePrice).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal max = withPrice.stream().map(PriceRequestItem::getResponsePrice).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal sum = withPrice.stream().map(PriceRequestItem::getResponsePrice).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = sum.divide(BigDecimal.valueOf(withPrice.size()), 2, RoundingMode.HALF_UP);
            summary.setMinPrice(min); summary.setMaxPrice(max); summary.setAvgPrice(avg);
        }
        r.setSummary(summary);

        Map<Long, List<PriceRequestItem>> byDist = withPrice.stream()
                .collect(Collectors.groupingBy(i -> i.getPriceRequest().getDistributor().getId()));
        r.setRanking(byDist.entrySet().stream().map(e -> {
            var rating = new EquipmentStatsResponse.DistributorRating();
            Distributor d = e.getValue().get(0).getPriceRequest().getDistributor();
            rating.setDistributor(distributorMapper.toResponse(d));
            rating.setResponsesCount(e.getValue().size());
            BigDecimal sum = e.getValue().stream().map(PriceRequestItem::getResponsePrice).reduce(BigDecimal.ZERO, BigDecimal::add);
            rating.setAvgPrice(sum.divide(BigDecimal.valueOf(e.getValue().size()), 2, RoundingMode.HALF_UP));
            return rating;
        }).sorted(Comparator.comparing(EquipmentStatsResponse.DistributorRating::getAvgPrice)).toList());

        r.setHistory(items.stream()
                .sorted(Comparator.comparing((PriceRequestItem i) -> i.getPriceRequest().getCreatedAt()).reversed())
                .map(i -> {
                    var h = new EquipmentStatsResponse.HistoryEntry();
                    h.setDate(i.getPriceRequest().getCreatedAt());
                    h.setDistributor(distributorMapper.toResponse(i.getPriceRequest().getDistributor()));
                    h.setTenderNumber(i.getPriceRequest().getTender().getTenderNumber());
                    h.setRequestedQuantity(i.getRequestedQuantity());
                    h.setResponsePrice(i.getResponsePrice());
                    h.setStatus(i.getPriceRequest().getStatus());
                    return h;
                }).toList());

        return r;
    }
}
```

- [ ] **Step 3: Endpoint в MedEquipmentController**
```java
@GetMapping("/{id}/stats")
public EquipmentStatsResponse stats(@PathVariable Long id) {
    return statsService.buildStats(id);
}
```
Inject `EquipmentStatsService statsService` в конструктор.

- [ ] **Step 4: Commit**
```bash
./gradlew compileJava 2>&1 | tail -5
git add src/main/java/com/vladoose/nir/service/EquipmentStatsService.java src/main/java/com/vladoose/nir/dto/response/EquipmentStatsResponse.java src/main/java/com/vladoose/nir/controller/MedEquipmentController.java
git commit -m "feat: EquipmentStatsService — статистика и рейтинг для карточки"
```

---

### Task 3.6: PriceRequestController — работа с item-структурой

**Files:**
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java`
- Modify: `src/main/java/com/vladoose/nir/service/PriceRequestService.java`

- [ ] **Step 1: PriceRequestService — добавить методы updateItems, findByTender**
```java
@Transactional
public PriceRequest saveWithItems(PriceRequest pr, List<PriceRequestItem> items) {
    PriceRequest saved = repository.save(pr);
    for (PriceRequestItem it : items) { it.setPriceRequest(saved); itemRepository.save(it); }
    return saved;
}

@Transactional
public PriceRequest updateResponses(Long prId, List<ItemResponseUpdate> updates) {
    PriceRequest pr = repository.findById(prId).orElseThrow();
    for (ItemResponseUpdate u : updates) {
        PriceRequestItem item = itemRepository.findById(u.itemId()).orElseThrow();
        item.setResponsePrice(u.responsePrice());
        item.setResponseNote(u.responseNote());
        itemRepository.save(item);
    }
    if (updates.stream().anyMatch(u -> u.responsePrice() != null) && "SENT".equals(pr.getStatus())) {
        pr.setStatus("RESPONDED");
        pr.setResponseDate(java.time.LocalDate.now());
    }
    return repository.save(pr);
}

public record ItemResponseUpdate(Long itemId, java.math.BigDecimal responsePrice, String responseNote) {}

public List<PriceRequest> findByTenderId(Long tenderId) { return repository.findByTenderId(tenderId); }
```
Inject `PriceRequestItemRepository itemRepository`.

- [ ] **Step 2: PriceRequestController — переписать**

Endpoints:
- `GET /api/price-requests` — все.
- `GET /api/price-requests/by-tender/{tenderId}` — по тендеру.
- `GET /api/price-requests/by-lot/{lotId}` — для совместимости с фронтом, фильтрация на бэке через items.
- `PUT /api/price-requests/{id}/responses` — массовое обновление ответов.
- `POST /api/price-requests/{id}/close` — установить status=CLOSED.
- `DELETE /api/price-requests/{id}` — удалить.

Полный пример endpoint'а обновления ответов:
```java
@PutMapping("/{id}/responses")
public PriceRequestResponse updateResponses(@PathVariable Long id, @RequestBody List<ItemResponseDto> updates) {
    var list = updates.stream()
            .map(u -> new PriceRequestService.ItemResponseUpdate(u.itemId(), u.responsePrice(), u.responseNote()))
            .toList();
    return mapper.toResponse(service.updateResponses(id, list));
}

public record ItemResponseDto(Long itemId, java.math.BigDecimal responsePrice, String responseNote) {}
```

- [ ] **Step 3: Compile + curl-проверка**
```bash
./gradlew compileJava 2>&1 | tail -5
```

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/vladoose/nir/controller/PriceRequestController.java src/main/java/com/vladoose/nir/service/PriceRequestService.java
git commit -m "feat: PriceRequestController — работа с items + updateResponses"
```

---

## Phase 4: Frontend — справочники и формы

### Task 4.1: api.service — методы для новых endpoints

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`

- [ ] **Step 1: Добавить методы**

В конец `api.service.ts` (перед закрывающей `}`):
```typescript
// === Equipment Types ===
getEquipmentTypes(): Observable<any[]> { return this.getAll('equipment-types'); }
createEquipmentType(body: any): Observable<any> { return this.create('equipment-types', body); }
updateEquipmentType(id: number, body: any): Observable<any> { return this.update('equipment-types', id, body); }
deleteEquipmentType(id: number): Observable<void> { return this.delete('equipment-types', id); }

// === Bulk Price ===
bulkPricePreview(tenderId: number): Observable<any> {
  return this.http.get<any>(`${this.base}/bulk-price/preview/${tenderId}`);
}
bulkPriceSend(body: any): Observable<number> {
  return this.http.post<number>(`${this.base}/bulk-price/send`, body);
}

// === Equipment stats ===
getEquipmentStats(id: number): Observable<any> {
  return this.http.get<any>(`${this.base}/equipment/${id}/stats`);
}

// === Auto-fill apply ===
autoFillApply(applyId: number): Observable<any> {
  return this.http.post<any>(`${this.base}/applies/${applyId}/auto-fill`, {});
}

// === PriceRequest responses ===
updatePriceRequestResponses(id: number, updates: any[]): Observable<any> {
  return this.http.put<any>(`${this.base}/price-requests/${id}/responses`, updates);
}
getPriceRequestsByTender(tenderId: number): Observable<any[]> {
  return this.http.get<any[]>(`${this.base}/price-requests/by-tender/${tenderId}`);
}
```

- [ ] **Step 2: Commit**
```bash
git add frontend/src/app/services/api.service.ts
git commit -m "feat(frontend): api методы для новых endpoints"
```

---

### Task 4.2: Страница справочника типов

**Files:**
- Create: `frontend/src/app/pages/equipment-types/equipment-types.component.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/layout/layout.component.ts`

- [ ] **Step 1: EquipmentTypesComponent**

```typescript
import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';

@Component({
  selector: 'app-equipment-types',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule],
  template: `
    <h2>Типы оборудования</h2>
    <p class="subtitle">Справочник типов медицинского оборудования</p>

    <div class="toolbar">
      <button class="btn btn-add" *ngIf="!showForm" (click)="onAdd()">Добавить тип</button>
    </div>

    <form *ngIf="showForm" [formGroup]="form" (ngSubmit)="onSave()" class="edit-form">
      <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
      <label>Название *<input formControlName="name" /><span class="field-error" *ngIf="validationErrors.name">{{ validationErrors.name }}</span></label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="showForm = false">Отмена</button>
      </div>
    </form>

    <table *ngIf="types.length > 0">
      <thead><tr><th>Название</th><th>Действия</th></tr></thead>
      <tbody>
        <tr *ngFor="let t of types">
          <td>{{ t.name }}</td>
          <td class="actions">
            <button class="btn btn-edit" (click)="onEdit(t)">Редактировать</button>
            <button class="btn btn-delete" (click)="onDelete(t.id)">Удалить</button>
          </td>
        </tr>
      </tbody>
    </table>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .toolbar { margin-bottom: 16px; }
    table { width: 100%; border-collapse: collapse; max-width: 600px; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    .actions { white-space: nowrap; }
    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; margin-right: 4px; }
    .btn-add, .btn-save { background: #1a56db; color: #fff; }
    .btn-cancel { background: #e5e7eb; color: #374151; }
    .btn-edit { background: #f59e0b; color: #fff; }
    .btn-delete { background: #ef4444; color: #fff; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 400px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; }
    .edit-form input { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; }
    .form-actions { margin-top: 16px; }
    .field-error { color: #dc2626; font-size: 12px; margin-top: 2px; display: block; }
    .error-banner { background: #fee2e2; color: #991b1b; padding: 8px 12px; border-radius: 4px; margin-bottom: 12px; font-size: 13px; }
  `]
})
export class EquipmentTypesComponent {
  types: any[] = [];
  validationErrors: any = {};
  showForm = false;
  editingId: number | null = null;
  form = new FormGroup({ name: new FormControl('', Validators.required) });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService) {
    this.load();
  }

  load() {
    this.api.getEquipmentTypes().subscribe({
      next: data => { this.types = data; this.cdr.detectChanges(); },
      error: err => this.notify.error('Ошибка загрузки: ' + (err.error?.message || err.message))
    });
  }

  onAdd() { this.editingId = null; this.form.reset(); this.validationErrors = {}; this.showForm = true; }
  onEdit(t: any) { this.editingId = t.id; this.form.patchValue(t); this.validationErrors = {}; this.showForm = true; }

  onSave() {
    const body = this.form.value;
    const wasEditing = this.editingId !== null;
    const req = this.editingId
      ? this.api.updateEquipmentType(this.editingId, body)
      : this.api.createEquipmentType(body);
    req.subscribe({
      next: () => {
        this.showForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Тип обновлён' : 'Тип добавлен');
        this.load();
      },
      error: (err: any) => {
        if (err.status === 400 && err.error?.errors) { this.validationErrors = err.error.errors; }
        else if (err.status === 400 && err.error?.message) { this.validationErrors = { _general: err.error.message }; }
        else { this.validationErrors = { _general: 'Ошибка сохранения' }; }
        this.cdr.detectChanges();
      }
    });
  }

  onDelete(id: number) {
    this.confirm.ask('Удалить тип оборудования?', 'Если тип используется — удалить нельзя.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.deleteEquipmentType(id).subscribe({
          next: () => { this.notify.success('Тип удалён'); this.load(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
```

- [ ] **Step 2: Добавить роут с adminGuard**

В `app.routes.ts` импорт + новый child:
```typescript
import { EquipmentTypesComponent } from './pages/equipment-types/equipment-types.component';
// внутри children:
{ path: 'equipment-types', component: EquipmentTypesComponent, canActivate: [adminGuard] },
```

- [ ] **Step 3: Пункт меню в layout**

В `layout.component.ts` в группе «Каталог» добавить ссылку (под admin-гардом, только для ADMIN видна):
```html
<a *ngIf="auth.isAdmin()" routerLink="/equipment-types" routerLinkActive="active">🏷️ Типы оборудования</a>
```

- [ ] **Step 4: ng build + commit**
```bash
cd frontend && npx ng build --configuration development 2>&1 | tail -5
git add frontend/src/app/pages/equipment-types/ frontend/src/app/app.routes.ts frontend/src/app/layout/layout.component.ts
git commit -m "feat(frontend): страница CRUD типов оборудования (ADMIN)"
```

---

### Task 4.3: Форма дистрибьютора со специализацией

**Files:**
- Modify: `frontend/src/app/pages/distributors/distributors.component.ts`

- [ ] **Step 1: Добавить state для типов и специализации**

В класс компонента добавить:
```typescript
allTypes: any[] = [];
selectedTypeIds: Set<number> = new Set();
get isUniversal(): boolean { return this.selectedTypeIds.size === 0; }
```
В конструкторе или ngOnInit загрузить:
```typescript
this.api.getEquipmentTypes().subscribe(types => { this.allTypes = types; this.cdr.detectChanges(); });
```

- [ ] **Step 2: Добавить FormControl и поведение**

Добавить в `form`:
```typescript
equipmentTypeIds: new FormControl<number[]>([])
```
В `onEdit`:
```typescript
this.selectedTypeIds = new Set((d.equipmentTypes || []).map((t: any) => t.id));
```
В `onAdd`:
```typescript
this.selectedTypeIds = new Set();
```

- [ ] **Step 3: HTML блок специализации**

В template, после поля «Сайт»:
```html
<label class="specialization-label">Специализация</label>
<div class="specialization-block">
  <label class="type-checkbox universal">
    <input type="checkbox" [checked]="isUniversal" [disabled]="isUniversal" (change)="onUniversalToggle($event)">
    <span>Все типы</span>
  </label>
  <label class="type-checkbox" *ngFor="let t of allTypes">
    <input type="checkbox" [checked]="selectedTypeIds.has(t.id)" (change)="onTypeToggle(t.id, $event)">
    <span>{{ t.name }}</span>
  </label>
</div>
```
Стили:
```css
.specialization-label { display: block; margin: 12px 0 6px; font-weight: 500; font-size: 14px; color: #374151; }
.specialization-block { display: flex; flex-wrap: wrap; gap: 8px 16px; padding: 8px 12px; background: #f9fafb; border-radius: 6px; border: 1px solid #e5e7eb; }
.type-checkbox { display: flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer; }
.type-checkbox.universal { font-weight: 600; }
.type-checkbox input[disabled] { opacity: 0.6; }
```

- [ ] **Step 4: Handlers + сохранение**

Методы:
```typescript
onUniversalToggle(_e: Event) {
  this.selectedTypeIds.clear();
}
onTypeToggle(id: number, e: Event) {
  const checked = (e.target as HTMLInputElement).checked;
  if (checked) this.selectedTypeIds.add(id); else this.selectedTypeIds.delete(id);
}
```
В `onSave` перед отправкой:
```typescript
body.equipmentTypeIds = Array.from(this.selectedTypeIds);
```

- [ ] **Step 5: Колонка «Специализация» в таблице**

В `<thead>` добавить `<th>Специализация</th>` (перед действиями).
В `<tbody> <tr>` добавить:
```html
<td>
  <span *ngIf="!d.equipmentTypes || d.equipmentTypes.length === 0" class="tag tag-all">Все типы</span>
  <span *ngFor="let t of d.equipmentTypes" class="tag">{{ t.name }}</span>
</td>
```
Стили:
```css
.tag { display: inline-block; padding: 2px 8px; background: #e5e7eb; border-radius: 4px; font-size: 12px; margin-right: 4px; }
.tag-all { background: #fef3c7; color: #92400e; font-weight: 600; }
```

- [ ] **Step 6: ng build + commit**
```bash
git add frontend/src/app/pages/distributors/distributors.component.ts
git commit -m "feat(frontend): специализация дистрибьюторов в форме и таблице"
```

---

### Task 4.4: Форма оборудования — select типов из API + карточка-модал

**Files:**
- Modify: `frontend/src/app/pages/equipment/equipment.component.ts`
- Create: `frontend/src/app/components/equipment-detail-modal/equipment-detail-modal.component.ts`

- [ ] **Step 1: Загрузить типы в equipment.component**

В класс:
```typescript
allTypes: any[] = [];
```
В конструкторе:
```typescript
this.api.getEquipmentTypes().subscribe(t => { this.allTypes = t; this.cdr.detectChanges(); });
```

- [ ] **Step 2: Заменить хардкод select на ngFor**

В template заменить:
```html
<select formControlName="equipType">
  <option value="">— не выбран —</option>
  <option value="УЗИ">УЗИ</option>
  ...
</select>
```
на:
```html
<select formControlName="equipTypeId">
  <option [ngValue]="null">— не выбран —</option>
  <option *ngFor="let t of allTypes" [ngValue]="t.id">{{ t.name }}</option>
</select>
```
В `form` заменить `equipType: new FormControl('')` на `equipTypeId: new FormControl<number | null>(null)`.
В `onEdit` заменить patch на `this.form.patchValue({ ...e, equipTypeId: e.equipmentType?.id || null });`.

- [ ] **Step 3: Колонка «Тип» в таблице — показ через equipmentType.name**

Заменить `{{ e.equipType }}` на `{{ e.equipmentType?.name }}`.
В фильтре по типу (если есть) — аналогично.

- [ ] **Step 4: Создать EquipmentDetailModalComponent**

```typescript
import { Component, Input, Output, EventEmitter, OnChanges } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

@Component({
  selector: 'app-equipment-detail-modal',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div *ngIf="equipment" class="modal-overlay" (click)="close.emit()">
      <div class="modal" (click)="$event.stopPropagation()">
        <button class="close-btn" (click)="close.emit()">×</button>
        <h2>{{ equipment.name }}</h2>
        <p class="manufact">{{ equipment.manufact }} · {{ equipment.equipmentType?.name }}</p>

        <section>
          <h3>Характеристики</h3>
          <div class="chars">
            <div><b>Цена:</b> {{ formatPrice(equipment.cost) }} ₽</div>
            <div><b>Габариты:</b> {{ equipment.lengthMm || '—' }}×{{ equipment.widthMm || '—' }}×{{ equipment.heightMm || '—' }} мм</div>
            <div><b>Вес:</b> {{ equipment.weightKg || '—' }} кг</div>
            <div *ngIf="equipment.spec"><b>Спецификация:</b> {{ equipment.spec }}</div>
          </div>
        </section>

        <section *ngIf="stats">
          <h3>Потенциальные поставщики ({{ stats.potentialDistributors?.length || 0 }})</h3>
          <div *ngIf="!stats.potentialDistributors?.length" class="muted">Нет дистрибьюторов с подходящей специализацией</div>
          <table *ngIf="stats.potentialDistributors?.length">
            <tr *ngFor="let d of stats.potentialDistributors">
              <td>{{ d.name }}</td><td>{{ d.email || '—' }}</td><td>{{ d.phone || '—' }}</td>
            </tr>
          </table>
        </section>

        <section *ngIf="stats?.summary">
          <h3>Сводка</h3>
          <div class="summary">
            <div>Запрашивали <b>{{ stats.summary.requestsCount }}</b> раз у <b>{{ stats.summary.distinctDistributors }}</b> дистрибьюторов</div>
            <div *ngIf="stats.summary.minPrice">
              Цены ответов: от <b>{{ formatPrice(stats.summary.minPrice) }} ₽</b>
              до <b>{{ formatPrice(stats.summary.maxPrice) }} ₽</b>,
              средняя <b>{{ formatPrice(stats.summary.avgPrice) }} ₽</b>
            </div>
          </div>
        </section>

        <section *ngIf="stats?.ranking?.length">
          <h3>Рейтинг по этому оборудованию</h3>
          <table>
            <tr *ngFor="let r of stats.ranking; let i = index">
              <td>{{ i + 1 }}.</td>
              <td>{{ r.distributor.name }}</td>
              <td>{{ r.responsesCount }} ответ(ов)</td>
              <td>средняя <b>{{ formatPrice(r.avgPrice) }} ₽</b></td>
              <td *ngIf="i === 0" class="best">← лучший</td>
            </tr>
          </table>
        </section>

        <section *ngIf="stats?.history?.length">
          <h3>История запросов</h3>
          <table>
            <thead><tr><th>Дата</th><th>Дистрибьютор</th><th>Тендер</th><th>Кол-во</th><th>Цена ответа</th><th>Статус</th></tr></thead>
            <tr *ngFor="let h of stats.history">
              <td>{{ formatDate(h.date) }}</td>
              <td>{{ h.distributor.name }}</td>
              <td>{{ h.tenderNumber }}</td>
              <td>{{ h.requestedQuantity }}</td>
              <td>{{ h.responsePrice ? formatPrice(h.responsePrice) + ' ₽' : '—' }}</td>
              <td><span class="status status-{{h.status}}">{{ h.status }}</span></td>
            </tr>
          </table>
        </section>
      </div>
    </div>
  `,
  styles: [`
    .modal-overlay { position: fixed; inset: 0; background: rgba(17,24,39,0.5); display: flex; align-items: flex-start; justify-content: flex-end; z-index: 1000; }
    .modal { background: #fff; width: 720px; max-width: 90vw; height: 100vh; overflow-y: auto; padding: 24px 32px; position: relative; box-shadow: -8px 0 24px rgba(0,0,0,0.2); }
    .close-btn { position: absolute; top: 12px; right: 12px; background: none; border: none; font-size: 28px; cursor: pointer; color: #6b7280; }
    h2 { margin: 0; font-size: 22px; }
    .manufact { color: #6b7280; margin: 4px 0 16px; }
    section { margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid #e5e7eb; }
    section:last-child { border-bottom: none; }
    h3 { font-size: 15px; margin: 0 0 10px; color: #1a56db; }
    .chars div, .summary div { margin-bottom: 6px; font-size: 14px; }
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #f3f4f6; font-size: 13px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    .muted { color: #9ca3af; font-size: 13px; }
    .best { color: #059669; font-weight: 600; }
    .status { padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
    .status-SENT { background: #dbeafe; color: #1a56db; }
    .status-RESPONDED { background: #d1fae5; color: #065f46; }
    .status-CLOSED { background: #e5e7eb; color: #374151; }
  `]
})
export class EquipmentDetailModalComponent implements OnChanges {
  @Input() equipment: any = null;
  @Output() close = new EventEmitter<void>();
  stats: any = null;

  constructor(private api: ApiService, private notify: NotificationService) {}

  ngOnChanges() {
    if (this.equipment) {
      this.api.getEquipmentStats(this.equipment.id).subscribe({
        next: s => this.stats = s,
        error: () => this.stats = null
      });
    } else this.stats = null;
  }

  formatPrice(n: any): string { return n != null ? Number(n).toLocaleString('ru-RU') : '0'; }
  formatDate(d: string): string { return d ? new Date(d).toLocaleDateString('ru-RU') : '—'; }
}
```

- [ ] **Step 5: Подключить модал в equipment.component**

В `equipment.component.ts` impors:
```typescript
import { EquipmentDetailModalComponent } from '../../components/equipment-detail-modal/equipment-detail-modal.component';
```
В `imports` массива `@Component`: `EquipmentDetailModalComponent`.
Поле:
```typescript
detailEquipment: any = null;
```
В template над `<table>` или после:
```html
<app-equipment-detail-modal [equipment]="detailEquipment" (close)="detailEquipment = null"></app-equipment-detail-modal>
```
В `<tr>` таблицы — клик по строке открывает модал (но не на actions):
```html
<tr *ngFor="let e of filteredEquipment" (click)="detailEquipment = e">
```
В `<td class="actions">` добавить `(click)="$event.stopPropagation()"` чтобы клик по кнопкам не открывал модал.

- [ ] **Step 6: ng build + commit**
```bash
git add frontend/src/app/pages/equipment/ frontend/src/app/components/equipment-detail-modal/
git commit -m "feat(frontend): типы в select из API + модал-карточка оборудования"
```

---

## Phase 5: Frontend — фильтр по типу в заявке

### Task 5.1: applies.component — фильтр оборудования по типу лота

**Files:**
- Modify: `frontend/src/app/pages/applies/applies.component.ts`

- [ ] **Step 1: Computed-список filteredEquipmentForItem**

В класс добавить getter:
```typescript
get filteredEquipmentForItem(): any[] {
  const lotId = this.itemForm.value.tenderLotId;
  if (!lotId) return this.equipment;
  const lot = this.lots.find(l => l.id === +lotId!);
  if (!lot || !lot.equipmentType?.id) return this.equipment;
  return this.equipment.filter(e => e.equipmentType?.id === lot.equipmentType.id);
}

get currentLotTypeName(): string | null {
  const lotId = this.itemForm.value.tenderLotId;
  if (!lotId) return null;
  const lot = this.lots.find(l => l.id === +lotId!);
  return lot?.equipmentType?.name || null;
}
```

- [ ] **Step 2: Заменить ngFor в select оборудования**

В template форме позиции:
```html
<label>Оборудование
  <select formControlName="medEquipId">
    <option [ngValue]="null">— не выбрано —</option>
    <option *ngFor="let e of filteredEquipmentForItem" [ngValue]="e.id">{{ e.name }} ({{ e.manufact }})</option>
  </select>
  <div *ngIf="currentLotTypeName" class="filter-hint">Показаны только аппараты типа {{ currentLotTypeName }}</div>
</label>
```
Стиль:
```css
.filter-hint { font-size: 12px; color: #6b7280; margin-top: 4px; font-style: italic; }
```

- [ ] **Step 3: ng build + commit**
```bash
git add frontend/src/app/pages/applies/applies.component.ts
git commit -m "feat(frontend): фильтр оборудования по типу лота в форме позиции"
```

---

## Phase 6: Frontend — массовый подбор по тендеру

### Task 6.1: BulkPriceModalComponent

**Files:**
- Create: `frontend/src/app/pages/tenders/bulk-price-modal.component.ts`

- [ ] **Step 1: Компонент**

```typescript
import { Component, Input, Output, EventEmitter, OnChanges, ChangeDetectorRef } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

@Component({
  selector: 'app-bulk-price-modal',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div *ngIf="tenderId" class="overlay" (click)="close.emit()">
      <div class="modal" (click)="$event.stopPropagation()">
        <header><h2>Запрос КП по тендеру</h2><button class="close" (click)="close.emit()">×</button></header>

        <div *ngIf="loading" class="muted">Загрузка...</div>

        <ng-container *ngIf="preview">
          <section *ngIf="preview.lotsWithoutMatch?.length">
            <h3 class="warn">Лоты без подходящего оборудования</h3>
            <ul><li *ngFor="let l of preview.lotsWithoutMatch">Лот {{ l.lotNumber }}: {{ l.equipName }}</li></ul>
            <p class="hint">Добавьте подходящее оборудование в каталог или ослабьте требования лота.</p>
          </section>
          <section *ngIf="preview.lotsWithoutDistributor?.length">
            <h3 class="warn">Лоты без дистрибьюторов</h3>
            <ul><li *ngFor="let l of preview.lotsWithoutDistributor">Лот {{ l.lotNumber }}: {{ l.equipName }} ({{ l.equipmentType?.name }})</li></ul>
            <p class="hint">Настройте специализацию дистрибьюторов или добавьте нового с этим типом.</p>
          </section>

          <section *ngFor="let g of preview.groups" class="group" [class.sent]="sentGroupIds.has(g.distributor.id)">
            <header>
              <h3>{{ g.distributor.name }}</h3>
              <small>{{ g.distributor.email || 'нет email' }} · {{ g.distributor.phone || '' }}</small>
            </header>

            <div *ngFor="let it of g.items" class="item">
              <input type="checkbox" [(ngModel)]="checkedKeys[key(g.distributor.id, it.lot.id, it.equipment.id)]" [disabled]="sentGroupIds.has(g.distributor.id)">
              <span>Лот {{ it.lot.lotNumber }} ({{ it.lot.equipName }} × {{ it.lot.quantity }})</span>
              <span class="eq">{{ it.equipment.name }} ({{ it.equipment.manufact }})</span>
              <span class="price">{{ formatPrice(it.equipment.cost) }} ₽</span>
              <span *ngIf="it.exceedsBudget" class="warn" title="Превышает max_cost лота">⚠ выше лимита</span>
            </div>

            <div class="actions">
              <button *ngIf="!sentGroupIds.has(g.distributor.id)" class="btn-primary" (click)="send(g)">Отправить КП</button>
              <span *ngIf="sentGroupIds.has(g.distributor.id)" class="sent-label">✓ Отправлено</span>
            </div>
          </section>
        </ng-container>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17,24,39,0.6); display: flex; align-items: center; justify-content: center; z-index: 1000; }
    .modal { background: #fff; width: 1000px; max-width: 95vw; height: 90vh; overflow-y: auto; border-radius: 8px; padding: 24px 32px; }
    .modal > header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
    .close { background: none; border: none; font-size: 28px; cursor: pointer; }
    h2 { margin: 0; }
    section { margin-bottom: 20px; padding-bottom: 16px; border-bottom: 1px solid #e5e7eb; }
    section.group { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 16px; }
    section.group.sent { opacity: 0.6; }
    section.group > header { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 12px; }
    h3 { margin: 0; font-size: 16px; }
    h3.warn { color: #dc2626; }
    .item { display: grid; grid-template-columns: 24px 220px 1fr 120px 120px; gap: 12px; padding: 6px 0; font-size: 14px; align-items: center; }
    .eq { color: #374151; }
    .price { text-align: right; font-weight: 500; }
    .warn { color: #dc2626; font-size: 12px; }
    .hint { color: #6b7280; font-size: 12px; }
    .actions { margin-top: 12px; text-align: right; }
    .btn-primary { background: #1a56db; color: #fff; border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer; font-size: 14px; }
    .sent-label { color: #059669; font-weight: 600; }
    .muted { color: #6b7280; }
  `]
})
export class BulkPriceModalComponent implements OnChanges {
  @Input() tenderId: number | null = null;
  @Output() close = new EventEmitter<void>();

  preview: any = null;
  loading = false;
  checkedKeys: Record<string, boolean> = {};
  sentGroupIds = new Set<number>();

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnChanges() {
    if (!this.tenderId) { this.preview = null; this.checkedKeys = {}; this.sentGroupIds.clear(); return; }
    this.loading = true; this.preview = null;
    this.api.bulkPricePreview(this.tenderId).subscribe({
      next: data => {
        this.preview = data;
        // По умолчанию отмечены все кроме exceedsBudget
        for (const g of data.groups || []) for (const it of g.items || []) {
          this.checkedKeys[this.key(g.distributor.id, it.lot.id, it.equipment.id)] = !it.exceedsBudget;
        }
        this.loading = false; this.cdr.detectChanges();
      },
      error: err => { this.loading = false; this.notify.error('Ошибка подбора: ' + (err.error?.message || err.message)); }
    });
  }

  key(distId: number, lotId: number, eqId: number): string { return `${distId}_${lotId}_${eqId}`; }

  send(group: any) {
    const items = group.items
      .filter((it: any) => this.checkedKeys[this.key(group.distributor.id, it.lot.id, it.equipment.id)])
      .map((it: any) => ({ tenderLotId: it.lot.id, medEquipmentId: it.equipment.id, requestedQuantity: it.lot.quantity }));
    if (items.length === 0) { this.notify.error('Не выбрано ни одной позиции'); return; }
    this.api.bulkPriceSend({ tenderId: this.tenderId, distributorId: group.distributor.id, items }).subscribe({
      next: () => {
        this.notify.success('КП отправлено: ' + group.distributor.name);
        this.sentGroupIds.add(group.distributor.id);
        this.cdr.detectChanges();
      },
      error: err => this.notify.error('Ошибка отправки: ' + (err.error?.message || err.message))
    });
  }

  formatPrice(n: any): string { return n != null ? Number(n).toLocaleString('ru-RU') : '0'; }
}
```

- [ ] **Step 2: Подключить в tenders.component**

В `tenders.component.ts` импорт + добавить в `imports`. Поле:
```typescript
bulkPriceTenderId: number | null = null;
```
В template деталей тендера, рядом с «Добавить лот»:
```html
<button class="btn btn-add-bulk" *ngIf="lots.length > 0" (click)="bulkPriceTenderId = selectedTender.id">
  Запросить КП по всему тендеру
</button>
<app-bulk-price-modal [tenderId]="bulkPriceTenderId" (close)="bulkPriceTenderId = null; loadLots()"></app-bulk-price-modal>
```
Стиль:
```css
.btn-add-bulk { background: #8b5cf6; color: #fff; margin-left: 8px; }
```

- [ ] **Step 3: ng build + commit**
```bash
git add frontend/src/app/pages/tenders/
git commit -m "feat(frontend): массовый подбор КП по тендеру с группировкой"
```

---

### Task 6.2: Секция «Запросы КП» в тендере — карточки с items

**Files:**
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

- [ ] **Step 1: Заменить loadPriceRequests на новый endpoint**

```typescript
loadPriceRequests() {
  if (!this.selectedTender) return;
  this.api.getPriceRequestsByTender(this.selectedTender.id).subscribe({
    next: data => { this.priceRequests = data; this.cdr.detectChanges(); },
    error: () => this.priceRequests = []
  });
}
```

- [ ] **Step 2: Переписать template секции «Запросы КП»**

Старый плоский список заменить на карточки:
```html
<section *ngIf="priceRequests.length > 0">
  <h3>Запросы КП</h3>
  <div *ngFor="let pr of priceRequests" class="pr-card">
    <header (click)="pr._expanded = !pr._expanded">
      <strong>{{ pr.distributor?.name }}</strong>
      <span class="badge badge-pr-{{pr.status}}">{{ getPrStatusLabel(pr.status) }}</span>
      <small *ngIf="pr.sentAt">отправлено {{ formatDate(pr.sentAt) }}</small>
      <span class="counter">{{ pr.items?.length || 0 }} позиций</span>
    </header>
    <div *ngIf="pr._expanded" class="pr-body">
      <table>
        <thead><tr><th>Лот</th><th>Модель</th><th>Кол-во</th><th>Цена ответа</th><th>Заметка</th></tr></thead>
        <tr *ngFor="let it of pr.items">
          <td>{{ it.tenderLot?.lotNumber }} — {{ it.tenderLot?.equipName }}</td>
          <td>{{ it.medEquipment?.name }}</td>
          <td>{{ it.requestedQuantity }}</td>
          <td><input type="number" step="0.01" [(ngModel)]="it._editPrice" [ngModelOptions]="{standalone: true}" /></td>
          <td><input [(ngModel)]="it._editNote" [ngModelOptions]="{standalone: true}" /></td>
        </tr>
      </table>
      <div class="pr-actions">
        <button class="btn-save" (click)="saveResponses(pr)">Сохранить ответы</button>
      </div>
    </div>
  </div>
</section>
```
В TS добавить:
```typescript
saveResponses(pr: any) {
  const updates = (pr.items || []).map((it: any) => ({
    itemId: it.id,
    responsePrice: it._editPrice ?? it.responsePrice,
    responseNote: it._editNote ?? it.responseNote
  }));
  this.api.updatePriceRequestResponses(pr.id, updates).subscribe({
    next: () => { this.notify.success('Ответы сохранены'); this.loadPriceRequests(); },
    error: err => this.notify.error(err.error?.message || 'Ошибка сохранения')
  });
}
```
Перед открытием — копировать существующие значения:
```typescript
// в onClickExpand: it._editPrice = it.responsePrice; it._editNote = it.responseNote;
```
Или сделать в template `[ngModel]="it._editPrice ?? it.responsePrice"`.

- [ ] **Step 3: Удалить старые методы onSavePrUpdate / onUpdatePr / onAddToApply, addItemToApply, onSavePriceRequest, onRequestPrice** (фичу заменили на bulk + responses).

Найти и удалить эти методы и связанные state (priceRequestLotId, priceRequestForm, prUpdateForm, showPriceRequestForm, etc.). Аккуратно — это много кода, но workflow целиком переезжает на новый bulk.

- [ ] **Step 4: ng build + commit**
```bash
git add frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(frontend): секция КП с группировкой по запросам и редактированием ответов"
```

---

## Phase 7: Frontend — автосборка заявки

### Task 7.1: Кнопка «Собрать из КП» в заявке

**Files:**
- Modify: `frontend/src/app/pages/applies/applies.component.ts`

- [ ] **Step 1: Метод autoFill**

```typescript
onAutoFill() {
  this.confirm.ask('Собрать позиции из принятых КП?', 'Для каждого лота возьмётся самое дешёвое предложение. Лоты уже имеющиеся в заявке пропускаются.', { confirmLabel: 'Собрать' })
    .subscribe(ok => {
      if (!ok) return;
      this.api.autoFillApply(this.selectedApply.id).subscribe({
        next: (resp) => {
          this.notify.success(`Добавлено позиций: ${resp.addedItems}`);
          if (resp.lotsWithoutResponse?.length) {
            this.notify.info('Нет КП с ответом по: ' + resp.lotsWithoutResponse.join(', '));
          }
          this.loadItems();
        },
        error: err => this.notify.error(err.error?.message || 'Ошибка автосборки')
      });
    });
}
```

- [ ] **Step 2: Кнопка в template**

В детальной секции заявки, рядом с «Добавить позицию» (только для DRAFT):
```html
<button class="btn btn-autofill" *ngIf="selectedApply?.status === 'DRAFT' && !showItemForm" (click)="onAutoFill()">
  Собрать из КП
</button>
```
Стиль:
```css
.btn-autofill { background: #059669; color: #fff; margin-left: 8px; }
```
Также добавить метод `info` в NotificationService если ещё нет (уже есть).

- [ ] **Step 3: ng build + commit**
```bash
git add frontend/src/app/pages/applies/applies.component.ts
git commit -m "feat(frontend): кнопка «Собрать из КП» в черновике заявки"
```

---

## Phase 8: Финальная проверка

### Task 8.1: Smoke-test всего workflow

- [ ] **Step 1: Полная сборка**
```bash
./gradlew build -x test 2>&1 | tail -5
cd frontend && npx ng build 2>&1 | tail -5
```

- [ ] **Step 2: Запуск + ручной сценарий**
```bash
./gradlew bootRun &
cd frontend && npm start &
```
1. Войти как `admin` → создать новый тип «Дефибриллятор» через `/equipment-types`.
2. Открыть `Дистрибьюторы` → у одного отметить специализацию (например, только УЗИ), у другого оставить пустой = универсал.
3. Открыть `Каталог → клик по строке` → проверить модал с историей и потенциальными поставщиками.
4. Войти как `operator` → создать новый тендер с 2-3 лотами разных типов.
5. На тендере нажать «Запросить КП по всему тендеру» → проверить группировку. Снять лишние галочки, отправить КП одной группе. Проверить что в почте дистрибьютора пришло письмо со списком позиций.
6. В секции «Запросы КП» этого тендера — раскрыть карточку, ввести цены ответа, нажать «Сохранить ответы». Статус должен стать `RESPONDED`.
7. Создать черновик заявки на этот тендер → нажать «Собрать из КП» → позиции должны автозаполниться лучшими ценами.

- [ ] **Step 3: Final commit (если есть мелкие фиксы)**
```bash
git status
git commit -am "fix: smoke-test artifacts" || echo "ничего не правили"
```

---

## Self-Review Checklist (для исполнителя)

После прохождения всех задач:

- [ ] `./gradlew test` — все юнит-тесты зелёные
- [ ] `./gradlew build` — backend собирается
- [ ] `cd frontend && npx ng build` — frontend собирается
- [ ] В БД присутствует `equipment_type` с 4 строками
- [ ] Дистрибьютор-универсал (пустая специализация) попадает во ВСЕ группы массового подбора
- [ ] @PreAuthorize защищает `/api/equipment-types` POST/PUT/DELETE — operator получит 403
- [ ] При нажатии «Собрать из КП» на лоте, по которому нет ответов — приходит уведомление с перечнем
- [ ] Кнопка «Запросить КП по всему тендеру» не реагирует если у тендера нет лотов
- [ ] При повторном клике на «Собрать из КП» уже заполненные лоты не дублируются
