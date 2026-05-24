# Smart Matching + Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Превратить эндпоинт подбора оборудования из простого фильтра в полноценный SAW-скоринг (4 критерия, пресеты, пользовательские веса, объяснимость) и заменить эмодзи-«иконки» на консистентный набор Lucide Angular.

**Architecture:** Бэкенд — два новых сервиса (`EquipmentHistoryStatsService` собирает агрегаты из WON-заявок через существующий `ApplyItemEnricher`; `EquipmentScoringService` оркестрирует scoring + ранжирование). Контроллер получает новый `POST /api/equipment/match/{lotId}` с body `MatchRequest`, старый `GET` остаётся алиасом. Фронтенд — отдельный standalone-компонент `SmartMatchComponent` вместо инлайн-блока в `tenders.component.ts`; localStorage хранит выбор пресета/весов. Иконки — `lucide-angular` через `LucideAngularModule.pick({...})` в `app.config.ts`, замена эмодзи в layout/dashboard/applies/reports + nav-иконки в sidebar.

**Tech Stack:** Java 17, Spring Boot 3.5, JPA/Hibernate, JUnit 5 + AssertJ, PostgreSQL; Angular 21 standalone components, RxJS, lucide-angular.

**Spec:** `docs/superpowers/specs/2026-05-25-smart-matching-and-icons-design.md`

---

## Файловая структура

**Backend (new):**
- `src/main/java/com/vladoose/nir/dto/request/MatchRequest.java`
- `src/main/java/com/vladoose/nir/dto/response/EquipmentMatchResponse.java`
- `src/main/java/com/vladoose/nir/service/EquipmentHistoryStatsService.java`
- `src/main/java/com/vladoose/nir/service/EquipmentScoringService.java`
- `src/test/java/com/vladoose/nir/service/EquipmentHistoryStatsServiceTest.java`
- `src/test/java/com/vladoose/nir/service/EquipmentScoringServiceTest.java`

**Backend (modify):**
- `src/main/java/com/vladoose/nir/repository/ApplyItemRepository.java` — добавить `findByMedEquipmentIdIn`
- `src/main/java/com/vladoose/nir/repository/ActivityApplyRepository.java` — добавить `findByStatus`, `existsByStatus`
- `src/main/java/com/vladoose/nir/controller/MedEquipmentController.java` — добавить POST `/match/{lotId}`, переписать существующий GET как алиас

**Frontend (new):**
- `frontend/src/app/components/smart-match/smart-match.component.ts`

**Frontend (modify):**
- `frontend/src/app/services/api.service.ts` — `postMatchEquipment(...)`
- `frontend/src/app/pages/tenders/tenders.component.ts` — заменить инлайн `onMatch`-блок на `<app-smart-match>`
- `frontend/src/app/app.config.ts` — `LucideAngularModule.pick({...})`
- `frontend/src/app/layout/layout.component.ts` — иконки в sidebar
- `frontend/src/app/pages/dashboard/dashboard.component.ts` — заменить эмодзи
- `frontend/src/app/pages/applies/applies.component.ts` — заменить эмодзи
- `frontend/src/app/pages/reports/reports.component.ts` — заменить эмодзи
- `frontend/package.json` — `lucide-angular`

---

# Фаза A — Backend smart matching

## Task 1: DTO запроса (MatchRequest) + helper нормализации весов

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/MatchRequest.java`

- [ ] **Step 1: Создать MatchRequest.java**

```java
package com.vladoose.nir.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MatchRequest {

    public enum Preset { BALANCED, MAX_PROFIT, RELIABILITY, CUSTOM }

    private Preset preset = Preset.BALANCED;
    private Weights weights;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Weights {
        private int price;
        private int margin;
        private int track;
        private int dim;
    }

    /** Возвращает нормализованные веса [price, margin, track, dim] с Σ=1.0. */
    public double[] resolveWeights() {
        int[] raw = switch (preset) {
            case BALANCED    -> new int[] { 25, 25, 25, 25 };
            case MAX_PROFIT  -> new int[] { 35, 40, 15, 10 };
            case RELIABILITY -> new int[] { 15, 15, 50, 20 };
            case CUSTOM -> {
                if (weights == null) {
                    throw new IllegalArgumentException("weights required when preset=CUSTOM");
                }
                int[] w = new int[] { weights.price, weights.margin, weights.track, weights.dim };
                int sum = w[0] + w[1] + w[2] + w[3];
                if (sum == 0) {
                    throw new IllegalArgumentException("at least one weight must be > 0");
                }
                yield w;
            }
        };
        double sum = raw[0] + raw[1] + raw[2] + raw[3];
        return new double[] { raw[0] / sum, raw[1] / sum, raw[2] / sum, raw[3] / sum };
    }
}
```

- [ ] **Step 2: Скомпилировать**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/request/MatchRequest.java
git commit -m "feat(matching): MatchRequest DTO с пресетами и нормализацией весов"
```

---

## Task 2: DTO ответа (EquipmentMatchResponse)

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/EquipmentMatchResponse.java`

- [ ] **Step 1: Создать EquipmentMatchResponse.java**

```java
package com.vladoose.nir.dto.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class EquipmentMatchResponse {

    private Long lotId;
    private BigDecimal lotMaxCost;
    private boolean hasHistory;
    private String preset;
    private WeightsUsed weightsUsed;
    private List<Candidate> candidates;

    @Getter @Setter @NoArgsConstructor
    public static class WeightsUsed {
        private double price;
        private double margin;
        private double track;
        private double dim;
    }

    @Getter @Setter @NoArgsConstructor
    public static class Candidate {
        private Long equipmentId;
        private String name;
        private String manufact;
        private String equipType;
        private Integer lengthMm;
        private Integer widthMm;
        private Integer heightMm;
        private BigDecimal weightKg;
        private String spec;
        private double score;
        private int rank;
        private boolean recommended;
        private Breakdown breakdown;
        private BestDistributor bestDistributor;
        private BigDecimal estimatedPrice;
        private BigDecimal estimatedMargin;
    }

    @Getter @Setter @NoArgsConstructor
    public static class Breakdown {
        private SubScore price;
        private SubScore margin;
        private SubScore track;
        private SubScore dim;
    }

    @Getter @Setter @NoArgsConstructor
    public static class SubScore {
        private double value;
        private boolean noData;
        private String raw;

        public SubScore(double value, boolean noData, String raw) {
            this.value = value;
            this.noData = noData;
            this.raw = raw;
        }
    }

    @Getter @Setter @NoArgsConstructor
    public static class BestDistributor {
        private Long distributorId;
        private String name;
        private int dealsCount;
        private BigDecimal avgMarginPercent;
    }
}
```

- [ ] **Step 2: Скомпилировать**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/response/EquipmentMatchResponse.java
git commit -m "feat(matching): EquipmentMatchResponse DTO с breakdown и best distributor"
```

---

## Task 3: Репозитории — новые методы

**Files:**
- Modify: `src/main/java/com/vladoose/nir/repository/ApplyItemRepository.java`
- Modify: `src/main/java/com/vladoose/nir/repository/ActivityApplyRepository.java`

- [ ] **Step 1: Добавить `findByMedEquipmentIdIn` в ApplyItemRepository**

В файле `src/main/java/com/vladoose/nir/repository/ApplyItemRepository.java` после строки `boolean existsByTenderLotId(Long tenderLotId);` добавить:

```java
    List<ApplyItem> findByMedEquipmentIdIn(java.util.Collection<Long> ids);
```

Это Spring Data авто-метод, реализация генерируется по имени.

- [ ] **Step 2: Добавить `findByStatus` и `existsByStatus` в ActivityApplyRepository**

В файле `src/main/java/com/vladoose/nir/repository/ActivityApplyRepository.java` после строки `boolean existsByTenderId(Long tenderId);` добавить:

```java
    List<ActivityApply> findByStatus(String status);

    boolean existsByStatus(String status);
```

