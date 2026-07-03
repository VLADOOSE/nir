# Запрос КП по лотам тендера + предложенная модель + единый канал отправки — план имплементации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Запрос КП у поставщиков по лотам тендера (оба рынка) с реальным письмом через единый market-aware канал; «предложенная модель» лота с апрувом из smart-match; парсер габаритов/веса из текста спеки; перевод всех существующих КП-флоу (частные заявки, smart-match, bulk-модалка) на единый канал.

**Architecture:** Один send-эндпоинт `POST /api/price-requests/send` → `PriceRequestSendService` (создание PriceRequest per поставщик + письмо) → `KpEmailComposer` (единственный генератор темы/тела, брендинг из `pr.getMarket()`). Nullable FK `tender_lot.proposed_equipment_id` (Flyway V4). `SpecConstraintExtractor` даёт констрейнты матчу каталога, когда структурные поля лота пусты. `LotSourcingService` аннотирует дистрибьюторов подсказками «возит бренд» (бренд предложенной модели / производители реестр-кандидатов НЦЭЛС).

**Tech Stack:** Java 17, Spring Boot 3.5.6, Flyway (V4), MapStruct, GreenMail (SMTP-тест), Angular 21 (standalone, инлайн-шаблоны).

**Spec:** `docs/superpowers/specs/2026-07-03-lot-price-request-design.md` (одобрена).

## Global Constraints

- Ветка: `feat/lot-price-request` (уже существует, спека закоммичена — `234d396`).
- **Sandbox:** ЛЮБЫЕ `./gradlew`-команды и psql — с `dangerouslyDisableSandbox: true` (песочница блокирует localhost:5432).
- Перед полным прогоном тестов глушить bootRun: `lsof -ti :8080 | xargs kill -9 || true`.
- Гейт «зелёного» для полного прогона: **только 2 известных падения** `ApplyAutoFillServiceTest` (autoFill_picksCheapestResponsePerLot, autoFill_reportsLotsWithoutResponse). Любое другое падение — регрессия, чинить.
- Фронт-гейт: `cd frontend && npm run build` (тестов нет).
- **Миграции: только новая `V4__lot_proposed_equipment.sql`** — V1/V2/V3 не трогать.
- `@FilterDef` объявлен ТОЛЬКО на `Tender` — не переобъявлять.
- Лоты создавать/удалять только через коллекцию `tender.getLots()`; **правка полей существующего лота через `tenderLotRepository.save(lot)` — безопасна** (этот план меняет только поле).
- Секреты (`/tmp/*.pass`, `/tmp/goszakup.token`) не эхо-печатать.
- Каждый commit заканчивать trailer-строкой: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Все субагенты — Opus 4.8 (`model: 'opus'`).
- В тестах, где создаются рыночные сущности: `MarketContext.set(Market.KZ)` в начале + `@AfterEach MarketContext.clear()`.
- Тестовые данные именовать с префиксом `ZZ` (уникальность против сида).

---

### Task 1: V4 + `TenderLot.proposedEquipment` + апрув-эндпоинты

**Files:**
- Create: `src/main/resources/db/migration/V4__lot_proposed_equipment.sql`
- Create: `src/main/java/com/vladoose/nir/dto/response/ProposedEquipmentResponse.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/ProposedEquipmentRequest.java`
- Modify: `src/main/java/com/vladoose/nir/entity/TenderLot.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java`
- Modify: `src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderLotController.java`
- Test: `src/test/java/com/vladoose/nir/tender/LotProposedEquipmentTest.java`

**Interfaces:**
- Produces: колонка `tender_lot.proposed_equipment_id`; `TenderLot.getProposedEquipment(): MedEquipment`; `TenderLotResponse.getProposedEquipment(): ProposedEquipmentResponse {id, name, manufact, registrationStatus, regNumber}`; `POST /api/lots/{id}/proposed-equipment {equipmentId}` и `DELETE /api/lots/{id}/proposed-equipment` (оба возвращают `TenderLotResponse`, ADMIN).
- Consumes: существующие `TenderLotService.findById/save`, `MedEquipmentService.findById`, `MarketContext`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/tender/LotProposedEquipmentTest.java`:

```java
package com.vladoose.nir.tender;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderLotController;
import com.vladoose.nir.dto.request.ProposedEquipmentRequest;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class LotProposedEquipmentTest {

    @Autowired TenderLotController controller;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    private TenderLot makeLot() {
        Tender t = new Tender();
        t.setTenderNumber("ZZ-PROP-" + System.nanoTime());
        t.setStatus("ACTIVE");
        tenderRepository.save(t);
        TenderLot lot = new TenderLot();
        lot.setTender(t);
        lot.setLotNumber(1);
        lot.setEquipName("ZZ Аппарат УЗИ");
        lot.setQuantity(2);
        return tenderLotRepository.save(lot);
    }

    private MedEquipment makeEquipment(String name) {
        MedEquipment e = new MedEquipment();
        e.setName(name);
        e.setManufact("Mindray");
        return medEquipmentRepository.save(e);
    }

    @Test
    void setReplaceAndClearProposedEquipment() {
        MarketContext.set(Market.KZ);
        TenderLot lot = makeLot();
        MedEquipment eq1 = makeEquipment("ZZ SonoMax 1");
        MedEquipment eq2 = makeEquipment("ZZ SonoMax 2");

        ProposedEquipmentRequest req = new ProposedEquipmentRequest();
        req.setEquipmentId(eq1.getId());
        TenderLotResponse r1 = controller.setProposedEquipment(lot.getId(), req);
        assertThat(r1.getProposedEquipment()).isNotNull();
        assertThat(r1.getProposedEquipment().getId()).isEqualTo(eq1.getId());
        assertThat(r1.getProposedEquipment().getManufact()).isEqualTo("Mindray");

        req.setEquipmentId(eq2.getId());
        TenderLotResponse r2 = controller.setProposedEquipment(lot.getId(), req);
        assertThat(r2.getProposedEquipment().getId()).isEqualTo(eq2.getId());

        TenderLotResponse r3 = controller.clearProposedEquipment(lot.getId());
        assertThat(r3.getProposedEquipment()).isNull();
        assertThat(tenderLotRepository.findById(lot.getId()).orElseThrow().getProposedEquipment()).isNull();
    }

    @Test
    void equipmentFromOtherMarketIsRejected() {
        MarketContext.set(Market.RF);
        MedEquipment rfEq = makeEquipment("ZZ RF-Only");
        MarketContext.set(Market.KZ);
        TenderLot lot = makeLot();

        ProposedEquipmentRequest req = new ProposedEquipmentRequest();
        req.setEquipmentId(rfEq.getId());
        assertThatThrownBy(() -> controller.setProposedEquipment(lot.getId(), req))
                .isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Прогнать тест — убедиться, что падает (компиляция)**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.tender.LotProposedEquipmentTest"`
Expected: FAIL — компиляция (нет `ProposedEquipmentRequest`, нет метода `setProposedEquipment`).

- [ ] **Step 3: Миграция V4**

`src/main/resources/db/migration/V4__lot_proposed_equipment.sql`:

```sql
-- Предложенная (одобренная оператором) модель каталога для лота тендера
ALTER TABLE tender_lot ADD COLUMN IF NOT EXISTS proposed_equipment_id BIGINT REFERENCES med_equipment(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_lot_proposed_equipment ON tender_lot(proposed_equipment_id);
```

- [ ] **Step 4: Поле сущности**

В `TenderLot.java` после поля `requiredSpec` добавить:

```java
    /** Предложенная (одобренная) модель каталога; подставляется в запрос КП по лоту. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "proposed_equipment_id")
    private MedEquipment proposedEquipment;
```

- [ ] **Step 5: DTO**

`src/main/java/com/vladoose/nir/dto/response/ProposedEquipmentResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class ProposedEquipmentResponse {
    private Long id;
    private String name;
    private String manufact;
    private String registrationStatus; // REGISTERED | NOT_FOUND | UNCHECKED
    private String regNumber;          // № РУ, если модель привязана к реестру
}
```

`src/main/java/com/vladoose/nir/dto/request/ProposedEquipmentRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ProposedEquipmentRequest {
    @NotNull(message = "Не указано оборудование")
    private Long equipmentId;
}
```

В `TenderLotResponse.java` добавить поле:

```java
    private ProposedEquipmentResponse proposedEquipment;
```

- [ ] **Step 6: Маппер**

В `TenderLotMapper.java` добавить default-метод (MapStruct подхватит его для свойства `proposedEquipment` по типам) и импорты `com.vladoose.nir.dto.response.ProposedEquipmentResponse`, `com.vladoose.nir.entity.MedEquipment`:

```java
    default ProposedEquipmentResponse proposedEquipmentToResponse(MedEquipment e) {
        if (e == null) return null;
        ProposedEquipmentResponse r = new ProposedEquipmentResponse();
        r.setId(e.getId());
        r.setName(e.getName());
        r.setManufact(e.getManufact());
        r.setRegistrationStatus(e.getRegistrationStatus() != null ? e.getRegistrationStatus().name() : null);
        r.setRegNumber(e.getRegistration() != null ? e.getRegistration().getRegNumber() : null);
        return r;
    }
```

И на обоих методах `toEntity`/`updateEntity` добавить `@Mapping(target = "proposedEquipment", ignore = true)` (создание/правка лота формой предложение не трогает).

- [ ] **Step 7: Эндпоинты**

В `TenderLotController.java`: добавить зависимость `MedEquipmentService` (поле + параметр конструктора + присваивание), импорты `com.vladoose.nir.context.MarketContext`, `com.vladoose.nir.dto.request.ProposedEquipmentRequest`, `com.vladoose.nir.entity.MedEquipment`, `com.vladoose.nir.exception.NotFoundException`, `com.vladoose.nir.service.MedEquipmentService`, `org.springframework.security.access.prepost.PreAuthorize` и методы:

```java
    /** Утвердить модель каталога как «предложенное оборудование» лота. */
    @PostMapping("/{id}/proposed-equipment")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse setProposedEquipment(@PathVariable Long id,
                                                  @Valid @RequestBody ProposedEquipmentRequest request) {
        TenderLot lot = service.findById(id);
        MedEquipment eq = medEquipmentService.findById(request.getEquipmentId());
        // em.find обходит hibernate-фильтр рынка — явный гард от чужого рынка
        if (eq.getMarket() != null && eq.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Оборудование не найдено: id=" + request.getEquipmentId());
        }
        lot.setProposedEquipment(eq);
        return mapper.toResponse(service.save(lot));
    }

    @DeleteMapping("/{id}/proposed-equipment")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse clearProposedEquipment(@PathVariable Long id) {
        TenderLot lot = service.findById(id);
        lot.setProposedEquipment(null);
        return mapper.toResponse(service.save(lot));
    }
```

- [ ] **Step 8: Прогнать тест — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.tender.LotProposedEquipmentTest"`
Expected: PASS (2/2). Заодно: `./gradlew test --tests "com.vladoose.nir.market.*"` — рыночные тесты не сломаны.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V4__lot_proposed_equipment.sql \
  src/main/java/com/vladoose/nir/entity/TenderLot.java \
  src/main/java/com/vladoose/nir/dto/response/ProposedEquipmentResponse.java \
  src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java \
  src/main/java/com/vladoose/nir/dto/request/ProposedEquipmentRequest.java \
  src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java \
  src/main/java/com/vladoose/nir/controller/TenderLotController.java \
  src/test/java/com/vladoose/nir/tender/LotProposedEquipmentTest.java
git commit -m "feat(lots): предложенная модель лота — V4, entity, DTO, апрув-эндпоинты

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `SpecConstraintExtractor` — габариты/вес из текста спеки

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/SpecConstraintExtractor.java`
- Test: `src/test/java/com/vladoose/nir/util/SpecConstraintExtractorTest.java`

**Interfaces:**
- Produces: `SpecConstraintExtractor.extract(String): SpecConstraints`; `record SpecConstraints(Integer maxLengthMm, Integer maxWidthMm, Integer maxHeightMm, BigDecimal maxWeightKg, List<String> snippets)` c методом `isEmpty()`.
- Consumes: ничего (чистая утилита).

- [ ] **Step 1: Написать падающий тест (чистый JUnit, без Spring)**

`src/test/java/com/vladoose/nir/util/SpecConstraintExtractorTest.java`:

```java
package com.vladoose.nir.util;

import com.vladoose.nir.util.SpecConstraintExtractor.SpecConstraints;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SpecConstraintExtractorTest {

    @Test
    void tripleMmWithNotMore() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Аппарат УЗИ. Габаритные размеры: не более 1200х800х1300 мм, питание 220 В.");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
        assertThat(c.maxWidthMm()).isEqualTo(800);
        assertThat(c.maxHeightMm()).isEqualTo(1300);
    }

    @Test
    void tripleCmConverted() {
        SpecConstraints c = SpecConstraintExtractor.extract("Размеры 120 x 80 x 130 см");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
        assertThat(c.maxWidthMm()).isEqualTo(800);
        assertThat(c.maxHeightMm()).isEqualTo(1300);
    }

    @Test
    void tripleMetersDecimalCommaAndCross() {
        SpecConstraints c = SpecConstraintExtractor.extract("габариты до 1,2×0,8×1,3 м");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
        assertThat(c.maxWidthMm()).isEqualTo(800);
        assertThat(c.maxHeightMm()).isEqualTo(1300);
    }

    @Test
    void starSeparator() {
        SpecConstraints c = SpecConstraintExtractor.extract("размер 1200*800*1300 мм");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
    }

    @Test
    void weightKg() {
        SpecConstraints c = SpecConstraintExtractor.extract("Вес не более 45 кг");
        assertThat(c.maxWeightKg()).isEqualByComparingTo(new BigDecimal("45"));
    }

    @Test
    void weightKgWithoutQualifier() {
        SpecConstraints c = SpecConstraintExtractor.extract("Масса: 45,5 кг");
        assertThat(c.maxWeightKg()).isEqualByComparingTo(new BigDecimal("45.5"));
    }

    @Test
    void weightGramsConverted() {
        SpecConstraints c = SpecConstraintExtractor.extract("масса до 4500 г");
        assertThat(c.maxWeightKg()).isEqualByComparingTo(new BigDecimal("4.50"));
    }

    @Test
    void lowerBoundsIgnored() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Размеры не менее 500х400х300 мм. Вес не менее 5 кг.");
        assertThat(c.isEmpty()).isTrue();
    }

    @Test
    void bareTripleWithoutKeywordIgnored() {
        SpecConstraints c = SpecConstraintExtractor.extract("В комплекте кабель 1200х800х1300 мм");
        assertThat(c.isEmpty()).isTrue();
    }

    @Test
    void garbageAndNullSafe() {
        assertThat(SpecConstraintExtractor.extract("Класс безопасности IIa, питание 220 В").isEmpty()).isTrue();
        assertThat(SpecConstraintExtractor.extract(null).isEmpty()).isTrue();
        assertThat(SpecConstraintExtractor.extract("  ").isEmpty()).isTrue();
    }

    @Test
    void snippetsCaptured() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Габариты не более 1200х800х1300 мм. Вес не более 45 кг.");
        assertThat(c.snippets()).hasSize(2);
        assertThat(c.snippets().get(0)).contains("1200х800х1300 мм");
        assertThat(c.snippets().get(1)).contains("45 кг");
    }
}
```

- [ ] **Step 2: Прогнать — падает (класс не существует)**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.SpecConstraintExtractorTest"`
Expected: FAIL (compilation).