- [ ] **Step 3: Скомпилировать**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/vladoose/nir/repository/ApplyItemRepository.java src/main/java/com/vladoose/nir/repository/ActivityApplyRepository.java
git commit -m "feat(matching): репозиторные методы findByStatus/findByMedEquipmentIdIn"
```

---

## Task 4: EquipmentHistoryStatsService + тесты

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/EquipmentHistoryStatsService.java`
- Test: `src/test/java/com/vladoose/nir/service/EquipmentHistoryStatsServiceTest.java`

Идея: один публичный метод `collect(equipmentIds, equipTypeIds)` делает 2 прохода — (1) все `apply_item` для avgOfferedCost; (2) WON-заявки + `ApplyItemEnricher` для wins/margin-by-type/best-distributor. Возвращает immutable `Stats`-объект с готовыми Map.

- [ ] **Step 1: Создать сервис со stub-реализацией (вернёт пустой Stats)**

Create `src/main/java/com/vladoose/nir/service/EquipmentHistoryStatsService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.repository.ActivityApplyRepository;
import com.vladoose.nir.repository.ApplyItemRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class EquipmentHistoryStatsService {

    private final ApplyItemRepository applyItemRepository;
    private final ActivityApplyRepository activityApplyRepository;
    private final ApplyItemEnricher enricher;

    public EquipmentHistoryStatsService(ApplyItemRepository ai,
                                         ActivityApplyRepository aa,
                                         ApplyItemEnricher e) {
        this.applyItemRepository = ai;
        this.activityApplyRepository = aa;
        this.enricher = e;
    }

    public Stats collect(Set<Long> equipmentIds, Set<Long> equipTypeIds) {
        Stats stats = new Stats();
        if (equipmentIds.isEmpty()) {
            return stats;
        }

        // Pass 1: avg offered cost (any status, only for equipmentIds we care about)
        Map<Long, BigDecimal> costSum = new HashMap<>();
        Map<Long, Integer> costCount = new HashMap<>();
        for (ApplyItem item : applyItemRepository.findByMedEquipmentIdIn(equipmentIds)) {
            if (item.getMedEquipment() == null || item.getOfferedCost() == null) continue;
            Long eId = item.getMedEquipment().getId();
            costSum.merge(eId, item.getOfferedCost(), BigDecimal::add);
            costCount.merge(eId, 1, Integer::sum);
        }
        costSum.forEach((eId, sum) -> {
            int n = costCount.getOrDefault(eId, 0);
            if (n > 0) {
                stats.avgOfferedCost.put(eId, sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP));
            }
        });

        // Pass 2: WON applies → enrich → wins, margin-by-type, best-distributor
        Map<Long, Set<Long>> winsByEquip = new HashMap<>();
        Map<Long, BigDecimal> marginSumByType = new HashMap<>();
        Map<Long, Integer> marginCountByType = new HashMap<>();
        Map<String, DistAgg> distAgg = new HashMap<>();

        for (ActivityApply apply : activityApplyRepository.findByStatus("WON")) {
            List<ApplyItem> items = applyItemRepository.findByApplyId(apply.getId());
            var enriched = enricher.toEnrichedResponseList(items);
            for (int i = 0; i < items.size(); i++) {
                ApplyItem item = items.get(i);
                MedEquipment me = item.getMedEquipment();
                if (me == null) continue;
                Long eId = me.getId();
                Long tId = me.getEquipmentType() != null ? me.getEquipmentType().getId() : null;
                Distributor d = item.getDistributor();
                BigDecimal marginPct = enriched.get(i).getMarginPercent();

                if (equipmentIds.contains(eId)) {
                    winsByEquip.computeIfAbsent(eId, k -> new HashSet<>()).add(apply.getId());
                }
                if (tId != null && equipTypeIds.contains(tId) && marginPct != null) {
                    marginSumByType.merge(tId, marginPct, BigDecimal::add);
                    marginCountByType.merge(tId, 1, Integer::sum);
                }
                if (equipmentIds.contains(eId) && d != null && marginPct != null) {
                    String key = eId + ":" + d.getId();
                    distAgg.computeIfAbsent(key, k -> new DistAgg(eId, d.getId(), d.getName()))
                            .accumulate(marginPct, apply.getId());
                }
            }
        }
        winsByEquip.forEach((eId, applies) -> stats.wins.put(eId, applies.size()));
        marginSumByType.forEach((tId, sum) -> {
            int n = marginCountByType.getOrDefault(tId, 0);
            if (n > 0) {
                stats.avgMarginByType.put(tId, sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP));
            }
        });
        // Best distributor per equipment: max avg margin
        Map<Long, DistAgg> bestPerEquip = new HashMap<>();
        for (DistAgg agg : distAgg.values()) {
            DistAgg current = bestPerEquip.get(agg.equipmentId);
            if (current == null || agg.avgMargin().compareTo(current.avgMargin()) > 0) {
                bestPerEquip.put(agg.equipmentId, agg);
            }
        }
        stats.bestDistributor.putAll(bestPerEquip);
        return stats;
    }

    public boolean hasAnyWon() {
        return activityApplyRepository.existsByStatus("WON");
    }

    public static class Stats {
        public final Map<Long, BigDecimal> avgOfferedCost = new HashMap<>();
        public final Map<Long, Integer> wins = new HashMap<>();
        public final Map<Long, BigDecimal> avgMarginByType = new HashMap<>();
        public final Map<Long, DistAgg> bestDistributor = new HashMap<>();
    }

    public static class DistAgg {
        public final Long equipmentId;
        public final Long distributorId;
        public final String distributorName;
        private BigDecimal sum = BigDecimal.ZERO;
        private int count = 0;
        private final Set<Long> appliesSeen = new HashSet<>();

        public DistAgg(Long equipmentId, Long distributorId, String distributorName) {
            this.equipmentId = equipmentId;
            this.distributorId = distributorId;
            this.distributorName = distributorName;
        }
        public void accumulate(BigDecimal marginPct, Long applyId) {
            sum = sum.add(marginPct);
            count++;
            appliesSeen.add(applyId);
        }
        public BigDecimal avgMargin() {
            return count == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        }
        public int dealsCount() { return appliesSeen.size(); }
    }
}
```

- [ ] **Step 2: Скомпилировать**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Написать failing-тест (cold start)**