- [ ] **Step 3: Реализация**

`src/main/java/com/vladoose/nir/util/SpecConstraintExtractor.java`:

```java
package com.vladoose.nir.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлекает из текста спецификации лота ВЕРХНИЕ ограничения габаритов и веса.
 * Берём только «не более/до/≤/максимум …» и значения без квалификатора;
 * «не менее/от/минимум» — нижние границы, игнорируются.
 * MVP: триплет A×B×C с ключевым словом (габарит*/размер*) + вес/масса. Best-effort: ничего не нашли — пустой результат.
 */
public final class SpecConstraintExtractor {

    public record SpecConstraints(Integer maxLengthMm, Integer maxWidthMm, Integer maxHeightMm,
                                  BigDecimal maxWeightKg, List<String> snippets) {
        public boolean isEmpty() {
            return maxLengthMm == null && maxWidthMm == null && maxHeightMm == null && maxWeightKg == null;
        }
    }

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final String NUM = "(\\d+(?:[.,]\\d+)?)";
    private static final String X = "\\s*[xх×*]\\s*";

    /** «габариты … 1200х800х1300 мм»: g1 — зазор (для проверки на «не менее»), g2..g4 — числа, g5 — единица. */
    private static final Pattern TRIPLE = Pattern.compile(
            "(?:габарит\\w*|размер\\w*)([^\\n]{0,60}?)" + NUM + X + NUM + X + NUM + "\\s*(мм|см|м)\\b", FLAGS);

    /** «вес/масса … 45 кг»: g1 — зазор, g2 — число, g3 — единица. Зазор без цифр — диапазоны («от 30 до 45») не матчатся. */
    private static final Pattern WEIGHT = Pattern.compile(
            "(?:вес|масса)([^\\n\\d]{0,40}?)" + NUM + "\\s*(кг|г)\\b", FLAGS);

    private static final Pattern LOWER_BOUND = Pattern.compile(
            "не\\s+менее|не\\s+ниже|минимум|\\bот\\b", FLAGS);

    private SpecConstraintExtractor() {}

    public static SpecConstraints extract(String spec) {
        List<String> snippets = new ArrayList<>();
        Integer len = null, wid = null, hei = null;
        BigDecimal weight = null;
        if (spec != null && !spec.isBlank()) {
            Matcher t = TRIPLE.matcher(spec);
            while (t.find()) {
                if (LOWER_BOUND.matcher(t.group(1)).find()) continue;
                double k = unitToMm(t.group(5));
                len = toMm(t.group(2), k);
                wid = toMm(t.group(3), k);
                hei = toMm(t.group(4), k);
                snippets.add(spec.substring(t.start(), t.end()).trim());
                break; // первый валидный триплет
            }
            Matcher w = WEIGHT.matcher(spec);
            while (w.find()) {
                if (LOWER_BOUND.matcher(w.group(1)).find()) continue;
                BigDecimal v = new BigDecimal(w.group(2).replace(',', '.'));
                if ("г".equalsIgnoreCase(w.group(3))) {
                    v = v.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
                }
                weight = v;
                snippets.add(spec.substring(w.start(), w.end()).trim());
                break;
            }
        }
        return new SpecConstraints(len, wid, hei, weight, snippets);
    }

    private static double unitToMm(String unit) {
        return switch (unit.toLowerCase()) {
            case "м" -> 1000.0;
            case "см" -> 10.0;
            default -> 1.0; // мм
        };
    }

    private static Integer toMm(String num, double k) {
        return (int) Math.round(Double.parseDouble(num.replace(',', '.')) * k);
    }
}
```

- [ ] **Step 4: Прогнать — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.SpecConstraintExtractorTest"`
Expected: PASS (11/11).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/util/SpecConstraintExtractor.java \
  src/test/java/com/vladoose/nir/util/SpecConstraintExtractorTest.java
git commit -m "feat(match): SpecConstraintExtractor — верхние габариты/вес из текста спеки

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `scoreLot` — констрейнты из спеки + `specDerived` в ответе

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/EquipmentScoringService.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/EquipmentMatchResponse.java`
- Test: `src/test/java/com/vladoose/nir/service/EquipmentScoringSpecDerivedTest.java`

**Interfaces:**
- Consumes: `SpecConstraintExtractor.extract` (Task 2).
- Produces: `EquipmentMatchResponse.getSpecDerived(): SpecDerived {lengthMm, widthMm, heightMm, weightKg, snippets}` — null, если парсер не применялся или ничего не нашёл. Поведение: парсер включается ТОЛЬКО когда все 4 структурных `max*`-поля лота null и спека непуста; при заданных структурных полях — как раньше.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/service/EquipmentScoringSpecDerivedTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.EquipmentMatchResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class EquipmentScoringSpecDerivedTest {

    private static final double[] W = {0.25, 0.25, 0.25, 0.25};

    @Autowired EquipmentScoringService scoringService;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;

    private Tender tender;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tender = new Tender();
        tender.setTenderNumber("ZZ-SD-" + System.nanoTime());
        tender.setStatus("ACTIVE");
        tenderRepository.save(tender);
        saveEquip("ZZ Компакт", 1000, 700, 1200, "40");
        saveEquip("ZZ Гигант", 2000, 900, 1500, "60");
    }

    private void saveEquip(String name, int len, int wid, int hei, String kg) {
        MedEquipment e = new MedEquipment();
        e.setName(name);
        e.setManufact("ZZBrand");
        e.setLengthMm(len);
        e.setWidthMm(wid);
        e.setHeightMm(hei);
        e.setWeightKg(new BigDecimal(kg));
        medEquipmentRepository.save(e);
    }

    private TenderLot saveLot(Integer maxLen, String spec) {
        TenderLot lot = new TenderLot();
        lot.setTender(tender);
        lot.setLotNumber(1);
        lot.setEquipName("ZZ УЗИ");
        lot.setMaxLengthMm(maxLen);
        lot.setRequiredSpec(spec);
        return tenderLotRepository.save(lot);
    }

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void specConstraintsFilterCandidates_andSpecDerivedReturned() {
        TenderLot lot = saveLot(null, "Габариты не более 1200х800х1300 мм, вес не более 45 кг");
        EquipmentMatchResponse r = scoringService.scoreLot(lot.getId(), W, "BALANCED");

        List<String> names = r.getCandidates().stream()
                .map(EquipmentMatchResponse.Candidate::getName).toList();
        assertThat(names).contains("ZZ Компакт").doesNotContain("ZZ Гигант");

        assertThat(r.getSpecDerived()).isNotNull();
        assertThat(r.getSpecDerived().getLengthMm()).isEqualTo(1200);
        assertThat(r.getSpecDerived().getWeightKg()).isEqualByComparingTo(new BigDecimal("45"));
        assertThat(r.getSpecDerived().getSnippets()).isNotEmpty();
    }

    @Test
    void structuredFieldsTakePriority_specDerivedNull() {
        // структурное поле есть → парсер не применяется, даже если в спеке другие числа
        TenderLot lot = saveLot(800, "Габариты не более 5000х5000х5000 мм");
        EquipmentMatchResponse r = scoringService.scoreLot(lot.getId(), W, "BALANCED");

        List<String> names = r.getCandidates().stream()
                .map(EquipmentMatchResponse.Candidate::getName).toList();
        assertThat(names).doesNotContain("ZZ Компакт", "ZZ Гигант"); // оба длиннее 800
        assertThat(r.getSpecDerived()).isNull();
    }

    @Test
    void unparsableSpec_specDerivedNull_noFiltering() {
        TenderLot lot = saveLot(null, "Класс безопасности IIa, питание 220 В");
        EquipmentMatchResponse r = scoringService.scoreLot(lot.getId(), W, "BALANCED");

        List<String> names = r.getCandidates().stream()
                .map(EquipmentMatchResponse.Candidate::getName).toList();
        assertThat(names).contains("ZZ Компакт", "ZZ Гигант");
        assertThat(r.getSpecDerived()).isNull();
    }
}
```