Create `src/test/java/com/vladoose/nir/service/EquipmentHistoryStatsServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class EquipmentHistoryStatsServiceTest {

    @Autowired EquipmentHistoryStatsService service;
    @Autowired MedEquipmentRepository equipmentRepo;
    @Autowired EquipmentTypeRepository typeRepo;
    @Autowired ActivityApplyRepository applyRepo;
    @Autowired ApplyItemRepository itemRepo;
    @Autowired TenderRepository tenderRepo;
    @Autowired FacilityRepository facilityRepo;
    @Autowired DistributorRepository distRepo;
    @Autowired TenderLotRepository lotRepo;
    @Autowired PriceRequestRepository prRepo;
    @Autowired PriceRequestItemRepository prItemRepo;

    @Test
    void collect_returnsEmptyMapsWhenNoApplyItemsForGivenIds() {
        EquipmentType type = saveType("Дефибриллятор");
        MedEquipment eq = saveEquipment("Mindray D3", type);

        var stats = service.collect(Set.of(eq.getId()), Set.of(type.getId()));

        assertThat(stats.avgOfferedCost).isEmpty();
        assertThat(stats.wins).isEmpty();
        assertThat(stats.avgMarginByType).isEmpty();
        assertThat(stats.bestDistributor).isEmpty();
    }

    @Test
    void hasAnyWon_falseWhenNoWonApplies() {
        // создадим DRAFT-заявку — hasAnyWon должен оставаться false
        Facility f = saveFacility();
        Tender t = saveTender(f);
        ActivityApply apply = new ActivityApply();
        apply.setTender(t);
        apply.setStatus("DRAFT");
        apply.setCreatedAt(OffsetDateTime.now());
        applyRepo.save(apply);
        assertThat(service.hasAnyWon()).isFalse();
    }

    @Test
    void collect_aggregatesWinsAndAvgCostAndMargin() {
        Facility f = saveFacility();
        Tender t = saveTender(f);
        EquipmentType type = saveType("Дефибриллятор");
        MedEquipment eq = saveEquipment("Mindray D3", type);
        Distributor d = saveDistributor("МедТех Поставка");
        TenderLot lot = saveLot(t, type);

        // Заявка #1 — WON, оборудование eq, distributor d, offered=300000, procurement=200000 → margin% = 50
        ActivityApply a1 = saveWonApply(t);
        saveItem(a1, lot, eq, d, BigDecimal.valueOf(300000), 1);
        savePriceResponse(lot, eq, d, BigDecimal.valueOf(200000));

        // Заявка #2 — WON, оборудование eq, distributor d, offered=200000, procurement=150000 → margin% = 33.33
        ActivityApply a2 = saveWonApply(t);
        saveItem(a2, lot, eq, d, BigDecimal.valueOf(200000), 1);
        savePriceResponse(lot, eq, d, BigDecimal.valueOf(150000));

        var stats = service.collect(Set.of(eq.getId()), Set.of(type.getId()));

        assertThat(stats.avgOfferedCost.get(eq.getId()))
                .isEqualByComparingTo("250000.00"); // (300k+200k)/2
        assertThat(stats.wins.get(eq.getId())).isEqualTo(2);
        assertThat(stats.avgMarginByType.get(type.getId()))
                .isCloseTo(new BigDecimal("41.67"), org.assertj.core.api.Assertions.within(new BigDecimal("0.5")));
        assertThat(stats.bestDistributor.get(eq.getId())).isNotNull();
        assertThat(stats.bestDistributor.get(eq.getId()).distributorName).isEqualTo("МедТех Поставка");
        assertThat(service.hasAnyWon()).isTrue();
    }

    // --- helpers ---

    private EquipmentType saveType(String name) {
        EquipmentType t = new EquipmentType();
        t.setName(name + "-" + UUID.randomUUID());
        return typeRepo.save(t);
    }

    private MedEquipment saveEquipment(String name, EquipmentType type) {
        MedEquipment e = MedEquipment.builder()
                .name(name + "-" + UUID.randomUUID())
                .manufact("X")
                .equipmentType(type)
                .lengthMm(100).widthMm(100).heightMm(100)
                .weightKg(new BigDecimal("1.0"))
                .build();
        return equipmentRepo.save(e);
    }

    private Facility saveFacility() {
        Facility f = new Facility();
        f.setName("Test-" + UUID.randomUUID());
        f.setInn("0000000000");
        return facilityRepo.save(f);
    }

    private Tender saveTender(Facility f) {
        Tender t = new Tender();
        t.setFacility(f);
        t.setTenderNumber("T-" + UUID.randomUUID());
        t.setStatus("NEW");
        return tenderRepo.save(t);
    }

    private Distributor saveDistributor(String name) {
        Distributor d = new Distributor();
        d.setName(name + "-" + UUID.randomUUID());
        d.setInn("0000000000");
        return distRepo.save(d);
    }

    private TenderLot saveLot(Tender t, EquipmentType type) {
        TenderLot lot = new TenderLot();
        lot.setTender(t);
        lot.setLotNumber(1);
        lot.setEquipName("дефибриллятор");
        lot.setEquipmentType(type);
        lot.setQuantity(1);
        lot.setMaxCost(new BigDecimal("500000"));
        return lotRepo.save(lot);
    }

    private ActivityApply saveWonApply(Tender t) {
        ActivityApply a = new ActivityApply();
        a.setTender(t);
        a.setStatus("WON");
        a.setCreatedAt(OffsetDateTime.now());
        return applyRepo.save(a);
    }

    private ApplyItem saveItem(ActivityApply a, TenderLot lot, MedEquipment eq, Distributor d, BigDecimal cost, int qty) {
        ApplyItem ai = new ApplyItem();
        ai.setApply(a);
        ai.setTenderLot(lot);
        ai.setMedEquipment(eq);
        ai.setDistributor(d);
        ai.setOfferedCost(cost);
        ai.setQuantity(qty);
        return itemRepo.save(ai);
    }

    private void savePriceResponse(TenderLot lot, MedEquipment eq, Distributor d, BigDecimal responsePrice) {
        PriceRequest pr = new PriceRequest();
        pr.setTenderLot(lot);
        pr.setMedEquipment(eq);
        pr.setDistributor(d);
        pr.setStatus("RESPONDED");
        pr.setQuantity(1);
        prRepo.save(pr);

        PriceRequestItem pri = new PriceRequestItem();
        pri.setPriceRequest(pr);
        pri.setMedEquipment(eq);
        pri.setResponsePrice(responsePrice);
        pri.setQuantity(1);
        prItemRepo.save(pri);
    }
}
```

> ⚠ **Если поля PriceRequest/PriceRequestItem отличаются от использованных в `savePriceResponse`** — открой эти entity-классы (`src/main/java/com/vladoose/nir/entity/PriceRequest.java`, `PriceRequestItem.java`) и подгони сеттеры. Цель helper'а — создать связку (lot, equipment, distributor) с responsePrice, такую, чтобы `priceRequestItemRepository.findResponseFor(lot.id, eq.id, d.id)` (см. `ApplyItemEnricher`) вернула этот item первым.

- [ ] **Step 4: Запустить тест — должен пройти**

Run: `./gradlew test --tests EquipmentHistoryStatsServiceTest -i`
Expected: 3 теста PASS. Если поля entity отличаются — скомпилируется неудачно, чините как описано выше.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/EquipmentHistoryStatsService.java src/test/java/com/vladoose/nir/service/EquipmentHistoryStatsServiceTest.java
git commit -m "feat(matching): EquipmentHistoryStatsService собирает агрегаты из WON-заявок через ApplyItemEnricher"
```

---

## Task 5: EquipmentScoringService + тесты

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/EquipmentScoringService.java`
- Test: `src/test/java/com/vladoose/nir/service/EquipmentScoringServiceTest.java`

- [ ] **Step 1: Создать EquipmentScoringService**