- [ ] **Step 2: Прогнать — падает** (нет `getSpecDerived`).

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.EquipmentScoringSpecDerivedTest"`
Expected: FAIL (compilation).

- [ ] **Step 3: DTO — блок `SpecDerived`**

В `EquipmentMatchResponse.java`: добавить поле в корневой класс и вложенный класс (+ `import java.util.List;` уже есть):

```java
    private SpecDerived specDerived; // ограничения, извлечённые из текста спеки (null — не применялись)
```

```java
    @Getter @Setter @NoArgsConstructor
    public static class SpecDerived {
        private Integer lengthMm;
        private Integer widthMm;
        private Integer heightMm;
        private BigDecimal weightKg;
        private List<String> snippets;
    }
```

- [ ] **Step 4: Интеграция в `EquipmentScoringService`**

Импорт: `com.vladoose.nir.util.SpecConstraintExtractor`. В `scoreLot` заменить блок shortlist:

```java
        Long equipTypeId = lot.getEquipmentType() != null ? lot.getEquipmentType().getId() : null;
        List<MedEquipment> shortlist = equipRepo.findMatchingEquipment(
                equipTypeId, lot.getMaxLengthMm(), lot.getMaxWidthMm(), lot.getMaxHeightMm(), lot.getMaxWeightKg());
```

на:

```java
        Long equipTypeId = lot.getEquipmentType() != null ? lot.getEquipmentType().getId() : null;

        // Эффективные ограничения: структурные поля лота, а при их полном отсутствии — из текста спеки
        Integer effLen = lot.getMaxLengthMm(), effWid = lot.getMaxWidthMm(), effHei = lot.getMaxHeightMm();
        BigDecimal effWeight = lot.getMaxWeightKg();
        SpecConstraintExtractor.SpecConstraints derived = null;
        boolean noStructured = effLen == null && effWid == null && effHei == null && effWeight == null;
        if (noStructured && lot.getRequiredSpec() != null && !lot.getRequiredSpec().isBlank()) {
            SpecConstraintExtractor.SpecConstraints c = SpecConstraintExtractor.extract(lot.getRequiredSpec());
            if (!c.isEmpty()) {
                derived = c;
                effLen = c.maxLengthMm(); effWid = c.maxWidthMm(); effHei = c.maxHeightMm();
                effWeight = c.maxWeightKg();
            }
        }

        List<MedEquipment> shortlist = equipRepo.findMatchingEquipment(
                equipTypeId, effLen, effWid, effHei, effWeight);
```

Пробросить eff-значения в скоринг габаритов: `buildCandidate(e, lot, stats, weights)` → `buildCandidate(e, stats, weights, effLen, effWid, effHei, effWeight)` (лот в buildCandidate использовался только для maxCost и габаритов — maxCost передать отдельно или оставить lot: проще оставить lot В buildCandidate для price-блока и добавить 4 eff-параметра, а `computeDimScore(e, lot)` заменить на `computeDimScore(e, effLen, effWid, effHei, effWeight)`):

```java
    private Candidate buildCandidate(MedEquipment e, TenderLot lot,
                                      EquipmentHistoryStatsService.Stats stats,
                                      double[] w,
                                      Integer effLen, Integer effWid, Integer effHei, BigDecimal effWeight) {
```

вызов в цикле: `candidates.add(buildCandidate(e, lot, stats, weights, effLen, effWid, effHei, effWeight));`
внутри: `SubScore dim = computeDimScore(e, effLen, effWid, effHei, effWeight);`

```java
    private SubScore computeDimScore(MedEquipment e, Integer maxLen, Integer maxWid, Integer maxHei, BigDecimal maxWeight) {
        double sumUsed = 0;
        int count = 0;
        if (maxLen != null && maxLen > 0 && e.getLengthMm() != null) {
            sumUsed += (double) e.getLengthMm() / maxLen; count++;
        }
        if (maxWid != null && maxWid > 0 && e.getWidthMm() != null) {
            sumUsed += (double) e.getWidthMm() / maxWid; count++;
        }
        if (maxHei != null && maxHei > 0 && e.getHeightMm() != null) {
            sumUsed += (double) e.getHeightMm() / maxHei; count++;
        }
        if (maxWeight != null && maxWeight.signum() > 0 && e.getWeightKg() != null) {
            sumUsed += e.getWeightKg().doubleValue() / maxWeight.doubleValue(); count++;
        }
        if (count == 0) {
            return new SubScore(100.0, false, "габариты лота не заданы");
        }
        double avgUsed = sumUsed / count;
        double v = Math.max(0.0, 100.0 - 25.0 * avgUsed);
        return new SubScore(round1(v), false,
                String.format("загрузка габаритов: %d %%", (int) Math.round(avgUsed * 100)));
    }
```

Перед `return resp;` заполнить specDerived:

```java
        if (derived != null) {
            EquipmentMatchResponse.SpecDerived sd = new EquipmentMatchResponse.SpecDerived();
            sd.setLengthMm(derived.maxLengthMm());
            sd.setWidthMm(derived.maxWidthMm());
            sd.setHeightMm(derived.maxHeightMm());
            sd.setWeightKg(derived.maxWeightKg());
            sd.setSnippets(derived.snippets());
            resp.setSpecDerived(sd);
        }
```

- [ ] **Step 5: Прогнать — зелёный** (и smoke на соседей)

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.EquipmentScoringSpecDerivedTest" --tests "com.vladoose.nir.service.RegistryMatchServiceTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/EquipmentScoringService.java \
  src/main/java/com/vladoose/nir/dto/response/EquipmentMatchResponse.java \
  src/test/java/com/vladoose/nir/service/EquipmentScoringSpecDerivedTest.java
git commit -m "feat(match): scoreLot берёт габариты/вес из спеки при пустых полях лота + specDerived в ответе

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `KpEmailComposer` — единый генератор письма КП

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/KpEmailComposer.java`
- Test: `src/test/java/com/vladoose/nir/service/KpEmailComposerTest.java`

**Interfaces:**
- Produces: `KpEmailComposer.compose(PriceRequest): Composed`; `record Composed(String subject, String body)`. Брендинг из `pr.getMarket()` (null → RF). Константа `SPEC_LIMIT = 1200`.
- Consumes: `KpToken.subjectToken`, сущности `PriceRequest/Tender/TenderLot/MedEquipment/Distributor/Market/Source`.

- [ ] **Step 1: Написать падающий тест (чистый JUnit)**

`src/test/java/com/vladoose/nir/service/KpEmailComposerTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KpEmailComposerTest {

    private final KpEmailComposer composer = new KpEmailComposer();

    private PriceRequest kzTenderPr(String spec) {
        Tender t = new Tender();
        t.setTenderNumber("17276387-1");
        t.setSource(Source.PUBLIC_TENDER);
        t.setDeadline(LocalDate.of(2026, 7, 15));
        t.setSourceExtId("17276387");

        Distributor d = new Distributor();
        d.setName("ZZ Дистр");
        d.setLastName("Иванов");
        d.setFirstName("Пётр");

        MedRegistry reg = new MedRegistry();
        reg.setRegNumber("РК-МТ-5№012345");
        MedEquipment eq = new MedEquipment();
        eq.setName("SonoMax DC-70");
        eq.setManufact("Mindray");
        eq.setRegistration(reg);

        TenderLot lot1 = new TenderLot();
        lot1.setLotNumber(1);
        lot1.setEquipName("Аппарат УЗИ");
        TenderLot lot2 = new TenderLot();
        lot2.setLotNumber(3);
        lot2.setEquipName("Аппарат ИВЛ");
        lot2.setRequiredSpec(spec);

        PriceRequestItem i1 = new PriceRequestItem();
        i1.setTenderLot(lot1);
        i1.setMedEquipment(eq);
        i1.setRequestedQuantity(2);
        PriceRequestItem i2 = new PriceRequestItem();
        i2.setTenderLot(lot2);
        i2.setRequestedQuantity(1);

        PriceRequest pr = new PriceRequest();
        pr.setId(42L);
        pr.setTender(t);
        pr.setDistributor(d);
        pr.setMarket(Market.KZ);
        pr.setItems(new ArrayList<>(List.of(i1, i2)));
        return pr;
    }

    @Test
    void kzTender_modelAndBareLot() {
        KpEmailComposer.Composed msg = composer.compose(kzTenderPr("Требуемая спека ИВЛ"));

        assertThat(msg.subject()).contains("[КП-42]").contains("по тендеру № 17276387-1");
        assertThat(msg.body())
                .contains("Уважаемый(ая) Иванов Пётр!")
                .contains("ТОО «West-Med»")
                .contains("Лот 1: SonoMax DC-70 (Mindray), РУ № РК-МТ-5№012345 — 2 шт.")
                .contains("Лот 3: Аппарат ИВЛ — 1 шт.")
                .contains("Требования (из ТЗ): Требуемая спека ИВЛ")
                .contains("Приём заявок до 15.07.2026")
                .contains("https://goszakup.gov.kz/ru/announce/index/17276387")
                .contains("НЦЭЛС РК")
                .doesNotContain("Росздравнадзор");
    }

    @Test
    void longSpecTrimmedAt1200() {
        String longSpec = "х".repeat(2000);
        KpEmailComposer.Composed msg = composer.compose(kzTenderPr(longSpec));
        assertThat(msg.body()).contains("(полное ТЗ — по ссылке на объявление)");
        assertThat(msg.body()).doesNotContain("х".repeat(1300));
    }

    @Test
    void rfBrandingAndZakupkiLink() {
        PriceRequest pr = kzTenderPr(null);
        pr.setMarket(Market.RF);
        pr.getTender().setSourceExtId(null);
        KpEmailComposer.Composed msg = composer.compose(pr);
        assertThat(msg.body())
                .contains("ООО «РЕГИОН-МЕД»")
                .contains("Росздравнадзора")
                .contains("https://zakupki.gov.ru/epz/order/extendedsearch/results.html?searchString=");
    }

    @Test
    void privateRequest_subjectAndNoAnnounceLink() {
        PriceRequest pr = kzTenderPr(null);
        pr.getTender().setSource(Source.PRIVATE_REQUEST);
        pr.getTender().setTenderNumber("ЧЗ-2026-0007");
        pr.getTender().setSourceExtId(null);
        pr.getTender().setDeadline(null);
        KpEmailComposer.Composed msg = composer.compose(pr);
        assertThat(msg.subject()).contains("по заявке ЧЗ-2026-0007");
        assertThat(msg.body()).doesNotContain("Объявление:").doesNotContain("Приём заявок");
    }
}
```

- [ ] **Step 2: Прогнать — падает** (класс не существует).

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.KpEmailComposerTest"`
Expected: FAIL (compilation).

- [ ] **Step 3: Реализация**