Create `src/main/java/com/vladoose/nir/service/EquipmentScoringService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.EquipmentMatchResponse;
import com.vladoose.nir.dto.response.EquipmentMatchResponse.*;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EquipmentScoringService {

    private static final double RECOMMEND_THRESHOLD = 60.0;

    private final TenderLotRepository lotRepo;
    private final MedEquipmentRepository equipRepo;
    private final EquipmentHistoryStatsService statsService;

    public EquipmentScoringService(TenderLotRepository lotRepo,
                                    MedEquipmentRepository equipRepo,
                                    EquipmentHistoryStatsService statsService) {
        this.lotRepo = lotRepo;
        this.equipRepo = equipRepo;
        this.statsService = statsService;
    }

    public EquipmentMatchResponse scoreLot(Long lotId, double[] weights, String presetName) {
        TenderLot lot = lotRepo.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Lot not found: " + lotId));

        Long equipTypeId = lot.getEquipmentType() != null ? lot.getEquipmentType().getId() : null;
        List<MedEquipment> shortlist = equipRepo.findMatchingEquipment(
                equipTypeId, lot.getMaxLengthMm(), lot.getMaxWidthMm(), lot.getMaxHeightMm(), lot.getMaxWeightKg());

        Set<Long> equipIds = shortlist.stream().map(MedEquipment::getId).collect(Collectors.toSet());
        Set<Long> typeIds = shortlist.stream()
                .map(e -> e.getEquipmentType() != null ? e.getEquipmentType().getId() : null)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        var stats = statsService.collect(equipIds, typeIds);
        boolean hasHistory = statsService.hasAnyWon();

        List<Candidate> candidates = new ArrayList<>();
        for (MedEquipment e : shortlist) {
            Candidate c = buildCandidate(e, lot, stats, hasHistory, weights);
            candidates.add(c);
        }
        candidates.sort(Comparator.comparingDouble(Candidate::getScore).reversed());
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).setRank(i + 1);
        }
        if (!candidates.isEmpty() && candidates.get(0).getScore() >= RECOMMEND_THRESHOLD) {
            candidates.get(0).setRecommended(true);
        }

        EquipmentMatchResponse resp = new EquipmentMatchResponse();
        resp.setLotId(lotId);
        resp.setLotMaxCost(lot.getMaxCost());
        resp.setHasHistory(hasHistory);
        resp.setPreset(presetName);
        WeightsUsed wu = new WeightsUsed();
        wu.setPrice(weights[0]); wu.setMargin(weights[1]); wu.setTrack(weights[2]); wu.setDim(weights[3]);
        resp.setWeightsUsed(wu);
        resp.setCandidates(candidates);
        return resp;
    }

    private Candidate buildCandidate(MedEquipment e, TenderLot lot,
                                      EquipmentHistoryStatsService.Stats stats,
                                      boolean hasHistory, double[] w) {
        Long eId = e.getId();
        Long tId = e.getEquipmentType() != null ? e.getEquipmentType().getId() : null;

        // priceScore
        BigDecimal avgCost = stats.avgOfferedCost.get(eId);
        SubScore price;
        BigDecimal estimatedPrice = null;
        BigDecimal estimatedMargin = null;
        if (avgCost != null && lot.getMaxCost() != null && lot.getMaxCost().signum() > 0) {
            double ratio = avgCost.doubleValue() / lot.getMaxCost().doubleValue();
            double v = Math.max(0.0, Math.min(100.0, 100.0 * (1.0 - ratio)));
            price = new SubScore(round1(v), false,
                    String.format("avg оф. %s ₽ при потолке %s",
                            fmt(avgCost), fmt(lot.getMaxCost())));
            estimatedPrice = avgCost;
            estimatedMargin = lot.getMaxCost().subtract(avgCost);
        } else {
            price = new SubScore(50.0, true, "нет истории цен");
        }

        // marginScore
        BigDecimal avgMargin = tId != null ? stats.avgMarginByType.get(tId) : null;
        SubScore margin;
        if (avgMargin != null) {
            double v = Math.min(100.0, avgMargin.doubleValue() * 2.0);
            margin = new SubScore(round1(v), false,
                    String.format("ср. маржа по типу: %s %%", fmt(avgMargin)));
        } else {
            margin = new SubScore(50.0, true, "нет истории маржи");
        }

        // trackScore
        int wins = stats.wins.getOrDefault(eId, 0);
        double trackV = Math.min(100.0, 25.0 * (Math.log(wins + 1) / Math.log(2)));
        SubScore track = new SubScore(round1(trackV), false, "побед: " + wins);

        // dimScore
        SubScore dim = computeDimScore(e, lot);

        double score = w[0]*price.getValue() + w[1]*margin.getValue() + w[2]*track.getValue() + w[3]*dim.getValue();

        Candidate c = new Candidate();
        c.setEquipmentId(eId);
        c.setName(e.getName());
        c.setManufact(e.getManufact());
        c.setEquipType(e.getEquipmentType() != null ? e.getEquipmentType().getName() : null);
        c.setLengthMm(e.getLengthMm());
        c.setWidthMm(e.getWidthMm());
        c.setHeightMm(e.getHeightMm());
        c.setWeightKg(e.getWeightKg());
        c.setSpec(e.getSpec());
        c.setScore(round1(score));
        c.setRecommended(false);
        Breakdown b = new Breakdown();
        b.setPrice(price); b.setMargin(margin); b.setTrack(track); b.setDim(dim);
        c.setBreakdown(b);
        c.setEstimatedPrice(estimatedPrice);
        c.setEstimatedMargin(estimatedMargin);

        EquipmentHistoryStatsService.DistAgg best = stats.bestDistributor.get(eId);
        if (best != null) {
            BestDistributor bd = new BestDistributor();
            bd.setDistributorId(best.distributorId);
            bd.setName(best.distributorName);
            bd.setDealsCount(best.dealsCount());
            bd.setAvgMarginPercent(best.avgMargin());
            c.setBestDistributor(bd);
        }
        return c;
    }

    private SubScore computeDimScore(MedEquipment e, TenderLot lot) {
        double sumUsed = 0;
        int count = 0;
        if (lot.getMaxLengthMm() != null && lot.getMaxLengthMm() > 0 && e.getLengthMm() != null) {
            sumUsed += (double) e.getLengthMm() / lot.getMaxLengthMm(); count++;
        }
        if (lot.getMaxWidthMm() != null && lot.getMaxWidthMm() > 0 && e.getWidthMm() != null) {
            sumUsed += (double) e.getWidthMm() / lot.getMaxWidthMm(); count++;
        }
        if (lot.getMaxHeightMm() != null && lot.getMaxHeightMm() > 0 && e.getHeightMm() != null) {
            sumUsed += (double) e.getHeightMm() / lot.getMaxHeightMm(); count++;
        }
        if (lot.getMaxWeightKg() != null && lot.getMaxWeightKg().signum() > 0 && e.getWeightKg() != null) {
            sumUsed += e.getWeightKg().doubleValue() / lot.getMaxWeightKg().doubleValue(); count++;
        }
        if (count == 0) {
            return new SubScore(100.0, false, "габариты лота не заданы");
        }
        double avgUsed = sumUsed / count;
        double v = Math.max(0.0, 100.0 - 25.0 * avgUsed);
        return new SubScore(round1(v), false,
                String.format("загрузка габаритов: %d %%", (int) Math.round(avgUsed * 100)));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String fmt(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
```

> ⚠ Если `NotFoundException` имеет другую сигнатуру или находится в другом пакете — посмотрите `src/main/java/com/vladoose/nir/exception/`. Подойдёт любой 404-эксепшен, который уже используется в проекте.

- [ ] **Step 2: Скомпилировать**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Написать тесты для скоринга**