`src/main/java/com/vladoose/nir/service/KpEmailComposer.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.util.KpToken;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/** Единственное место построения темы/тела письма запроса КП. Брендинг — по рынку заявки (pr.getMarket()). */
@Component
public class KpEmailComposer {

    static final int SPEC_LIMIT = 1200;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public record Composed(String subject, String body) {}

    public Composed compose(PriceRequest pr) {
        Tender tender = pr.getTender();
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        boolean isPrivate = tender.getSource() == Source.PRIVATE_REQUEST;
        String target = isPrivate
                ? "заявке " + tender.getTenderNumber()
                : "тендеру № " + tender.getTenderNumber();

        String subject = KpToken.subjectToken(pr.getId()) + " Запрос КП по " + target;

        Distributor d = pr.getDistributor();
        StringBuilder sb = new StringBuilder();
        sb.append("Уважаемый(ая) ").append(safe(d.getLastName())).append(" ").append(safe(d.getFirstName())).append("!\n\n");
        sb.append(market.companyShortName())
          .append(" просит предоставить коммерческое предложение по позициям ")
          .append(isPrivate ? "заявки " + tender.getTenderNumber() : "тендера № " + tender.getTenderNumber())
          .append(":\n\n");

        for (PriceRequestItem it : pr.getItems()) {
            TenderLot lot = it.getTenderLot();
            String qty = it.getRequestedQuantity() != null ? it.getRequestedQuantity() + " шт." : "кол-во уточняется";
            sb.append("— Лот ").append(lot.getLotNumber() != null ? lot.getLotNumber() : "—").append(": ");
            MedEquipment eq = it.getMedEquipment();
            if (eq != null) {
                sb.append(eq.getName()).append(" (").append(eq.getManufact()).append(")");
                if (eq.getRegistration() != null) {
                    sb.append(", РУ № ").append(eq.getRegistration().getRegNumber());
                }
                sb.append(" — ").append(qty).append("\n");
            } else {
                sb.append(lot.getEquipName());
                if (lot.getManufact() != null && !lot.getManufact().isBlank()) {
                    sb.append(" (").append(lot.getManufact()).append(")");
                }
                sb.append(" — ").append(qty).append("\n");
                if (lot.getRequiredSpec() != null && !lot.getRequiredSpec().isBlank()) {
                    sb.append("  Требования (из ТЗ): ").append(trimSpec(lot.getRequiredSpec())).append("\n");
                }
            }
        }
        sb.append("\n");
        if (tender.getDeadline() != null) {
            sb.append("Приём заявок до ").append(DATE.format(tender.getDeadline())).append(".\n");
        }
        String link = announceLink(tender, market);
        if (link != null) {
            sb.append("Объявление: ").append(link).append("\n");
        }
        sb.append("\nПросим указать: цену за единицу, № регистрационного удостоверения (")
          .append(market == Market.KZ ? "НЦЭЛС РК" : "Росздравнадзора")
          .append(") на предлагаемую модель, сроки поставки, условия оплаты, гарантию.\n\n");
        sb.append("С уважением,\n").append(market.companyShortName());
        return new Composed(subject, sb.toString());
    }

    private String trimSpec(String spec) {
        String s = spec.strip();
        if (s.length() <= SPEC_LIMIT) return s;
        return s.substring(0, SPEC_LIMIT) + "… (полное ТЗ — по ссылке на объявление)";
    }

    private String announceLink(Tender t, Market market) {
        if (market == Market.KZ) {
            return t.getSourceExtId() != null
                    ? "https://goszakup.gov.kz/ru/announce/index/" + t.getSourceExtId()
                    : null;
        }
        if (t.getSource() == Source.PRIVATE_REQUEST) return null;
        return "https://zakupki.gov.ru/epz/order/extendedsearch/results.html?searchString="
                + URLEncoder.encode(t.getTenderNumber() == null ? "" : t.getTenderNumber(), StandardCharsets.UTF_8);
    }

    private String safe(String s) { return s == null ? "" : s; }
}
```

- [ ] **Step 4: Прогнать — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.KpEmailComposerTest"`
Expected: PASS (4/4).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/KpEmailComposer.java \
  src/test/java/com/vladoose/nir/service/KpEmailComposerTest.java