Create `src/test/java/com/vladoose/nir/service/EquipmentScoringServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.MatchRequest;
import com.vladoose.nir.dto.response.EquipmentMatchResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class EquipmentScoringServiceTest {

    @Autowired EquipmentScoringService scoring;
    @Autowired MedEquipmentRepository equipRepo;
    @Autowired EquipmentTypeRepository typeRepo;
    @Autowired TenderLotRepository lotRepo;
    @Autowired TenderRepository tenderRepo;
    @Autowired FacilityRepository facilityRepo;
    @Autowired ActivityApplyRepository applyRepo;
    @Autowired ApplyItemRepository itemRepo;
    @Autowired DistributorRepository distRepo;
    @Autowired PriceRequestRepository prRepo;
    @Autowired PriceRequestItemRepository prItemRepo;

    @Test
    void scoreLot_coldStart_dimScoreDrivesRanking() {
        EquipmentType type = saveType();
        MedEquipment big = saveEquipment("Big", type, 90, 90, 90, "1.0");  // занимает 90% габаритов
        MedEquipment small = saveEquipment("Small", type, 10, 10, 10, "1.0"); // занимает 10%
        TenderLot lot = saveLot(type, 100, 100, 100, "10.0", "500000");

        var resp = scoring.scoreLot(lot.getId(), new double[]{0.25,0.25,0.25,0.25}, "BALANCED");

        assertThat(resp.isHasHistory()).isFalse();
        assertThat(resp.getCandidates()).hasSize(2);
        assertThat(resp.getCandidates().get(0).getName()).startsWith("Small");
        assertThat(resp.getCandidates().get(0).getRank()).isEqualTo(1);
        assertThat(resp.getCandidates().get(0).getBreakdown().getPrice().isNoData()).isTrue();
    }

    @Test
    void scoreLot_recommendedBadgeOnlyWhenScoreAtLeast60() {
        EquipmentType type = saveType();
        // Только один кандидат, который занимает 100% габаритов (dim=75); cold start → score = (50+50+0+75)/4 = 43.75 < 60
        MedEquipment full = saveEquipment("Full", type, 100, 100, 100, "10.0");
        TenderLot lot = saveLot(type, 100, 100, 100, "10.0", "500000");

        var resp = scoring.scoreLot(lot.getId(), new double[]{0.25,0.25,0.25,0.25}, "BALANCED");

        assertThat(resp.getCandidates()).hasSize(1);
        assertThat(resp.getCandidates().get(0).isRecommended()).isFalse();
    }

    @Test
    void scoreLot_withHistory_topCandidateIsRecommended() {
        EquipmentType type = saveType();
        MedEquipment eq = saveEquipment("Profitable", type, 20, 20, 20, "1.0");
        Facility f = saveFacility();
        Tender t = saveTender(f);
        TenderLot lot = saveLot(type, 100, 100, 100, "10.0", "500000");
        lot.setTender(t); lotRepo.save(lot);
        Distributor d = saveDistributor();

        // выигранная заявка с маржой 50%
        ActivityApply a = saveWonApply(t);
        ApplyItem item = new ApplyItem();
        item.setApply(a); item.setTenderLot(lot); item.setMedEquipment(eq); item.setDistributor(d);
        item.setOfferedCost(new BigDecimal("300000")); item.setQuantity(1);
        itemRepo.save(item);
        savePriceResponse(lot, eq, d, new BigDecimal("200000")); // margin% = 50

        var resp = scoring.scoreLot(lot.getId(), new double[]{0.25,0.25,0.25,0.25}, "BALANCED");

        assertThat(resp.isHasHistory()).isTrue();
        assertThat(resp.getCandidates()).isNotEmpty();
        EquipmentMatchResponse.Candidate top = resp.getCandidates().get(0);
        assertThat(top.getName()).startsWith("Profitable");
        assertThat(top.isRecommended()).isTrue(); // score должен быть >= 60 благодаря маржe и track
        assertThat(top.getBestDistributor()).isNotNull();
        assertThat(top.getEstimatedPrice()).isEqualByComparingTo("300000.00");
    }

    @Test
    void matchRequest_resolveWeights_normalizesToSumOne() {
        MatchRequest r = new MatchRequest();
        r.setPreset(MatchRequest.Preset.CUSTOM);
        MatchRequest.Weights w = new MatchRequest.Weights();
        w.setPrice(50); w.setMargin(50); w.setTrack(0); w.setDim(0);
        r.setWeights(w);
        double[] resolved = r.resolveWeights();
        assertThat(resolved[0]).isEqualTo(0.5);
        assertThat(resolved[1]).isEqualTo(0.5);
        assertThat(resolved[2]).isEqualTo(0.0);
        assertThat(resolved[3]).isEqualTo(0.0);
    }

    @Test
    void matchRequest_customPresetWithZeroSum_throws() {
        MatchRequest r = new MatchRequest();
        r.setPreset(MatchRequest.Preset.CUSTOM);
        MatchRequest.Weights w = new MatchRequest.Weights();
        r.setWeights(w);
        org.assertj.core.api.Assertions.assertThatThrownBy(r::resolveWeights)
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---

    private EquipmentType saveType() {
        EquipmentType t = new EquipmentType();
        t.setName("T-" + UUID.randomUUID());
        return typeRepo.save(t);
    }

    private MedEquipment saveEquipment(String prefix, EquipmentType type, int L, int W, int H, String kg) {
        MedEquipment e = MedEquipment.builder()
                .name(prefix + "-" + UUID.randomUUID()).manufact("X")
                .equipmentType(type).lengthMm(L).widthMm(W).heightMm(H)
                .weightKg(new BigDecimal(kg)).build();
        return equipRepo.save(e);
    }

    private TenderLot saveLot(EquipmentType type, int maxL, int maxW, int maxH, String maxKg, String maxCost) {
        Facility f = saveFacility();
        Tender t = saveTender(f);
        TenderLot lot = new TenderLot();
        lot.setTender(t); lot.setLotNumber(1); lot.setEquipName("test");
        lot.setEquipmentType(type); lot.setQuantity(1);
        lot.setMaxCost(new BigDecimal(maxCost));
        lot.setMaxLengthMm(maxL); lot.setMaxWidthMm(maxW); lot.setMaxHeightMm(maxH);
        lot.setMaxWeightKg(new BigDecimal(maxKg));
        return lotRepo.save(lot);
    }

    private Facility saveFacility() {
        Facility f = new Facility(); f.setName("F-" + UUID.randomUUID()); f.setInn("0000000000");
        return facilityRepo.save(f);
    }

    private Tender saveTender(Facility f) {
        Tender t = new Tender(); t.setFacility(f); t.setTenderNumber("T-" + UUID.randomUUID()); t.setStatus("NEW");
        return tenderRepo.save(t);
    }

    private Distributor saveDistributor() {
        Distributor d = new Distributor(); d.setName("D-" + UUID.randomUUID()); d.setInn("0000000000");
        return distRepo.save(d);
    }

    private ActivityApply saveWonApply(Tender t) {
        ActivityApply a = new ActivityApply(); a.setTender(t); a.setStatus("WON");
        a.setCreatedAt(OffsetDateTime.now());
        return applyRepo.save(a);
    }

    private void savePriceResponse(TenderLot lot, MedEquipment eq, Distributor d, BigDecimal price) {
        PriceRequest pr = new PriceRequest();
        pr.setTenderLot(lot); pr.setMedEquipment(eq); pr.setDistributor(d);
        pr.setStatus("RESPONDED"); pr.setQuantity(1);
        prRepo.save(pr);
        PriceRequestItem pri = new PriceRequestItem();
        pri.setPriceRequest(pr); pri.setMedEquipment(eq); pri.setResponsePrice(price); pri.setQuantity(1);
        prItemRepo.save(pri);
    }
}
```

- [ ] **Step 4: Запустить тесты — все должны пройти**

Run: `./gradlew test --tests EquipmentScoringServiceTest -i`
Expected: 5 тестов PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/EquipmentScoringService.java src/test/java/com/vladoose/nir/service/EquipmentScoringServiceTest.java
git commit -m "feat(matching): EquipmentScoringService — SAW-скоринг кандидатов с 4 sub-scores"
```

---

## Task 6: Контроллер — POST endpoint + GET-алиас

**Files:**
- Modify: `src/main/java/com/vladoose/nir/controller/MedEquipmentController.java`

- [ ] **Step 1: Прочитать текущий контроллер**

Run: `cat src/main/java/com/vladoose/nir/controller/MedEquipmentController.java`

Найти существующий метод `/match/{lotId}` (GET) и метод-перехватчик, как делают другие контроллеры.

- [ ] **Step 2: Заменить GET-метод и добавить POST**

Найти в файле блок с `@GetMapping("/match/{lotId}")` (примерно строки 60-75) и заменить на:

```java
    @PostMapping("/match/{lotId}")
    public EquipmentMatchResponse smartMatch(@PathVariable Long lotId,
                                              @RequestBody(required = false) MatchRequest request) {
        MatchRequest req = request != null ? request : new MatchRequest();
        double[] weights = req.resolveWeights();
        return scoringService.scoreLot(lotId, weights, req.getPreset().name());
    }

    @GetMapping("/match/{lotId}")
    public EquipmentMatchResponse smartMatchDefault(@PathVariable Long lotId) {
        return scoringService.scoreLot(lotId, new MatchRequest().resolveWeights(), "BALANCED");
    }
```

Также добавить импорты в начало файла:
```java
import com.vladoose.nir.dto.request.MatchRequest;
import com.vladoose.nir.dto.response.EquipmentMatchResponse;
import com.vladoose.nir.service.EquipmentScoringService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
```

Добавить `EquipmentScoringService scoringService` в поля + констуктор (или через `final` + Lombok `@RequiredArgsConstructor`, если так же сделано в других контроллерах). Если уже есть `private final MedEquipmentService service` — добавить рядом, передать в конструкторе.

- [ ] **Step 3: Скомпилировать**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Запустить бэкенд и smoke-тестировать**

В одном терминале:
```bash
./gradlew bootRun
```

В другом:
```bash
# Дождаться "Started Nir2Application"
curl -s -X POST http://localhost:8080/api/equipment/match/1 \
  -H 'Content-Type: application/json' \
  -d '{"preset":"BALANCED"}' | python3 -m json.tool | head -30