git commit -m "feat(email): KpEmailComposer — единый market-aware шаблон письма запроса КП

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `PriceRequestSendService` + `POST /api/price-requests/send` + делегат bulk-price

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/PriceRequestSendService.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/PriceRequestSendRequest.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java`
- Modify: `src/main/java/com/vladoose/nir/controller/BulkPriceController.java`
- Modify: `src/main/java/com/vladoose/nir/service/BulkPriceRequestService.java` (выпилить отправку)
- Test: `src/test/java/com/vladoose/nir/service/PriceRequestSendServiceTest.java`

**Interfaces:**
- Consumes: `KpEmailComposer` (Task 4), `EmailService.sendEmail`, `PriceRequestService.save` (штампует market), `TenderService/TenderLotService/MedEquipmentService/DistributorService.findById`, `PriceRequestItemRepository`.
- Produces:
  - `PriceRequestSendService.send(Long tenderId, List<Long> distributorIds, List<SendItem> items): List<SendResult>`;
  - `record SendItem(Long tenderLotId, Long medEquipmentId, Integer requestedQuantity)` (medEquipmentId nullable);
  - `record SendResult(Long priceRequestId, Long distributorId, String distributorName, boolean emailSent, String reason)` — reason: `null | "NO_EMAIL" | "SEND_FAILED"`;
  - `POST /api/price-requests/send` (ADMIN) c телом `{tenderId, distributorIds[], items:[{tenderLotId, medEquipmentId?, requestedQuantity}]}` → массив SendResult (JSON-поля: priceRequestId, distributorId, distributorName, emailSent, reason).
  - `POST /api/bulk-price/send` — тонкий делегат (контракт `→ Long` сохранён).

- [ ] **Step 1: Написать падающий тест (GreenMail SMTP)**

`src/test/java/com/vladoose/nir/service/PriceRequestSendServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "spring.mail.username=zakup-test@westmed.local"
})
class PriceRequestSendServiceTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired PriceRequestSendService sendService;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired DistributorRepository distributorRepository;

    Tender tender;
    TenderLot lot1, lot2;
    MedEquipment eq;
    Distributor withEmail, withoutEmail;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tender = new Tender();
        tender.setTenderNumber("ZZ-SEND-1");
        tender.setStatus("ACTIVE");
        tender.setDeadline(LocalDate.of(2026, 7, 20));
        tender.setSourceExtId("999111");
        tenderRepository.save(tender);

        lot1 = lot(1, "ZZ УЗИ", null);
        lot2 = lot(2, "ZZ ИВЛ", "Спека ИВЛ: поток не менее 60 л/мин");

        eq = new MedEquipment();
        eq.setName("ZZ SonoMax");
        eq.setManufact("Mindray");
        medEquipmentRepository.save(eq);

        withEmail = dist("ZZ Поставщик-1", "d1@x.kz");
        withoutEmail = dist("ZZ Поставщик-2", null);
    }

    private TenderLot lot(int n, String name, String spec) {
        TenderLot l = new TenderLot();
        l.setTender(tender);
        l.setLotNumber(n);
        l.setEquipName(name);
        l.setQuantity(2);
        l.setRequiredSpec(spec);
        return tenderLotRepository.save(l);
    }

    private Distributor dist(String name, String email) {
        Distributor d = new Distributor();
        d.setName(name);
        d.setEmail(email);
        return distributorRepository.save(d);
    }

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void sendsToEachDistributor_marksNoEmail_andDeliversMailWithToken() throws Exception {
        List<SendResult> results = sendService.send(
                tender.getId(),
                List.of(withEmail.getId(), withoutEmail.getId()),
                List.of(new SendItem(lot1.getId(), eq.getId(), 2),
                        new SendItem(lot2.getId(), null, 1)));

        assertThat(results).hasSize(2);
        SendResult ok = results.get(0);
        SendResult noMail = results.get(1);
        assertThat(ok.emailSent()).isTrue();
        assertThat(ok.reason()).isNull();
        assertThat(noMail.emailSent()).isFalse();
        assertThat(noMail.reason()).isEqualTo("NO_EMAIL");

        // обе записи КП созданы, SENT, KZ, по 2 позиции
        for (SendResult r : results) {
            PriceRequest pr = priceRequestRepository.findById(r.priceRequestId()).orElseThrow();
            assertThat(pr.getStatus()).isEqualTo("SENT");
            assertThat(pr.getSentAt()).isNotNull();
            assertThat(pr.getMarket()).isEqualTo(Market.KZ);
            assertThat(pr.getItems()).hasSize(2);
        }

        // письмо ушло ровно одно — тому, у кого есть email; тема несёт токен
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getAllRecipients()[0].toString()).isEqualTo("d1@x.kz");
        assertThat(messages[0].getSubject()).contains("[КП-" + ok.priceRequestId() + "]");
        String body = messages[0].getContent().toString();
        assertThat(body).contains("West-Med").contains("ZZ SonoMax (Mindray)").contains("Требования (из ТЗ)");
    }

    @Test
    void lotFromAnotherTenderRejected() {
        Tender other = new Tender();
        other.setTenderNumber("ZZ-SEND-2");
        other.setStatus("ACTIVE");
        tenderRepository.save(other);

        assertThatThrownBy(() -> sendService.send(
                other.getId(),
                List.of(withEmail.getId()),
                List.of(new SendItem(lot1.getId(), null, 1))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void invalidQuantityRejected() {
        assertThatThrownBy(() -> sendService.send(
                tender.getId(),
                List.of(withEmail.getId()),
                List.of(new SendItem(lot1.getId(), null, 0))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void emptyItemsRejected() {
        assertThatThrownBy(() -> sendService.send(tender.getId(), List.of(withEmail.getId()), List.of()))
                .isInstanceOf(BadRequestException.class);
    }
}
```

- [ ] **Step 2: Прогнать — падает** (класса нет).

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.PriceRequestSendServiceTest"`
Expected: FAIL (compilation).

- [ ] **Step 3: Сервис**

`src/main/java/com/vladoose/nir/service/PriceRequestSendService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Единый канал отправки запросов КП: на каждого поставщика создаётся PriceRequest (SENT)
 * с позициями (лот + опционально модель) и уходит письмо (KpEmailComposer + EmailService).
 * Ошибка/отсутствие email НЕ валит запись — отражается флагом в SendResult.
 */
@Service
public class PriceRequestSendService {

    private static final Logger log = LoggerFactory.getLogger(PriceRequestSendService.class);

    public static final String REASON_NO_EMAIL = "NO_EMAIL";
    public static final String REASON_SEND_FAILED = "SEND_FAILED";

    public record SendItem(Long tenderLotId, Long medEquipmentId, Integer requestedQuantity) {}
    public record SendResult(Long priceRequestId, Long distributorId, String distributorName,
                             boolean emailSent, String reason) {}

    private final TenderService tenderService;
    private final TenderLotService tenderLotService;
    private final MedEquipmentService medEquipmentService;
    private final DistributorService distributorService;
    private final PriceRequestService priceRequestService;
    private final PriceRequestItemRepository itemRepository;
    private final KpEmailComposer composer;
    private final EmailService emailService;

    public PriceRequestSendService(TenderService tenderService,
                                   TenderLotService tenderLotService,
                                   MedEquipmentService medEquipmentService,
                                   DistributorService distributorService,
                                   PriceRequestService priceRequestService,
                                   PriceRequestItemRepository itemRepository,
                                   KpEmailComposer composer,
                                   EmailService emailService) {
        this.tenderService = tenderService;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
        this.priceRequestService = priceRequestService;
        this.itemRepository = itemRepository;
        this.composer = composer;
        this.emailService = emailService;
    }

    @Transactional
    public List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items) {
        if (tenderId == null) throw new BadRequestException("Не указан тендер");
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        if (items == null || items.isEmpty()) throw new BadRequestException("Не выбраны позиции");

        Tender tender = tenderService.findById(tenderId);

        record Line(TenderLot lot, MedEquipment equipment, int qty) {}
        List<Line> lines = new ArrayList<>();
        for (SendItem si : items) {
            if (si.tenderLotId() == null) throw new BadRequestException("Не указан лот в позиции");
            if (si.requestedQuantity() == null || si.requestedQuantity() < 1) {
                throw new BadRequestException("Количество в позиции должно быть не меньше 1");
            }
            TenderLot lot = tenderLotService.findById(si.tenderLotId());
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + si.tenderLotId() + " не принадлежит тендеру " + tenderId);
            }
            MedEquipment eq = si.medEquipmentId() != null ? medEquipmentService.findById(si.medEquipmentId()) : null;
            lines.add(new Line(lot, eq, si.requestedQuantity()));
        }

        List<SendResult> results = new ArrayList<>();
        for (Long distId : distributorIds) {
            Distributor dist = distributorService.findById(distId);
            PriceRequest pr = PriceRequest.builder()
                    .tender(tender)
                    .distributor(dist)
                    .status("SENT")
                    .sentAt(OffsetDateTime.now())
                    .createdAt(OffsetDateTime.now())
                    .build();
            pr = priceRequestService.save(pr); // штампует market
            for (Line line : lines) {
                PriceRequestItem item = PriceRequestItem.builder()
                        .priceRequest(pr)
                        .tenderLot(line.lot())
                        .medEquipment(line.equipment())
                        .requestedQuantity(line.qty())
                        .build();
                pr.getItems().add(itemRepository.save(item));
            }
            results.add(dispatch(pr, dist));
        }
        return results;
    }

    private SendResult dispatch(PriceRequest pr, Distributor dist) {
        String to = dist.getEmail();
        if (to == null || to.isBlank()) {
            log.warn("КП id={} создан, но у поставщика «{}» нет email — письмо не отправлено", pr.getId(), dist.getName());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_NO_EMAIL);
        }
        KpEmailComposer.Composed msg = composer.compose(pr);
        try {
            emailService.sendEmail(to, msg.subject(), msg.body());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), true, null);
        } catch (Exception ex) {
            log.warn("Не удалось отправить КП id={} на {}: {}. Запрос сохранён в БД.", pr.getId(), to, ex.getMessage());
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_SEND_FAILED);
        }
    }
}
```

- [ ] **Step 4: DTO запроса + эндпоинт**

`src/main/java/com/vladoose/nir/dto/request/PriceRequestSendRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class PriceRequestSendRequest {

    @NotNull(message = "Не указан тендер")
    private Long tenderId;

    @NotEmpty(message = "Не выбраны поставщики")
    private List<Long> distributorIds;

    @NotEmpty(message = "Не выбраны позиции")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "Не указан лот")
        private Long tenderLotId;
        private Long medEquipmentId; // null = запрос по голому лоту
        @NotNull @Min(value = 1, message = "Количество должно быть не меньше 1")
        private Integer requestedQuantity;
    }
}
```

В `PriceRequestController.java`: добавить зависимость `PriceRequestSendService sendService` (поле + параметр конструктора + присваивание), импорты `com.vladoose.nir.dto.request.PriceRequestSendRequest`, `com.vladoose.nir.service.PriceRequestSendService`, `org.springframework.security.access.prepost.PreAuthorize`, и метод (после `create`):

```java
    /** Единый канал: создать КП на каждого поставщика и отправить письмо с токеном [КП-id]. */
    @PostMapping("/send")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PriceRequestSendService.SendResult> send(@Valid @RequestBody PriceRequestSendRequest req) {
        var items = req.getItems().stream()
                .map(i -> new PriceRequestSendService.SendItem(
                        i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
        return sendService.send(req.getTenderId(), req.getDistributorIds(), items);
    }
```

- [ ] **Step 5: Делегат bulk-price + чистка BulkPriceRequestService**

`BulkPriceController.java`: добавить зависимость `PriceRequestSendService sendService` (поле/конструктор), импорт `com.vladoose.nir.service.PriceRequestSendService`, `java.util.List`; заменить метод `send`:

```java
    @PostMapping("/send")
    public Long send(@Valid @RequestBody BulkPriceSendRequest req) {
        var items = req.getItems().stream()
                .map(i -> new PriceRequestSendService.SendItem(
                        i.getTenderLotId(), i.getMedEquipmentId(), i.getRequestedQuantity()))
                .toList();
        var results = sendService.send(req.getTenderId(), List.of(req.getDistributorId()), items);
        return results.get(0).priceRequestId();
    }
```

`BulkPriceRequestService.java`: удалить метод `sendGroup`, `buildEmailBody`, `safe`, record `SendItem`; удалить зависимости `PriceRequestRepository`, `PriceRequestItemRepository`, `EmailService` (поля + параметры конструктора), поле `log` и импорты `KpToken`, `OffsetDateTime`, slf4j — остаётся только `buildPreview` c записями `GroupItem/DistributorGroup/Preview` и зависимостями `TenderRepository, TenderLotRepository, MedEquipmentService, DistributorRepository`.

- [ ] **Step 6: Прогнать — зелёный** (+ компиляция всего)

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.PriceRequestSendServiceTest" --tests "com.vladoose.nir.service.KpEmailComposerTest" --tests "com.vladoose.nir.mail.*"`
Expected: PASS (send 4/4 + композер + почтовые без регрессий).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/PriceRequestSendService.java \
  src/main/java/com/vladoose/nir/dto/request/PriceRequestSendRequest.java \
  src/main/java/com/vladoose/nir/controller/PriceRequestController.java \
  src/main/java/com/vladoose/nir/controller/BulkPriceController.java \
  src/main/java/com/vladoose/nir/service/BulkPriceRequestService.java \
  src/test/java/com/vladoose/nir/service/PriceRequestSendServiceTest.java
git commit -m "feat(kp): единый канал отправки КП — PriceRequestSendService + POST /api/price-requests/send, bulk-send делегирует

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `BrandMatch` + `LotSourcingService` + `GET /api/tenders/{id}/lot-sourcing`

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/BrandMatch.java`
- Create: `src/main/java/com/vladoose/nir/service/LotSourcingService.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/LotSourcingResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/PrivateRequestSourcingService.java` (перевести на BrandMatch)
- Modify: `src/main/java/com/vladoose/nir/controller/TenderController.java` (эндпоинт, 8-й параметр конструктора)
- Test: `src/test/java/com/vladoose/nir/service/LotSourcingServiceTest.java`

**Interfaces:**
- Consumes: `TenderLot.getProposedEquipment()` (Task 1), `RegistryMatchService.candidatesForLot(lotId, limit): List<RegistryCandidateResponse>` (score/producer), `DistributorService.findAll()`, `DistributorMapper.toResponse`.
- Produces:
  - `BrandMatch.firstCarried(List<String> brands, String haystack): String|null`;
  - `LotSourcingService.build(Long tenderId, List<Long> lotIds): LotSourcingResponse`;
  - `GET /api/tenders/{id}/lot-sourcing?lotIds=1,2` → `{distributors: [{distributor: DistributorResponse, preselect: bool, matchedBrands: [{brand, via: "PROPOSED_MODEL"|"REGISTRY", lotId}]}]}`.
  - Константы: топ-5 реестр-кандидатов, порог score ≥ 0.35.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/service/LotSourcingServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class LotSourcingServiceTest {

    @Autowired LotSourcingService lotSourcingService;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired MedEquipmentRepository medEquipmentRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired MedRegistryRepository medRegistryRepository;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void hintsByProposedModelAndRegistryProducer() {
        MarketContext.set(Market.KZ);

        Distributor carrier = new Distributor();
        carrier.setName("ZZ Возит Mindray");
        carrier.setBrands(new java.util.ArrayList<>(List.of("Mindray")));
        distributorRepository.save(carrier);

        Distributor other = new Distributor();
        other.setName("ZZ Без брендов");
        distributorRepository.save(other);

        Tender tender = new Tender();
        tender.setTenderNumber("ZZ-SRC-1");
        tender.setStatus("ACTIVE");
        tenderRepository.save(tender);

        // лот A: есть предложенная модель с брендом-производителем
        MedEquipment eq = new MedEquipment();
        eq.setName("ZZ SonoMax");
        eq.setManufact("Shenzhen Mindray Bio-Medical");
        medEquipmentRepository.save(eq);
        TenderLot lotA = new TenderLot();
        lotA.setTender(tender);
        lotA.setLotNumber(1);
        lotA.setEquipName("ZZ УЗИ");
        lotA.setProposedEquipment(eq);
        tenderLotRepository.save(lotA);

        // лот B: без модели — производитель придёт из реестр-кандидата (имя лота = имя записи реестра)
        String regName = "Аппарат ультразвуковой диагностический ZZSONO-77";
        MedRegistry reg = new MedRegistry();
        reg.setRegNumber("ZZ-РУ-СРЦ-1");
        reg.setName(regName);
        reg.setProducer("Mindray Bio-Medical Co., Ltd");
        medRegistryRepository.saveAndFlush(reg);
        TenderLot lotB = new TenderLot();
        lotB.setTender(tender);
        lotB.setLotNumber(2);
        lotB.setEquipName(regName);
        tenderLotRepository.save(lotB);

        LotSourcingResponse r = lotSourcingService.build(tender.getId(), List.of(lotA.getId(), lotB.getId()));

        LotSourcingResponse.Entry carrierEntry = r.getDistributors().stream()
                .filter(e -> e.getDistributor().getName().equals("ZZ Возит Mindray"))
                .findFirst().orElseThrow();
        assertThat(carrierEntry.isPreselect()).isTrue();
        assertThat(carrierEntry.getMatchedBrands())
                .anyMatch(h -> h.getVia().equals("PROPOSED_MODEL") && h.getLotId().equals(lotA.getId()))
                .anyMatch(h -> h.getVia().equals("REGISTRY") && h.getLotId().equals(lotB.getId()));
        assertThat(carrierEntry.getMatchedBrands()).allMatch(h -> h.getBrand().equals("Mindray"));

        LotSourcingResponse.Entry otherEntry = r.getDistributors().stream()
                .filter(e -> e.getDistributor().getName().equals("ZZ Без брендов"))
                .findFirst().orElseThrow();
        assertThat(otherEntry.isPreselect()).isFalse();
        assertThat(otherEntry.getMatchedBrands()).isEmpty();
    }
}
```

- [ ] **Step 2: Прогнать — падает** (классов нет).

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.LotSourcingServiceTest"`
Expected: FAIL (compilation).

- [ ] **Step 3: `BrandMatch` + перевод частного sourcing**

`src/main/java/com/vladoose/nir/util/BrandMatch.java`:

```java
package com.vladoose.nir.util;

import java.util.List;

/** Матч бренда поставщика: строка (бренд строки/производитель) содержит бренд (case-insensitive, contains). */
public final class BrandMatch {

    private BrandMatch() {}

    /** @return первый бренд из brands, который содержится в haystack, или null. */
    public static String firstCarried(List<String> brands, String haystack) {
        if (haystack == null || haystack.isBlank() || brands == null) return null;
        String h = haystack.toLowerCase();
        for (String b : brands) {
            if (b != null && !b.isBlank() && h.contains(b.trim().toLowerCase())) return b;
        }
        return null;
    }
}
```

В `PrivateRequestSourcingService.java`: импорт `com.vladoose.nir.util.BrandMatch`; заменить вызов `carriesBrand(d, line.getManufact())` на `BrandMatch.firstCarried(d.getBrands(), line.getManufact()) != null` и удалить приватный метод `carriesBrand` целиком.

- [ ] **Step 4: DTO + сервис + эндпоинт**

`src/main/java/com/vladoose/nir/dto/response/LotSourcingResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class LotSourcingResponse {

    private List<Entry> distributors;

    @Data
    public static class Entry {
        private DistributorResponse distributor;
        private boolean preselect;
        private List<BrandHit> matchedBrands;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandHit {
        private String brand;
        private String via;  // PROPOSED_MODEL | REGISTRY
        private Long lotId;
    }
}
```

`src/main/java/com/vladoose/nir/service/LotSourcingService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.util.BrandMatch;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Подсказки поставщиков для запроса КП по лотам тендера:
 * бренд предложенной модели лота, а без модели — производители топ-кандидатов реестра НЦЭЛС,
 * матчатся на бренды дистрибьюторов активного рынка.
 */
@Service
public class LotSourcingService {

    static final int REGISTRY_TOP = 5;
    static final double REGISTRY_SCORE_MIN = 0.35;

    private final TenderLotService tenderLotService;
    private final DistributorService distributorService;
    private final RegistryMatchService registryMatchService;
    private final DistributorMapper distributorMapper;

    public LotSourcingService(TenderLotService tenderLotService,
                              DistributorService distributorService,
                              RegistryMatchService registryMatchService,
                              DistributorMapper distributorMapper) {
        this.tenderLotService = tenderLotService;
        this.distributorService = distributorService;
        this.registryMatchService = registryMatchService;
        this.distributorMapper = distributorMapper;
    }

    public LotSourcingResponse build(Long tenderId, List<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty()) throw new BadRequestException("Не выбраны лоты");

        record BrandSource(Long lotId, String text, String via) {}
        List<BrandSource> sources = new ArrayList<>();
        for (Long lotId : lotIds) {
            TenderLot lot = tenderLotService.findById(lotId);
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + lotId + " не принадлежит тендеру " + tenderId);
            }
            if (lot.getProposedEquipment() != null) {
                sources.add(new BrandSource(lotId, lot.getProposedEquipment().getManufact(), "PROPOSED_MODEL"));
            } else {
                for (RegistryCandidateResponse c : registryMatchService.candidatesForLot(lotId, REGISTRY_TOP)) {
                    if (c.getScore() != null && c.getScore() >= REGISTRY_SCORE_MIN && c.getProducer() != null) {
                        sources.add(new BrandSource(lotId, c.getProducer(), "REGISTRY"));
                    }
                }
            }
        }

        List<LotSourcingResponse.Entry> entries = new ArrayList<>();
        for (Distributor d : distributorService.findAll()) {
            List<LotSourcingResponse.BrandHit> hits = new ArrayList<>();
            for (BrandSource s : sources) {
                String brand = BrandMatch.firstCarried(d.getBrands(), s.text());
                if (brand != null && hits.stream().noneMatch(h ->
                        h.getBrand().equals(brand) && h.getVia().equals(s.via()) && h.getLotId().equals(s.lotId()))) {
                    hits.add(new LotSourcingResponse.BrandHit(brand, s.via(), s.lotId()));
                }
            }
            LotSourcingResponse.Entry e = new LotSourcingResponse.Entry();
            e.setDistributor(distributorMapper.toResponse(d));
            e.setPreselect(!hits.isEmpty());
            e.setMatchedBrands(hits);
            entries.add(e);
        }

        LotSourcingResponse resp = new LotSourcingResponse();
        resp.setDistributors(entries);
        return resp;
    }
}
```

В `TenderController.java`: добавить зависимость `LotSourcingService lotSourcingService` (поле + 8-й параметр конструктора + присваивание), импорты `com.vladoose.nir.dto.response.LotSourcingResponse`, `com.vladoose.nir.service.LotSourcingService`, и метод:

```java
    /** Подсказки поставщиков для запроса КП по выбранным лотам. */
    @GetMapping("/{id}/lot-sourcing")
    public LotSourcingResponse lotSourcing(@PathVariable Long id, @RequestParam List<Long> lotIds) {
        return lotSourcingService.build(id, lotIds);
    }
```

- [ ] **Step 5: Прогнать — зелёный** (+ sourcing частников не сломан)

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.LotSourcingServiceTest" --tests "com.vladoose.nir.privaterequest.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/util/BrandMatch.java \
  src/main/java/com/vladoose/nir/service/LotSourcingService.java \
  src/main/java/com/vladoose/nir/dto/response/LotSourcingResponse.java \
  src/main/java/com/vladoose/nir/service/PrivateRequestSourcingService.java \
  src/main/java/com/vladoose/nir/controller/TenderController.java \
  src/test/java/com/vladoose/nir/service/LotSourcingServiceTest.java
git commit -m "feat(kp): подсказки поставщиков по лотам — BrandMatch + LotSourcingService + GET lot-sourcing

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Фронт — лотовый КП-флоу в карточке тендера

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `POST /api/price-requests/send` (Task 5), `GET /api/tenders/{id}/lot-sourcing` (Task 6), `DELETE /api/lots/{id}/proposed-equipment` (Task 1), `lot.proposedEquipment` в `TenderLotResponse` (Task 1).
- Produces (для Task 8/9): методы `ApiService.sendPriceRequests(body)`, `getLotSourcing(tenderId, lotIds)`, `setProposedEquipment(lotId, equipmentId)`, `clearProposedEquipment(lotId)`; хелпер `tenders.component.kpToastFromResults(results)` (см. Step 3).

- [ ] **Step 1: Методы ApiService**

В `frontend/src/app/services/api.service.ts` после метода `bulkPriceSend` (~строка 184) добавить:

```ts
  /** Единый канал отправки КП: письма поставщикам + записи PriceRequest. */
  sendPriceRequests(body: {
    tenderId: number;
    distributorIds: number[];
    items: { tenderLotId: number; medEquipmentId: number | null; requestedQuantity: number }[];
  }): Observable<any[]> {
    return this.http.post<any[]>(`${this.base}/price-requests/send`, body);
  }

  /** Подсказки поставщиков для запроса КП по лотам тендера. */
  getLotSourcing(tenderId: number, lotIds: number[]): Observable<any> {
    return this.http.get<any>(`${this.base}/tenders/${tenderId}/lot-sourcing`, {
      params: { lotIds: lotIds.join(',') },
    });
  }

  setProposedEquipment(lotId: number, equipmentId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/proposed-equipment`, { equipmentId });
  }

  clearProposedEquipment(lotId: number): Observable<any> {
    return this.http.delete<any>(`${this.base}/lots/${lotId}/proposed-equipment`);
  }
```

- [ ] **Step 2: Шаблон карточки тендера**

В `frontend/src/app/pages/tenders/tenders.component.ts`:

2a. В тулбар лотов (после кнопки «Запросить КП по всему тендеру», строка ~207) добавить:

```html
        <button class="btn btn-kp-selected" *ngIf="lots.length > 0" [disabled]="lotSel.size === 0"
                (click)="openKpPanel()">
          Запросить КП по выбранным ({{ lotSel.size }})
        </button>
```

2b. Заголовок таблицы лотов (строка ~245) — добавить чекбокс-колонку первой:

```html
          <tr><th class="w-36"><input type="checkbox" [checked]="allLotsSelected()" (change)="toggleAllLots($any($event.target).checked)" title="Выбрать все лоты" /></th><th>&#8470;</th><th>Название</th><th>Тип</th><th>Кол-во</th><th>Макс. цена</th><th>Габариты (макс.)</th><th>Макс. вес</th><th>Спецификация</th><th>Действия</th></tr>
```

2c. Строка лота: первой ячейкой чекбокс; ячейку `<td>{{ l.equipName }}</td>` заменить на блок с предложенной моделью и списком «КП уже запрошен у…»; в actions добавить кнопку «КП»:

```html
          <tr *ngFor="let l of lots">
            <td class="w-36"><input type="checkbox" [checked]="lotSel.has(l.id)" (change)="toggleLotSel(l)" /></td>
            <td>{{ l.lotNumber }}</td>
            <td>
              {{ l.equipName }}
              <div class="proposed-line" *ngIf="l.proposedEquipment">
                <span class="badge-proposed">Предложено:</span>
                {{ l.proposedEquipment.name }} ({{ l.proposedEquipment.manufact }})
                <span class="badge-reg-ok" *ngIf="l.proposedEquipment.registrationStatus === 'REGISTERED'"
                      [title]="'РУ ' + (l.proposedEquipment.regNumber || '')">РУ ✓</span>
                <button class="x-mini" (click)="clearProposed(l)" title="Снять предложение">✕</button>
              </div>
              <div class="kp-line" *ngIf="kpDistributorsFor(l.id).length">КП: {{ kpDistributorsFor(l.id).join(', ') }}</div>
            </td>
            <td>{{ l.equipType }}</td><td>{{ l.quantity }}</td>
            <td>{{ l.maxCost | money }}</td><td>{{ l.maxLengthMm || '—' }}x{{ l.maxWidthMm || '—' }}x{{ l.maxHeightMm || '—' }}</td><td>{{ l.maxWeightKg ? l.maxWeightKg + ' кг' : '—' }}</td>
            <td class="spec-cell" [class.spec-open]="l._specOpen" (click)="toggleSpec(l)"
                [title]="l._specOpen ? 'Свернуть' : 'Развернуть спецификацию'">{{ l.requiredSpec || '—' }}</td>
            <td class="actions">
              <button class="btn btn-kp" (click)="openKpPanelFor(l)">КП</button>
              <button class="btn btn-registry" (click)="onLotRegistry(l)">Реестр</button>
              <button class="btn btn-match" (click)="onMatch(l)">Подобрать</button>
              <button class="btn btn-edit" (click)="onEditLot(l)">Редактировать</button>
              <button class="btn btn-delete" (click)="onDeleteLot(l.id)">Удалить</button>
            </td>
          </tr>
```

2d. После блока `registry-panel` (после строки ~283 `</div>`) добавить панель поставщиков:

```html
      <div class="kp-panel" *ngIf="kpPanel">
        <div class="kp-panel-head">
          <span><b>Запрос КП</b> · выбрано лотов: {{ lotSel.size }}</span>
          <button class="btn btn-cancel" (click)="kpPanel = null">✕ Закрыть</button>
        </div>
        <div *ngIf="kpPanel.loading" class="registry-loading">Подбираем поставщиков…</div>
        <ng-container *ngIf="!kpPanel.loading">
          <div class="empty" *ngIf="!kpPanel.entries.length">На этом рынке нет поставщиков — добавьте их в справочнике «Дистрибьюторы»</div>
          <table *ngIf="kpPanel.entries.length" class="kp-suppliers">
            <thead><tr><th class="w-36"></th><th>Поставщик</th><th>Email</th><th>Подсказка</th></tr></thead>
            <tbody>
              <tr *ngFor="let e of kpPanel.entries" [class.kp-hit]="e.preselect">
                <td class="w-36"><input type="checkbox" [(ngModel)]="e._checked" /></td>
                <td>{{ e.distributor?.name }}</td>
                <td>{{ e.distributor?.email || '—' }} <span class="no-email" *ngIf="!e.distributor?.email">письмо не уйдёт</span></td>
                <td>
                  <span class="brand-chip" *ngFor="let h of e.matchedBrands"
                        [title]="h.via === 'PROPOSED_MODEL' ? 'Бренд предложенной модели лота' : 'Производитель из реестр-кандидатов НЦЭЛС'">
                    возит: {{ h.brand }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="kp-panel-actions" *ngIf="kpPanel.entries.length">
            <button class="btn btn-save" [disabled]="kpPanel.sending || checkedSuppliers().length === 0" (click)="sendKpRequests()">
              {{ kpPanel.sending ? 'Отправка…' : 'Отправить запросы (' + checkedSuppliers().length + ')' }}
            </button>
          </div>
        </ng-container>
      </div>
```

2e. В секции «Запросы КП» колонку модели (строка ~334) заменить:

```html
                  <td>{{ it.medEquipment?.name || '— по лоту' }}</td>
```

2f. В styles добавить:

```css
    .w-36 { width: 36px; text-align: center; }
    .btn-kp { background: #0e9f6e; color: #fff; }
    .btn-kp-selected { background: #0e9f6e; color: #fff; margin-left: 8px; }
    .btn-kp-selected:disabled { opacity: 0.5; cursor: not-allowed; }
    .proposed-line { margin-top: 4px; font-size: 12px; color: #374151; }
    .badge-proposed { background: #d1fae5; color: #065f46; border-radius: 8px; padding: 1px 7px; font-size: 11px; font-weight: 600; margin-right: 4px; }
    .badge-reg-ok { background: #dbeafe; color: #1e40af; border-radius: 8px; padding: 1px 6px; font-size: 11px; font-weight: 600; margin-left: 4px; }
    .x-mini { background: none; border: none; color: #ef4444; cursor: pointer; font-size: 13px; margin-left: 4px; }
    .kp-line { margin-top: 3px; font-size: 11px; color: #6b7280; }
    .kp-panel { border: 1px solid #a7f3d0; background: #f0fdf4; border-radius: 8px; padding: 12px 14px; margin: 12px 0; }
    .kp-panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .kp-suppliers { background: #fff; }
    .kp-suppliers tr.kp-hit td { background: #ecfdf5; }
    .brand-chip { display: inline-block; background: #d1fae5; color: #065f46; border-radius: 10px; padding: 2px 8px; font-size: 11px; font-weight: 600; margin: 1px 4px 1px 0; }
    .no-email { color: #b91c1c; font-size: 11px; margin-left: 6px; }
    .kp-panel-actions { margin-top: 10px; display: flex; justify-content: flex-end; }
```

- [ ] **Step 3: TS-логика**

В класс `TendersComponent` (рядом с `matchLotId`, ~строка 522) добавить поля:

```ts
  // Лотовый запрос КП
  lotSel = new Set<number>();
  kpPanel: { loading: boolean; sending: boolean; entries: any[] } | null = null;
```

Методы (после `onSmartMatchRequest`):

```ts
  // ===== Лотовый запрос КП =====
  toggleLotSel(l: any) {
    if (this.lotSel.has(l.id)) this.lotSel.delete(l.id); else this.lotSel.add(l.id);
  }
  allLotsSelected(): boolean {
    return this.lots.length > 0 && this.lots.every((l: any) => this.lotSel.has(l.id));
  }
  toggleAllLots(checked: boolean) {
    this.lotSel.clear();
    if (checked) for (const l of this.lots) this.lotSel.add(l.id);
  }

  openKpPanelFor(l: any) {
    this.lotSel.clear();
    this.lotSel.add(l.id);
    this.openKpPanel();
  }

  openKpPanel() {
    if (!this.selectedTender || this.lotSel.size === 0) return;
    this.kpPanel = { loading: true, sending: false, entries: [] };
    this.cdr.detectChanges();
    this.api.getLotSourcing(this.selectedTender.id, [...this.lotSel]).subscribe({
      next: (r) => {
        const entries = (r?.distributors || []).map((e: any) => ({ ...e, _checked: !!e.preselect }));
        this.kpPanel = { loading: false, sending: false, entries };
        this.cdr.detectChanges();
      },
      error: (e) => {
        this.kpPanel = null;
        this.notify.error('Ошибка подбора поставщиков: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  checkedSuppliers(): any[] {
    return (this.kpPanel?.entries || []).filter((e: any) => e._checked);
  }

  kpDistributorsFor(lotId: number): string[] {
    const names: string[] = [];
    for (const pr of this.priceRequests) {
      if ((pr.items || []).some((it: any) => it.tenderLot?.id === lotId)
          && pr.distributor?.name && !names.includes(pr.distributor.name)) {
        names.push(pr.distributor.name);
      }
    }
    return names;
  }

  clearProposed(l: any) {
    this.api.clearProposedEquipment(l.id).subscribe({
      next: () => { this.notify.success('Предложение модели снято'); this.loadLots(); },
      error: (e) => this.notify.error(e.error?.message || 'Ошибка'),
    });
  }

  /** Единый тост по результатам /send: writes/emails/noEmail/failed. Переиспользуется smart-match-переводом. */
  kpToastFromResults(results: any[]) {
    const list = results || [];
    const sent = list.filter((r: any) => r.emailSent).length;
    const noEmail = list.filter((r: any) => r.reason === 'NO_EMAIL').map((r: any) => r.distributorName);
    const failed = list.filter((r: any) => r.reason === 'SEND_FAILED').map((r: any) => r.distributorName);
    let msg = `Создано запросов: ${list.length}, писем отправлено: ${sent}`;
    if (noEmail.length) msg += `; без email: ${noEmail.join(', ')}`;
    if (failed.length) msg += `; ошибка отправки: ${failed.join(', ')}`;
    if (noEmail.length || failed.length) this.notify.error(msg); else this.notify.success(msg);
  }

  sendKpRequests() {
    if (!this.selectedTender || !this.kpPanel) return;
    const distributorIds = this.checkedSuppliers().map((e: any) => e.distributor.id);
    const items = this.lots
      .filter((l: any) => this.lotSel.has(l.id))
      .map((l: any) => ({
        tenderLotId: l.id,
        medEquipmentId: l.proposedEquipment?.id ?? null,
        requestedQuantity: l.quantity ?? 1,
      }));
    this.kpPanel.sending = true;
    this.api.sendPriceRequests({ tenderId: this.selectedTender.id, distributorIds, items }).subscribe({
      next: (results) => {
        this.kpToastFromResults(results);
        this.kpPanel = null;
        this.lotSel.clear();
        this.loadPriceRequests();
        this.cdr.detectChanges();
      },
      error: (e) => {
        if (this.kpPanel) this.kpPanel.sending = false;
        this.notify.error('Ошибка отправки: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }
```

Сброс состояния при навигации: в `onOpen(t)` (строка ~930) и `onBack()` (строка ~938) добавить строки:

```ts
    this.lotSel.clear();
    this.kpPanel = null;
```

- [ ] **Step 4: Сборка фронта**

Run: `cd frontend && npm run build`
Expected: успешная сборка без ошибок TypeScript/шаблонов. (Если песочница мешает — повторить с sandbox off.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(ui): лотовый запрос КП в карточке тендера — чекбоксы, панель поставщиков с подсказками, отправка

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Фронт — smart-match: specDerived, «Утвердить модель», перевод его КП-кнопки на /send

**Files:**
- Modify: `frontend/src/app/components/smart-match/smart-match.component.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `EquipmentMatchResponse.specDerived` (Task 3), `ApiService.setProposedEquipment` (Task 7), `ApiService.sendPriceRequests` + `kpToastFromResults` (Task 7), `lot.proposedEquipment` (Task 1).
- Produces: `SmartMatchComponent` inputs/outputs: `@Input() proposedEquipmentId: number | null`, `@Output() proposedChanged: EventEmitter<void>`.

- [ ] **Step 1: smart-match — inputs, специблок, кнопка апрува**

В `smart-match.component.ts`:

1a. Импортировать NotificationService: `import { NotificationService } from '../../services/notification.service';` и добавить в конструктор: `constructor(private api: ApiService, private notify: NotificationService) {`.

1b. В класс добавить:

```ts
  @Input() proposedEquipmentId: number | null = null;
  @Output() proposedChanged = new EventEmitter<void>();
```

1c. В шаблон после блока `sm-coldstart` (строка ~43) добавить:

```html
      <div class="sm-specderived" *ngIf="result?.specDerived as sd">
        📐 Ограничения из спеки:
        <ng-container *ngIf="sd.lengthMm">≤ {{ sd.lengthMm }}×{{ sd.widthMm }}×{{ sd.heightMm }} мм</ng-container>
        <ng-container *ngIf="sd.weightKg"><span *ngIf="sd.lengthMm">, </span>≤ {{ sd.weightKg }} кг</ng-container>
      </div>
```

1d. В развёрнутой строке кандидата рядом с кнопкой `btn-pr` (строка ~82-84) добавить:

```html
                <button class="btn-approve" *ngIf="proposedEquipmentId !== c.equipmentId" (click)="approve(c)">
                  ☑ Утвердить модель
                </button>
                <span class="approved-badge" *ngIf="proposedEquipmentId === c.equipmentId">Предложена для лота</span>
```

1e. Метод в классе:

```ts
  approve(c: any) {
    this.api.setProposedEquipment(this.lotId, c.equipmentId).subscribe({
      next: () => {
        this.notify.success(`Модель «${c.name}» предложена для лота`);
        this.proposedChanged.emit();
      },
      error: (e: any) => this.notify.error(e.error?.message || 'Не удалось утвердить модель'),
    });
  }
```

1f. Стили добавить:

```css
    .sm-specderived { background: #eef2ff; border-left: 3px solid #6366f1; padding: 10px 14px; border-radius: 4px; margin-bottom: 12px; font-size: 13px; color: #3730a3; }
    .btn-approve { background: #0e9f6e; color: #fff; border: none; padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 12px; margin-left: 8px; }
    .btn-approve:hover { background: #057a55; }
    .approved-badge { display: inline-block; margin-left: 8px; background: #d1fae5; color: #065f46; border-radius: 10px; padding: 4px 10px; font-size: 12px; font-weight: 600; }
```

- [ ] **Step 2: tenders.component — проброс proposed + перевод onSmartMatchRequest**

2a. Шаблон `app-smart-match` (строки ~285-291) заменить на:

```html
      <app-smart-match
        *ngIf="matchLotId !== null"
        [lotId]="matchLotId"
        [lotNumber]="matchLotNumber || 0"
        [proposedEquipmentId]="matchLotProposedId()"
        (proposedChanged)="loadLots()"
        (close)="closeMatch()"
        (requestPrice)="onSmartMatchRequest($event)">
      </app-smart-match>
```

2b. Метод-хелпер в классе:

```ts
  matchLotProposedId(): number | null {
    const lot = this.lots.find((l: any) => l.id === this.matchLotId);
    return lot?.proposedEquipment?.id ?? null;
  }
```

2c. Заменить тело `onSmartMatchRequest` (строки ~1021-1046) на вызов единого канала:

```ts
  onSmartMatchRequest(ev: { candidate: any; distributorId: number; distributorName: string }) {
    if (!this.matchLotId || !this.selectedTender) {
      this.notify.error('Лот не определён');
      return;
    }
    const lot = this.lots.find((l: any) => l.id === this.matchLotId);
    this.api.sendPriceRequests({
      tenderId: this.selectedTender.id,
      distributorIds: [ev.distributorId],
      items: [{
        tenderLotId: this.matchLotId,
        medEquipmentId: ev.candidate.equipmentId,
        requestedQuantity: lot?.quantity ?? 1
      }]
    }).subscribe({
      next: (results) => {
        this.kpToastFromResults(results);
        this.loadPriceRequests();
        this.matchLotId = null;
        this.matchLotNumber = null;
      },
      error: err => this.notify.error(err.error?.message || 'Не удалось отправить запрос КП')
    });
  }
```

- [ ] **Step 3: Сборка фронта**

Run: `cd frontend && npm run build`
Expected: успех.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/components/smart-match/smart-match.component.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(ui): smart-match — ограничения из спеки, утверждение модели лота, КП через единый канал

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Фронт — частная заявка и bulk-модалка на едином канале

**Files:**
- Modify: `frontend/src/app/pages/private-requests/private-request-card.component.ts`
- Modify: `frontend/src/app/pages/tenders/bulk-price-modal.component.ts`

**Interfaces:**
- Consumes: `ApiService.sendPriceRequests` (Task 7).
- Produces: оба флоу шлют реальные письма; честные тосты про email. `api.createPriceRequest`/`api.bulkPriceSend` из этих компонентов больше не зовутся.

- [ ] **Step 1: private-request-card — requestPrice()**

Заменить тело `requestPrice()` (строки ~405-428):

```ts
  requestPrice() {
    if (this.requestId == null || !this.selectedDistributorId || !this.selectedCount()) return;
    this.sending = true;
    this.api.sendPriceRequests({
      tenderId: this.requestId,
      distributorIds: [this.selectedDistributorId],
      items: this.lines.filter(l => l._selected).map(l => ({
        tenderLotId: l.lotId, medEquipmentId: null, requestedQuantity: l.quantity ?? 1
      }))
    }).subscribe({
      next: (results) => {
        this.sending = false;
        for (const l of this.lines) l._selected = false;
        this.selectedDistributorId = null;
        this.kpToast(results);
        this.loadPriceRequests(this.requestId as number);
      },
      error: (e) => {
        this.sending = false;
        this.notify.error('Ошибка запроса КП: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }
```

- [ ] **Step 2: private-request-card — requestGroup() + тост-хелпер**

Заменить тело `requestGroup()` (строки ~430-448) и добавить хелпер:

```ts
  requestGroup(group: any) {
    if (this.requestId == null || !group?.distributor?.id || !group.lines?.length) return;
    this.sendingGroupId = group.distributor.id;
    this.api.sendPriceRequests({
      tenderId: this.requestId,
      distributorIds: [group.distributor.id],
      items: group.lines.map((l: any) => ({
        tenderLotId: l.lotId, medEquipmentId: null, requestedQuantity: l.quantity ?? 1
      }))
    }).subscribe({
      next: (results) => {
        this.sendingGroupId = null;
        this.kpToast(results);
        this.loadPriceRequests(this.requestId as number);
        this.api.getPrivateRequestSourcing(this.requestId as number).subscribe({ next: s => { this.sourcing = s; this.cdr.detectChanges(); } });
      },
      error: (e) => { this.sendingGroupId = null; this.notify.error('Ошибка: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); }
    });
  }

  private kpToast(results: any[]) {
    const r = (results || [])[0];
    if (!r) { this.notify.success('КП запрошено'); return; }
    if (r.emailSent) this.notify.success(`КП отправлено: «${r.distributorName}»`);
    else if (r.reason === 'NO_EMAIL') this.notify.error(`КП создано, но у «${r.distributorName}» нет email — письмо не ушло`);
    else this.notify.error(`КП создано, но письмо «${r.distributorName}» не отправлено (ошибка SMTP)`);
  }
```

- [ ] **Step 3: bulk-price-modal — onSendGroup()**

В `bulk-price-modal.component.ts` внутри `onSendGroup` заменить блок отправки (строки ~370-393, от `const body = {...}` до конца subscribe):

```ts
    this.sendingGroupIds.add(distId);
    this.cdr.detectChanges();

    this.api.sendPriceRequests({ tenderId: this.tenderId, distributorIds: [distId], items }).subscribe({
      next: (results: any[]) => {
        this.sendingGroupIds.delete(distId);
        this.sentGroupIds.add(distId);
        const r = (results || [])[0];
        if (r && !r.emailSent) {
          this.notify.error(`КП создано (${items.length} поз.), но письмо «${group?.distributor?.name || ''}» не ушло${r.reason === 'NO_EMAIL' ? ' — нет email' : ' (ошибка SMTP)'}`);
        } else {
          this.notify.success(`КП отправлено: ${group?.distributor?.name || 'дистрибьютор'} (${items.length} поз.)`);
        }
        this.cdr.detectChanges();
      },
      error: (err: any) => {
        this.sendingGroupIds.delete(distId);
        const msg = err?.error?.message || err?.message || 'Не удалось отправить КП';
        this.notify.error('Ошибка отправки: ' + msg);
        this.cdr.detectChanges();
      }
    });
```

- [ ] **Step 4: Сборка фронта**

Run: `cd frontend && npm run build`
Expected: успех. Проверить, что `bulkPriceSend(` и (в этих двух компонентах) `createPriceRequest(` больше не используются: `grep -rn "bulkPriceSend(\|createPriceRequest(" frontend/src/app/pages/private-requests frontend/src/app/pages/tenders/bulk-price-modal.component.ts` → пусто.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/private-requests/private-request-card.component.ts \
  frontend/src/app/pages/tenders/bulk-price-modal.component.ts
git commit -m "feat(ui): частные заявки и bulk-модалка шлют КП через единый канал (реальные письма + честные тосты)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Финальный гейт — полный прогон, live-проверка, документация

**Files:**
- Modify: `CLAUDE.md` (блок в §8, эндпоинты в §15, вычеркнуть пункт бэклога в §16)
- Никакого нового кода; фиксы регрессий — по месту.

- [ ] **Step 1: Полный бэкенд-прогон**

```bash
lsof -ti :8080 | xargs kill -9 || true
./gradlew test
```
(sandbox off). Expected: только 2 известных падения `ApplyAutoFillServiceTest`. Любое другое падение — чинить до зелёного.

- [ ] **Step 2: Сборка фронта**

Run: `cd frontend && npm run build` → успех.

- [ ] **Step 3: Live-проверка (Playwright MCP, главная сессия — не субагент)**

Прогон по чеклисту (бэк: `./gradlew bootRun`; MailHog поднят: `docker ps | grep mailhog` или `docker run -d --name nir2-mailhog -p 1025:1025 -p 8025:8025 mailhog/mailhog`; фронт `npm start`):
1. Логин admin/admin, рынок KZ, `/tenders`, открыть импортированный тендер (или любой с лотами).
2. «Подобрать» на лоте без структурных габаритов, но со спекой с «не более …х…х… мм» → виден блок «📐 Ограничения из спеки»; «☑ Утвердить модель» → бейдж «Предложена для лота», в таблице лотов строка «Предложено: …».
3. Отметить 2 лота чекбоксами → «Запросить КП по выбранным (2)» → панель: поставщики, у «возит: <бренд>» предотметка → «Отправить запросы (N)».
4. Тост «Создано запросов: N, писем отправлено: M»; секция «Запросы КП» пополнилась; в позициях без модели — «— по лоту».
5. MailHog `http://localhost:8025`: письмо с темой `[КП-<id>] Запрос КП по тендеру № …`, тело: ТОО «West-Med», позиции (модель с РУ / лот со спекой), ссылка на объявление, «НЦЭЛС РК».
6. Кнопка «КП» в строке лота → панель с одним лотом.
7. `/private-requests` → карточка → «Запросить КП» по строкам → тост про письмо, письмо в MailHog с «по заявке ЧЗ-…».
8. Рынок RF → тендер → bulk-модалка «Запросить КП по всему тендеру» → письмо в MailHog с «ООО «РЕГИОН-МЕД»» и «Росздравнадзора».
Скриншоты ключевых состояний. Регрессии — чинить и перепроверять.

- [ ] **Step 4: Обновить CLAUDE.md**

В §8 добавить пункт (после «Реестр-сверка…»):

```markdown
- **Лотовый запрос КП + предложенная модель (оба рынка):** единый канал отправки `POST /api/price-requests/send` (`PriceRequestSendService` + `KpEmailComposer` — брендинг по `pr.getMarket()`, токен `[КП-id]`, № РУ, ссылка на объявление, спека с обрезкой 1200; результат per-поставщик `{emailSent, reason NO_EMAIL/SEND_FAILED}` — запись КП живёт даже без письма). На него переведены ВСЕ флоу: лотовый (чекбоксы лотов + кнопка «КП» в строке + панель поставщиков с подсказками `GET /api/tenders/{id}/lot-sourcing` — бренд предложенной модели или производители реестр-кандидатов ≥0.35, `BrandMatch`), smart-match, bulk-модалка, частные заявки (дыра «SENT без письма» закрыта). «Предложенная модель» — `tender_lot.proposed_equipment_id` (V4, ON DELETE SET NULL), апрув из smart-match (`POST/DELETE /api/lots/{id}/proposed-equipment`, гард чужого рынка — em.find обходит hibernate-фильтр), подставляется в КП-items. `SpecConstraintExtractor` — «не более A×B×C мм/см/м» + вес кг/г из текста спеки («не менее» игнорируется), питает `scoreLot`, когда структурные поля лота пусты; `specDerived` в ответе матча показывается в smart-match. Письмостроение из `BulkPriceRequestService` выпилено (остался `buildPreview`); `POST /api/bulk-price/send` — тонкий делегат.
```

В §15 в список API добавить: `` `/api/price-requests/send` ``, `` `/api/tenders/{id}/lot-sourcing` ``, `` `/api/lots/{id}/proposed-equipment` (POST/DELETE) ``.

В §16 (бэклог) добавить два пункта: «Удалить тонкий делегат `/api/bulk-price/send` (фронт уже на `/api/price-requests/send`)» и «Пооосевые ограничения из спеки („длина не более X" отдельными фразами)». Существующие пункты не трогать.

- [ ] **Step 5: Commit + отчёт**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md — лотовый запрос КП, предложенная модель, единый канал отправки

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Дать пользователю click-by-click тур «куда смотреть» (по шагам live-проверки). Дальше — whole-branch review (Opus) и merge по superpowers:finishing-a-development-branch.

---

## Порядок и зависимости

```
T1 (V4+апрув) ──┬─→ T6 (lot-sourcing) ─→ T7 (фронт-флоу) ─→ T8 (smart-match) ─→ T9 (перевод флоу) ─→ T10 (гейт)
T2 (extractor) ─┴─→ T3 (scoreLot) ────────────────────────────↗ (T8 показывает specDerived)
T4 (composer) ──→ T5 (send-сервис+эндпоинт) ──→ T7/T8/T9
```

Последовательное исполнение T1→T10 удовлетворяет все зависимости.