```
Expected: JSON с полями `lotId`, `hasHistory`, `weightsUsed`, `candidates: [...]`. Кандидаты содержат `score`, `breakdown`, `rank`.

Также проверить GET-алиас:
```bash
curl -s http://localhost:8080/api/equipment/match/1 | python3 -m json.tool | head -10
```
Expected: тот же JSON с `preset: "BALANCED"`.

Если кандидатов нет — взять любой `lotId` из `curl -s http://localhost:8080/api/lots | python3 -m json.tool` (где задан maxLengthMm и т.п.), повторить.

Остановить бэкенд (Ctrl+C).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/controller/MedEquipmentController.java
git commit -m "feat(matching): POST /api/equipment/match/{lotId} с пресетами; GET оставлен как алиас"
```

---

# Фаза B — Frontend smart matching

## Task 7: API method postMatchEquipment

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`

- [ ] **Step 1: Добавить метод в api.service.ts**

Найти в файле метод `downloadProfitabilityExcel()`. Сразу под ним добавить:

```typescript
  postMatchEquipment(lotId: number, body: { preset: string; weights?: { price: number; margin: number; track: number; dim: number } }): Observable<any> {
    return this.http.post<any>(`${this.base}/equipment/match/${lotId}`, body);
  }
```

- [ ] **Step 2: Проверить TS-сборку**

Run: `cd frontend && npx ng build --configuration=development`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/services/api.service.ts
git commit -m "feat(matching): postMatchEquipment в api.service"
```

---

## Task 8: SmartMatchComponent (standalone)

**Files:**
- Create: `frontend/src/app/components/smart-match/smart-match.component.ts`

- [ ] **Step 1: Создать smart-match.component.ts**

```typescript
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime } from 'rxjs/operators';
import { ApiService } from '../../services/api.service';

type Preset = 'BALANCED' | 'MAX_PROFIT' | 'RELIABILITY' | 'CUSTOM';

interface Weights { price: number; margin: number; track: number; dim: number; }

const LS_KEY = 'smartMatch.v1';

@Component({
  selector: 'app-smart-match',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="sm-panel">
      <div class="sm-header">
        <strong>Подбор оборудования для лота №{{ lotNumber }}</strong>
        <button class="sm-close" (click)="close.emit()">✕</button>
      </div>

      <div class="sm-presets">
        <button [class.active]="preset === 'BALANCED'"    (click)="setPreset('BALANCED')">Баланс</button>
        <button [class.active]="preset === 'MAX_PROFIT'"  (click)="setPreset('MAX_PROFIT')">Макс. прибыль</button>
        <button [class.active]="preset === 'RELIABILITY'" (click)="setPreset('RELIABILITY')">Надёжность</button>
        <button [class.active]="preset === 'CUSTOM'"      (click)="setPreset('CUSTOM')">Свой</button>
      </div>

      <div class="sm-sliders" *ngIf="preset === 'CUSTOM'">
        <label>Цена   <input type="range" min="0" max="100" [(ngModel)]="weights.price"  (ngModelChange)="onSlider()"> {{ weights.price }}</label>
        <label>Маржа  <input type="range" min="0" max="100" [(ngModel)]="weights.margin" (ngModelChange)="onSlider()"> {{ weights.margin }}</label>
        <label>Опыт   <input type="range" min="0" max="100" [(ngModel)]="weights.track"  (ngModelChange)="onSlider()"> {{ weights.track }}</label>
        <label>Габар. <input type="range" min="0" max="100" [(ngModel)]="weights.dim"    (ngModelChange)="onSlider()"> {{ weights.dim }}</label>
        <span class="sm-sum">Σ = {{ weights.price + weights.margin + weights.track + weights.dim }}</span>
      </div>

      <div class="sm-coldstart" *ngIf="result && !result.hasHistory">
        ⚠ Истории сделок пока нет — рекомендации основаны только на габаритах.
      </div>

      <div class="sm-loading" *ngIf="loading">Загрузка…</div>

      <div class="sm-recommend" *ngIf="recommended">
        ⭐ Рекомендация СППР — <strong>{{ recommended.name }}</strong> ({{ recommended.score }} баллов)
        <span *ngIf="recommended.bestDistributor"> · лучший дистрибьютор: {{ recommended.bestDistributor.name }} (ср. маржа {{ recommended.bestDistributor.avgMarginPercent }} %)</span>
      </div>

      <table class="sm-table" *ngIf="result?.candidates?.length">
        <thead>
          <tr>
            <th>#</th><th>Наименование</th><th>Score</th><th>Цена</th><th>Маржа</th><th>Опыт</th><th>Габар.</th><th></th>
          </tr>
        </thead>
        <tbody>
          <ng-container *ngFor="let c of result.candidates">
            <tr [class.top]="c.recommended">
              <td>{{ c.rank }}</td>
              <td>{{ c.name }} <small>· {{ c.manufact }}</small></td>
              <td class="score">{{ c.score }}</td>
              <td><div class="bar"><span [style.width.%]="c.breakdown.price.value"></span></div></td>
              <td><div class="bar"><span [style.width.%]="c.breakdown.margin.value"></span></div></td>
              <td><div class="bar"><span [style.width.%]="c.breakdown.track.value"></span></div></td>
              <td><div class="bar"><span [style.width.%]="c.breakdown.dim.value"></span></div></td>
              <td><button (click)="toggle(c.equipmentId)">{{ expanded.has(c.equipmentId) ? '−' : '+' }}</button></td>
            </tr>
            <tr *ngIf="expanded.has(c.equipmentId)" class="expand">
              <td colspan="8">
                <ul>
                  <li>Цена: {{ c.breakdown.price.raw }}</li>
                  <li>Маржа: {{ c.breakdown.margin.raw }}</li>
                  <li>Опыт: {{ c.breakdown.track.raw }}</li>
                  <li>Габариты: {{ c.breakdown.dim.raw }}</li>
                </ul>
                <p *ngIf="c.estimatedPrice">Оценочная цена: {{ c.estimatedPrice }} ₽ · ожидаемая маржа: {{ c.estimatedMargin }} ₽</p>
                <button *ngIf="c.bestDistributor"
                        (click)="requestPrice.emit({ candidate: c, distributorId: c.bestDistributor.distributorId })">
                  Запросить КП у {{ c.bestDistributor.name }}
                </button>
              </td>
            </tr>
          </ng-container>
        </tbody>
      </table>

      <div *ngIf="result && !result.candidates?.length" class="sm-empty">Нет кандидатов под габариты лота.</div>
    </div>
  `,
  styles: [`
    .sm-panel { border: 1px solid #e5e7eb; border-radius: 8px; padding: 16px; background: #fff; margin: 12px 0; }
    .sm-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
    .sm-close { background: none; border: none; font-size: 16px; cursor: pointer; color: #6b7280; }
    .sm-presets { display: flex; gap: 8px; margin-bottom: 12px; }
    .sm-presets button { padding: 6px 12px; border: 1px solid #d1d5db; background: #f9fafb; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .sm-presets button.active { background: #1a56db; color: #fff; border-color: #1a56db; }
    .sm-sliders { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; padding: 12px; background: #f9fafb; border-radius: 6px; margin-bottom: 12px; }
    .sm-sliders label { display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .sm-sliders input { flex: 1; }
    .sm-sum { grid-column: span 2; text-align: right; font-weight: 600; color: #6b7280; }
    .sm-coldstart { background: #fef3c7; border: 1px solid #f59e0b; padding: 10px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; }
    .sm-recommend { background: #dbeafe; border: 1px solid #1a56db; padding: 10px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; }
    .sm-loading { padding: 20px; text-align: center; color: #6b7280; }
    .sm-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .sm-table th { background: #f3f4f6; padding: 8px; text-align: left; font-weight: 600; }
    .sm-table td { padding: 8px; border-bottom: 1px solid #e5e7eb; vertical-align: middle; }
    .sm-table tr.top td { background: #eff6ff; }
    .sm-table .score { font-weight: 700; }
    .sm-table .bar { width: 80px; height: 8px; background: #e5e7eb; border-radius: 4px; overflow: hidden; }
    .sm-table .bar span { display: block; height: 100%; background: #10b981; }
    .sm-table tr.expand td { background: #f9fafb; }
    .sm-table tr.expand ul { margin: 0; padding-left: 16px; }
    .sm-empty { text-align: center; color: #6b7280; padding: 20px; }
  `]
})
export class SmartMatchComponent implements OnChanges {
  @Input() lotId!: number;
  @Input() lotNumber: number = 0;
  @Output() close = new EventEmitter<void>();
  @Output() requestPrice = new EventEmitter<{ candidate: any; distributorId: number }>();

  preset: Preset = 'BALANCED';
  weights: Weights = { price: 25, margin: 25, track: 25, dim: 25 };
  result: any = null;
  loading = false;
  expanded = new Set<number>();
  private debouncer = new Subject<void>();

  constructor(private api: ApiService) {
    const saved = localStorage.getItem(LS_KEY);
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        if (parsed.preset) this.preset = parsed.preset;
        if (parsed.weights) this.weights = parsed.weights;
      } catch {}
    }
    this.debouncer.pipe(debounceTime(300)).subscribe(() => this.fetch());
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['lotId'] && this.lotId) {
      this.fetch();
    }
  }

  setPreset(p: Preset) {
    this.preset = p;
    this.save();
    this.fetch();
  }

  onSlider() {
    this.save();
    this.debouncer.next();
  }

  toggle(id: number) {
    if (this.expanded.has(id)) this.expanded.delete(id);
    else this.expanded.add(id);
  }

  get recommended(): any | null {
    if (!this.result?.candidates?.length) return null;
    const top = this.result.candidates[0];
    return top.recommended ? top : null;
  }

  private save() {
    localStorage.setItem(LS_KEY, JSON.stringify({ preset: this.preset, weights: this.weights }));
  }

  private fetch() {
    if (!this.lotId) return;
    this.loading = true;
    const body: any = { preset: this.preset };
    if (this.preset === 'CUSTOM') body.weights = this.weights;
    this.api.postMatchEquipment(this.lotId, body).subscribe({
      next: (r) => { this.result = r; this.loading = false; },
      error: () => { this.loading = false; this.result = null; }
    });
  }
}
```

- [ ] **Step 2: Скомпилировать**

Run: `cd frontend && npx ng build --configuration=development`
Expected: BUILD SUCCESS (компонент пока не используется — TS-ошибок быть не должно).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/components/smart-match/smart-match.component.ts
git commit -m "feat(matching): SmartMatchComponent — preset/slider toolbar + breakdown + cold-start banner"
```

---

## Task 9: Интегрировать SmartMatchComponent в tenders.component.ts

**Files:**
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

- [ ] **Step 1: Изучить текущий блок подбора**

Прочитать строки 195-220 и 625-660 (вокруг `onMatch` и кнопки «Подобрать»):

```bash
sed -n '195,220p;625,660p' frontend/src/app/pages/tenders/tenders.component.ts
```

Цель: понять, как сейчас отображается результат подбора (taблица под лотом), куда вставить новый компонент.

- [ ] **Step 2: Заменить инлайн-блок подбора на `<app-smart-match>`**

Найти место, где сейчас рендерится таблица результатов подбора (внутри template). Это блок типа `<table *ngIf="matchedEquipment?.length">` или similar. Заменить его на:

```html
<app-smart-match
  *ngIf="matchingLotId === l.id"
  [lotId]="l.id"
  [lotNumber]="l.lotNumber"
  (close)="closeMatch()"
  (requestPrice)="onSmartMatchRequest($event)">
</app-smart-match>
```

В TS-классе:
- Добавить импорт: `import { SmartMatchComponent } from '../../components/smart-match/smart-match.component';`
- В `@Component({ imports: [...] })` добавить `SmartMatchComponent`.
- Заменить старый `onMatch(lot)` на:
  ```typescript
  matchingLotId: number | null = null;

  onMatch(lot: any) {
    this.matchingLotId = lot.id;
  }

  closeMatch() {
    this.matchingLotId = null;
  }

  onSmartMatchRequest(ev: { candidate: any; distributorId: number }) {
    // Используем существующий workflow КП: открыть форму КП, предзаполнить equipment + distributor
    // Если в проекте есть метод `openPriceRequestForm(lotId, equipmentId, distributorId)` — вызвать его.
    // Иначе — TODO-stub: console.log + alert (фронт остаётся рабочим)
    console.log('TODO: hook to KP workflow', ev);
    alert('Запрос КП для ' + ev.candidate.name + ' у dist ' + ev.distributorId);
  }
  ```
- Удалить старые поля типа `matchedEquipment` и `currentMatchLot` (если использовались).

> ⚠ Если в компоненте используется storeForLot / какой-то особый state — НЕ удаляй его слепо. Цель — только заменить блок отрисовки результата на `<app-smart-match>`, остальное оставить.

- [ ] **Step 3: Проверить сборку**

Run: `cd frontend && npx ng build --configuration=development`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Ручной smoke-тест в браузере**

```bash
cd frontend && npx ng serve --open
```

В отдельном терминале запустить бэкенд:
```bash
./gradlew bootRun
```

В браузере:
1. Открыть страницу тендеров, развернуть любой тендер с лотами.
2. Нажать «Подобрать» на любой строке лота.
3. Убедиться: появилась панель с пресетами / cold-start банером / таблицей.
4. Переключить пресет «Макс. прибыль» — таблица перестроилась.
5. Выбрать «Свой» — появились слайдеры, подвигать — после ~300 мс таблица обновилась.
6. Перезагрузить страницу — сохранённый пресет применился (если был не BALANCED).
7. Раскрыть строку — видны breakdown.raw тексты.

Остановить серверы.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(matching): подключить SmartMatchComponent в strings тендера вместо инлайн-таблицы"
```

---

# Фаза C — Иконки (Lucide Angular)

## Task 10: Установить lucide-angular и зарегистрировать иконки

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/src/app/app.config.ts`

- [ ] **Step 1: Установить пакет**

Run:
```bash
cd frontend && npm install lucide-angular
```
Expected: пакет добавлен в `dependencies`.

- [ ] **Step 2: Зарегистрировать иконки в app.config.ts**

Заменить содержимое `frontend/src/app/app.config.ts`:

```typescript
import { ApplicationConfig, importProvidersFrom, provideBrowserGlobalErrorListeners, provideAppInitializer, provideZoneChangeDetection, inject } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  LucideAngularModule,
  LayoutDashboard, FileText, Stethoscope, ClipboardList, Building2, Truck,
  BarChart3, Mail, TrendingUp, FileSpreadsheet, Search, Plus, Pencil, Trash2,
  Filter, Settings, Calendar, Users, X, ChevronDown, ChevronUp, Star,
  Download, AlertTriangle, CheckCircle2, Clock, LogOut, User
} from 'lucide-angular';

import { routes } from './app.routes';
import { authInterceptor } from './interceptors/auth.interceptor';
import { AuthService } from './services/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    importProvidersFrom(
      LucideAngularModule.pick({
        LayoutDashboard, FileText, Stethoscope, ClipboardList, Building2, Truck,
        BarChart3, Mail, TrendingUp, FileSpreadsheet, Search, Plus, Pencil, Trash2,
        Filter, Settings, Calendar, Users, X, ChevronDown, ChevronUp, Star,
        Download, AlertTriangle, CheckCircle2, Clock, LogOut, User
      })
    ),
    provideAppInitializer(() => {
      const auth = inject(AuthService);
      return firstValueFrom(auth.loadCurrentUser());
    })
  ]
};
```

- [ ] **Step 3: Проверить сборку**

Run: `cd frontend && npx ng build --configuration=development`
Expected: BUILD SUCCESS, без unresolved imports.

- [ ] **Step 4: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/app/app.config.ts
git commit -m "feat(icons): подключить lucide-angular с tree-shake-набором иконок"
```

---

## Task 11: Замена эмодзи в layout / dashboard / applies / reports

**Files:**
- Modify: `frontend/src/app/layout/layout.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/applies/applies.component.ts`
- Modify: `frontend/src/app/pages/reports/reports.component.ts`

Для каждого файла шаги одинаковые: найти эмодзи и заменить на `<lucide-icon name="..." [size]="14"></lucide-icon>`. Так же добавить `LucideAngularModule` в `imports:` массив `@Component`.

- [ ] **Step 1: layout.component.ts — заменить эмодзи в sidebar**

В `frontend/src/app/layout/layout.component.ts`:
1. Добавить `import { LucideAngularModule } from 'lucide-angular';`
2. В `@Component({ imports: [...] })` добавить `LucideAngularModule`.
3. Найти все строки навигации с эмодзи и переписать примерно так:

| Было | Стало |
|---|---|
| текст «Дашборд» | `<lucide-icon name="layout-dashboard" [size]="16"></lucide-icon> Дашборд` |
| текст «Тендеры» | `<lucide-icon name="file-text" [size]="16"></lucide-icon> Тендеры` |
| текст «Оборудование» | `<lucide-icon name="stethoscope" [size]="16"></lucide-icon> Оборудование` |
| текст «Заявки» | `<lucide-icon name="clipboard-list" [size]="16"></lucide-icon> Заявки` |
| текст «Учреждения» | `<lucide-icon name="building-2" [size]="16"></lucide-icon> Учреждения` |
| текст «Дистрибьюторы» | `<lucide-icon name="truck" [size]="16"></lucide-icon> Дистрибьюторы` |
| текст «Отчёты» | `<lucide-icon name="bar-chart-3" [size]="16"></lucide-icon> Отчёты` |
| любая «🔔», «⚙» в шапке | соответствующий lucide-icon |

Иконки кнопок выхода: `<lucide-icon name="log-out" [size]="14"></lucide-icon>`. Иконка пользователя — `name="user"`.

Если в файле нет именно этих строк навигации — пробежать grep'ом `grep -n "Дашборд\|Тендеры\|Оборудование\|Заявки\|Учреждения\|Дистрибьюторы\|Отчёты" frontend/src/app/layout/layout.component.ts` и поправить найденные.

- [ ] **Step 2: dashboard.component.ts — заменить эмодзи**

Запустить `grep -n "📊\|📈\|💰\|🛒\|⚙\|🔍\|📋\|📅\|💼\|🏥\|✉\|📧\|🔔\|⭐\|❌\|✅\|📝\|🗂\|📁\|👤\|🏢" frontend/src/app/pages/dashboard/dashboard.component.ts`. Каждый найденный эмодзи заменить на соответствующий lucide-icon. Маппинг — см. таблицу спека §7.3 (`docs/superpowers/specs/2026-05-25-smart-matching-and-icons-design.md`). Добавить `LucideAngularModule` в imports.

- [ ] **Step 3: applies.component.ts — заменить эмодзи**

То же самое: grep, замена, импорт.

- [ ] **Step 4: reports.component.ts — заменить эмодзи**

То же самое. Особо: в кнопке Excel-экспорта `📊 Excel: прибыльность` → `<lucide-icon name="file-spreadsheet" [size]="14"></lucide-icon> Excel: прибыльность`.

- [ ] **Step 5: Проверить сборку**

Run: `cd frontend && npx ng build --configuration=development`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Проверить grep — эмодзи не остались**

Run:
```bash
grep -rn "📊\|📈\|💰\|🛒\|🔍\|📋\|📅\|💼\|🏥\|✉\|📧\|🔔\|❌\|✅\|📝\|🗂\|📁\|👤\|🏢" frontend/src/app --include="*.ts" --include="*.html"
```
Expected: пусто (или только в комментариях, если оставлены намеренно).

Допустимо оставить ⚠ (предупреждения), ⭐ (рекомендация), ✕ (закрытие) — они уже в SmartMatchComponent как Unicode-символы и не требуют замены. Но если хочется единого стиля — поменять на lucide (`alert-triangle`, `star`, `x`).

- [ ] **Step 7: Ручной smoke**

```bash
cd frontend && npx ng serve --open
```

Пройтись по основным страницам, убедиться: sidebar иконки рендерятся, заголовки секций красивые.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/layout/layout.component.ts frontend/src/app/pages/dashboard frontend/src/app/pages/applies frontend/src/app/pages/reports
git commit -m "feat(icons): заменить эмодзи на lucide-icon в layout/dashboard/applies/reports"
```

---

## Task 12: Иконки для действий таблиц (edit/delete/add)

Цель: добавить компактные иконки рядом с действиями в стандартных CRUD-таблицах (учреждения, дистрибьюторы, оборудование, пользователи).

**Files:**
- Modify (по необходимости): каждый CRUD-page компонент:
  - `frontend/src/app/pages/facilities/facilities.component.ts`
  - `frontend/src/app/pages/distributors/distributors.component.ts`
  - `frontend/src/app/pages/equipment/equipment.component.ts`
  - `frontend/src/app/pages/users/users.component.ts`

- [ ] **Step 1: Найти все CRUD-страницы**

Run: `ls frontend/src/app/pages/`
Expected: список папок. Открыть `.component.ts` каждой и найти кнопки `Удалить`, `Изменить`, `Добавить`.

- [ ] **Step 2: Заменить текст кнопок**

В каждой `.component.ts`:
- Добавить `import { LucideAngularModule } from 'lucide-angular';` + добавить в `imports`.
- `<button>Добавить</button>` → `<button><lucide-icon name="plus" [size]="14"></lucide-icon> Добавить</button>`
- `<button (click)="edit(x)">Изменить</button>` → `<button (click)="edit(x)" title="Изменить"><lucide-icon name="pencil" [size]="14"></lucide-icon></button>`
- `<button (click)="delete(x)">Удалить</button>` → `<button (click)="delete(x)" title="Удалить" class="btn-danger"><lucide-icon name="trash-2" [size]="14"></lucide-icon></button>`

Делать на каждой странице по очереди. Если в проекте нет одной из перечисленных страниц — пропустить.

- [ ] **Step 3: Сборка + ручная проверка**

Run: `cd frontend && npx ng build --configuration=development`
Expected: BUILD SUCCESS.

```bash
cd frontend && npx ng serve --open
```

Пройти по CRUD-страницам, убедиться, что иконки в кнопках действий рендерятся и не сломали layout.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/pages
git commit -m "feat(icons): добавить иконки edit/delete/add в CRUD-таблицы"
```

---

# Финальная проверка

- [ ] **Step 1: Полный билд бэка + теста**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (включая все тесты — `EquipmentHistoryStatsServiceTest`, `EquipmentScoringServiceTest`, и существующие).

- [ ] **Step 2: Полный билд фронта**

Run: `cd frontend && npx ng build`
Expected: BUILD SUCCESS, без warnings про missing icons.

- [ ] **Step 3: End-to-end smoke**

Запустить `./gradlew bootRun` + `cd frontend && npx ng serve --open`. Пройти полный путь:
1. Открыть тендер → лот → «Подобрать».
2. Переключить пресеты, подвигать слайдеры в CUSTOM.
3. Раскрыть строку, увидеть breakdown.
4. Если есть WON-заявки в БД — увидеть `recommended` бейдж на топе.
5. Если нет — увидеть cold-start банер.
6. Проверить sidebar — иконки на месте, навигация работает.
7. Открыть отчёты — кнопка Excel с lucide-иконкой.
8. Открыть CRUD-страницу — действия с иконками.

- [ ] **Step 4: Финальный коммит (если что-то ещё не закоммичено)**

```bash
git status
# Если есть unстейджед — закоммитить осмысленно (например, единый "polish" коммит)
```
