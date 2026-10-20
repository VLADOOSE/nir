# «Модель в работе» лота — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> В этом проекте — **только executing-plans в основной сессии, без субагентов** (CLAUDE.md §2).

**Goal:** оператор закрепляет за лотом тендера KZ модель (наш товар сайта или любую запись реестра НЦЭЛС) с заметкой «почему», видит сверку ТЗ с ней, и модель по готовому пути уходит в запрос цен поставщикам и в заявку на участие.

**Architecture:** «модель в работе» — это существующая предложенная модель лота (`tender_lot.proposed_equipment_id` → `med_equipment`). Новое: связь позиции каталога с товаром сайта (V40), заметка и «кто/когда» у лота, одна служба `LotModelService` для всех путей установки модели (с событием в «Истории»), поиск по нашим товарам и реестру, сверка закреплённой модели с ТЗ на лету (наш товар — карточка снимка каталога, модель реестра — карточка РУ ndda.kz, позиция каталога — её описание), чип и фильтр в списке тендеров, строка в «📋 Для ИИ».

**Tech Stack:** Spring Boot 3.5.6 / Java 17, JPA, Flyway, PostgreSQL 17; Angular 21 standalone; JUnit 5, AssertJ, Mockito, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-20-lot-model-in-work-design.md`

## Global Constraints

- Закрепить наш товар или запись реестра — только на рынке KZ (`MarketContext.get() == Market.KZ`, иначе 400 «Только для Казахстана»). Снять модель и править заметку — на любом рынке (модель в работе у РФ-лотов ставит «Утвердить» подбора по каталогу).
- Лот — только своего рынка: `findById` = `em.find`, фильтр рынка его не режет — явная сверка `lot.getTender().getMarket()` (иначе 404).
- Запись — `@PreAuthorize("hasRole('ADMIN')")`; чтение (поиск, сверка) — любой вошедший.
- Заметка — до 1000 символов (иначе 400); новая модель без заметки заметку очищает; та же модель без заметки — ничего не меняет; та же модель с заметкой — меняет только заметку.
- Сеть (ndda.kz) — вне транзакции; сбой ndda.kz закреплению не мешает.
- Каждый коммит — строка `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- `./gradlew` и psql — с `dangerouslyDisableSandbox: true`; перед прогоном: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`. Команды — из корня: `cd /Users/vlad/IdeaProjects/AIS && …`.
- Фронт: новые компоненты — только в ленивых чанках (`@defer`), начальный бандл ≤ 1,5 МБ (сейчас 1,43); результаты промисов — `.then`, не нативный `await`; классы компонентов — не `.empty / .subtitle / .counter / .badge / .form-actions / .field-error` (UI-kit), сам kit (`.btn btn-primary`, `.btn btn-line`, `.error-banner`, `.form-actions`) — брать намеренно.
- Ветка — `feature/lot-model-in-work`; мерж в `main` — `--ff-only`; push — оператор (`! git push origin main`).

## Review Focus

1. № РУ товара сайта записан иначе, чем в реестре (регистр, двойные пробелы) — позиция каталога всё равно связывается с записью реестра, и РУ уходит в письмо поставщику (тест — задача 3).
2. Товар снят с сайта после закрепления — модель остаётся, сверка строится из БД; в поиске снятых нет (тесты — задачи 4, 5).
3. ndda.kz недоступен при закреплении модели реестра — модель закреплена, сверка «Карточка РУ недоступна — повторите позже» с «Повторить» (тесты — задача 5).
4. Та же модель закреплена повторно («Взять в работу» дважды) — без второго события, без потери заметки (тест — задача 3).
5. Лот другого рынка / рынок РФ — 404 / 400 на каждом пути записи и чтения (тесты — задачи 3, 4, 5, 6).

---

### Task 1: Данные (V40), поля лота и позиции каталога, ответы API

**Files:**
- Create: `src/main/resources/db/migration/V40__lot_model_in_work.sql`
- Modify: `src/main/java/com/vladoose/nir/entity/MedEquipment.java` (поле после `registrationCheckedAt`)
- Modify: `src/main/java/com/vladoose/nir/entity/TenderLot.java` (поля после `proposedEquipment`)
- Modify: `src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java`, `src/main/java/com/vladoose/nir/dto/response/ProposedEquipmentResponse.java`
- Modify: `src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java`, `src/main/java/com/vladoose/nir/mapper/MedEquipmentMapper.java`
- Test: `src/test/java/com/vladoose/nir/lotmodel/LotModelSchemaTest.java`

**Interfaces:**
- Produces: `MedEquipment.siteProduct` (`SiteProduct`, LAZY); `TenderLot.modelNote / modelSetBy / modelSetAt` (`String`, `String`, `OffsetDateTime`); `TenderLotResponse.modelNote / modelSetBy / modelSetAt`; `ProposedEquipmentResponse.siteProductId (Long) / productUrl (String) / source (String: SITE | REGISTRY | CATALOG) / regExpires (LocalDate) / regActive (Boolean)`; маппер ставит `siteProductId`, `source`, `regExpires` (ссылку и `regActive` ставит `LotModelViews` — задача 5).

- [ ] **Step 1: Тест схемы и источника модели**

```java
package com.vladoose.nir.lotmodel;

import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.mapper.TenderLotMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** «Модель в работе» (спека 2026-10-20-lot-model-in-work §4): V40 и источник модели в ответе лота. */
@SpringBootTest
class LotModelSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenderLotMapper mapper;

    @Test
    void v40Columns() {
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'tender_lot' "
                + "and column_name like 'model\\_%'", String.class)).containsExactlyInAnyOrder("model_note", "model_set_by", "model_set_at");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'med_equipment' "
                + "and column_name = 'site_product_id'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'uq_med_equipment_site_product'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void sourceOfModel() {
        SiteProduct sp = SiteProduct.builder().id(7L).name("Дефибриллятор ДКИ-Н-12").build();
        MedRegistry reg = MedRegistry.builder().regNumber("РК-1").name("x").expirationDate(LocalDate.of(2030, 1, 1)).build();
        assertThat(mapper.proposedEquipmentToResponse(MedEquipment.builder().name("a").siteProduct(sp).registration(reg).build()))
                .satisfies(r -> {
                    assertThat(r.getSource()).isEqualTo("SITE");
                    assertThat(r.getSiteProductId()).isEqualTo(7L);
                    assertThat(r.getRegExpires()).isEqualTo(LocalDate.of(2030, 1, 1));
                });
        assertThat(mapper.proposedEquipmentToResponse(MedEquipment.builder().name("b").registration(reg).build()).getSource())
                .isEqualTo("REGISTRY");
        assertThat(mapper.proposedEquipmentToResponse(MedEquipment.builder().name("c").build()).getSource()).isEqualTo("CATALOG");
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lotmodel.LotModelSchemaTest' > .superpowers/sdd/2026-10-20-lot-model-in-work/t1.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t1.log`
Expected: FAIL — компиляция: нет `siteProduct(...)` у билдера, нет `getSource`, `getSiteProductId`.

- [ ] **Step 3: Миграция, поля, мапперы**

`V40__lot_model_in_work.sql`:
```sql
-- «Модель в работе» лота (спека 2026-10-20-lot-model-in-work §4): позиция каталога из нашего товара сайта — одна на товар;
-- у лота — заметка «почему эта модель» (вывод ИИ) и кто / когда закрепил.
ALTER TABLE med_equipment ADD COLUMN site_product_id BIGINT REFERENCES site_product (id) ON DELETE SET NULL;
CREATE UNIQUE INDEX uq_med_equipment_site_product ON med_equipment (site_product_id) WHERE site_product_id IS NOT NULL;
ALTER TABLE tender_lot ADD COLUMN model_note VARCHAR(1000);
ALTER TABLE tender_lot ADD COLUMN model_set_by VARCHAR(100);
ALTER TABLE tender_lot ADD COLUMN model_set_at TIMESTAMPTZ;
```

`MedEquipment` (после `registrationCheckedAt`):
```java
    /** Наш товар сайта, из которого заведена позиция (V40): «модель в работе» лота — наш товар. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_product_id")
    private SiteProduct siteProduct;
```

`TenderLot` (после `proposedEquipment`):
```java
    /** «Почему эта модель» — заметка к модели в работе (вывод ИИ), V40. */
    @Column(name = "model_note", length = 1000)
    private String modelNote;

    /** Кто и когда последним ставил или снимал модель в работе (V40). */
    @Column(name = "model_set_by", length = 100)
    private String modelSetBy;

    @Column(name = "model_set_at")
    private OffsetDateTime modelSetAt;
```

`TenderLotResponse` (после `proposedEquipment`):
```java
    private String modelNote;          // «почему эта модель» (V40)
    private String modelSetBy;
    private OffsetDateTime modelSetAt;
```

`ProposedEquipmentResponse` (после `regNumber`):
```java
    private Long siteProductId;        // наш товар сайта
    private String productUrl;         // ссылка на товар сайта (ставит LotModelViews)
    private String source;             // SITE | REGISTRY | CATALOG
    private java.time.LocalDate regExpires;
    private Boolean regActive;         // РУ действует (ставит LotModelViews)
```

`TenderLotMapper`: в `toEntity` и `updateEntity` — рядом с `@Mapping(target = "proposedEquipment", ignore = true)`:
```java
    @Mapping(target = "modelNote", ignore = true)
    @Mapping(target = "modelSetBy", ignore = true)
    @Mapping(target = "modelSetAt", ignore = true)
```
и в `proposedEquipmentToResponse` перед `return r;`:
```java
        r.setSiteProductId(e.getSiteProduct() != null ? e.getSiteProduct().getId() : null);
        r.setSource(e.getSiteProduct() != null ? "SITE" : e.getRegistration() != null ? "REGISTRY" : "CATALOG");
        r.setRegExpires(e.getRegistration() != null ? e.getRegistration().getExpirationDate() : null);
```

`MedEquipmentMapper`: в `toEntity` и `updateEntity` — рядом с `@Mapping(target = "registration", ignore = true)`:
```java
    @Mapping(target = "siteProduct", ignore = true)
```

- [ ] **Step 4: Зелёный + мутация**

Run: та же команда, что в Step 2.
Expected: PASS (2 теста). Мутация: в маппере поменять местами `"SITE"` и `"REGISTRY"` → `sourceOfModel` красный; вернуть (копией файла, CLAUDE.md §14).

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V40__lot_model_in_work.sql \
  src/main/java/com/vladoose/nir/entity/MedEquipment.java src/main/java/com/vladoose/nir/entity/TenderLot.java \
  src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java src/main/java/com/vladoose/nir/dto/response/ProposedEquipmentResponse.java \
  src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java src/main/java/com/vladoose/nir/mapper/MedEquipmentMapper.java \
  src/test/java/com/vladoose/nir/lotmodel/LotModelSchemaTest.java
git commit -m "feat(lot-model): V40 — товар сайта у позиции каталога, заметка и «кто/когда» у лота

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Карточка любой модели для сверки и общий текст ТЗ лота

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/productwatch/fit/ProductCards.java` (рядом с `of`)
- Modify: `src/main/java/com/vladoose/nir/service/productwatch/ProductMatchService.java` (`lotFitText`, `specOf`, `descriptionOf`; `evaluate` — через них)
- Test: `src/test/java/com/vladoose/nir/service/productwatch/fit/ProductCardsModelTest.java`, `src/test/java/com/vladoose/nir/service/productwatch/ProductMatchLotTextTest.java`

**Interfaces:**
- Produces: `ProductCards.ofRegistry(MedRegistry r, LocalDate today) → ProductCard` (productId 0, бренд — производитель, статус: бессрочно — ACTIVE, срока нет — UNKNOWN, срок не раньше today — ACTIVE, иначе EXPIRED; факты — `textFacts(techChars, REG)`); `ProductCards.ofEquipment(MedEquipment e) → ProductCard` (факты — `textFacts(spec, DESCRIPTION)`, статус UNKNOWN); `ProductMatchService.lotFitText(TenderLot l) → String` (static).
- Типы: `ProductCard`, `ProductFact`, `ProductCards` — пакет `service.productwatch.fit`; `RegStatus`, `FitMark`, `FitCandidate`, `FitVerdict` — пакет `entity`.

- [ ] **Step 1: Тесты**

`ProductCardsModelTest`:
```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.FitMark;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Карточки «модели в работе» (спека 2026-10-20-lot-model-in-work §6): модель реестра — по карточке РУ, позиция каталога — по описанию. */
class ProductCardsModelTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    static MedRegistry reg(Boolean unlimited, LocalDate expires) {
        return MedRegistry.builder().regNumber("РК МИ (МТ)-0№099001").name("Дефибриллятор BeneHeart D3").producer("Mindray")
                .purpose("Дефибрилляция и кардиоверсия").techChars("Энергия разряда: не менее 360 Дж; масса не более 6 кг")
                .unlimited(unlimited).expirationDate(expires).build();
    }

    @Test
    void registryCard_factsFromRuCard_andStatusBySrok() {
        ProductCard c = ProductCards.ofRegistry(reg(true, null), TODAY);
        assertThat(c.name()).isEqualTo("Дефибриллятор BeneHeart D3");
        assertThat(c.brand()).isEqualTo("Mindray");
        assertThat(c.regNumber()).isEqualTo("РК МИ (МТ)-0№099001");
        assertThat(c.regStatus()).isEqualTo(RegStatus.ACTIVE);
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.KG);
            assertThat(f.source()).isEqualTo(ProductCards.REG);
        });
        assertThat(c.stems()).anyMatch(s -> s.startsWith("дефибр"));
        assertThat(ProductCards.ofRegistry(reg(false, TODAY.minusDays(1)), TODAY).regStatus()).isEqualTo(RegStatus.EXPIRED);
        assertThat(ProductCards.ofRegistry(reg(false, TODAY), TODAY).regStatus()).isEqualTo(RegStatus.ACTIVE);
        assertThat(ProductCards.ofRegistry(reg(false, null), TODAY).regStatus()).isEqualTo(RegStatus.UNKNOWN);
    }

    @Test
    void registryCard_comparesWithSpec() {
        FitCandidate c = SpecFit.compare(SpecRequirements.extract("масса не более 8 кг; энергия разряда не менее 200 Дж"),
                ProductCards.ofRegistry(reg(true, null), TODAY));
        assertThat(c.rows()).filteredOn(r -> r.text().startsWith("масса")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.OK));
    }

    @Test
    void equipmentCard_factsFromSpec() {
        ProductCard c = ProductCards.ofEquipment(MedEquipment.builder().name("Монитор пациента").manufact("Завод")
                .spec("Масса не более 4 кг").build());
        assertThat(c.regStatus()).isEqualTo(RegStatus.UNKNOWN);
        assertThat(c.brand()).isEqualTo("Завод");
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.KG);
            assertThat(f.source()).isEqualTo(ProductCards.DESCRIPTION);
        });
    }
}
```

`ProductMatchLotTextTest`:
```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.productwatch.fit.SpecRequirements;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Текст ТЗ лота для сверки — один на отметку, сверку модели в работе и «Для ИИ» (спека 2026-10-20-lot-model-in-work §6). */
class ProductMatchLotTextTest {

    static TenderLot lot(TechSpecStatus status, String spec) {
        TenderLot l = new TenderLot();
        l.setTechSpecStatus(status);
        l.setRequiredSpec(spec);
        return l;
    }

    @Test
    void parsedSpec_usesSpecBody_elsePlatformDescription() {
        String spec = "Наименование закупки: Аспиратор\nХарактеристики закупаемых товаров: производительность не менее 20 л/мин";
        assertThat(ProductMatchService.lotFitText(lot(TechSpecStatus.OK, spec))).isEqualTo(SpecRequirements.body(spec));
        assertThat(ProductMatchService.lotFitText(lot(TechSpecStatus.PENDING, "строка 1\nстрока 2")))
                .isEqualTo(SpecRequirements.reflowText("строка 1\nстрока 2"));
        assertThat(ProductMatchService.lotFitText(lot(null, null))).isEmpty();
    }
}
```

- [ ] **Step 2: Красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.ProductCardsModelTest' --tests 'com.vladoose.nir.service.productwatch.ProductMatchLotTextTest' > .superpowers/sdd/2026-10-20-lot-model-in-work/t2.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t2.log`
Expected: FAIL — компиляция: нет `ofRegistry`, `ofEquipment`, `lotFitText`.

- [ ] **Step 3: Реализация**

В `ProductCards` (после `of`; импорты `com.vladoose.nir.entity.MedEquipment`, `com.vladoose.nir.entity.MedRegistry`, `java.time.LocalDate`):
```java
    /**
     * Модель реестра «в работе» (спека 2026-10-20-lot-model-in-work §6): факты — числа кратких характеристик карточки РУ
     * (источник «РУ»), слова — наименование, назначение, область применения, характеристики; РУ — по сроку в реестре.
     */
    public static ProductCard ofRegistry(MedRegistry r, LocalDate today) {
        StringBuilder text = new StringBuilder();
        append(text, r.getName(), r.getPurpose(), r.getUseArea(), r.getTechChars());
        boolean unlimited = Boolean.TRUE.equals(r.getUnlimited());
        RegStatus status = unlimited ? RegStatus.ACTIVE : r.getExpirationDate() == null ? RegStatus.UNKNOWN
                : r.getExpirationDate().isBefore(today) ? RegStatus.EXPIRED : RegStatus.ACTIVE;
        return new ProductCard(0, r.getName(), r.getProducer(), null, r.getRegNumber(), status, r.getExpirationDate(), unlimited,
                List.copyOf(textFacts(r.getTechChars(), REG)), Set.copyOf(FitText.stems(text.toString())));
    }

    /** Позиция каталога без товара сайта и без РУ (подбор по каталогу): факты — из её описания. */
    public static ProductCard ofEquipment(MedEquipment e) {
        StringBuilder text = new StringBuilder();
        append(text, e.getName(), e.getSpec());
        return new ProductCard(0, e.getName(), e.getManufact(), null, null, RegStatus.UNKNOWN, null, false,
                List.copyOf(textFacts(e.getSpec(), DESCRIPTION)), Set.copyOf(FitText.stems(text.toString())));
    }
```

В `ProductMatchService` (рядом с `fitText`):
```java
    /** Текст ТЗ лота для сверки — один на отметку, сверку модели в работе и «Для ИИ». */
    public static String lotFitText(TenderLot l) {
        return fitText(specOf(l), descriptionOf(l));
    }

    /** Разобранное ТЗ лота (`tech_spec_status = OK`) или null. */
    static String specOf(TenderLot l) {
        return l.getTechSpecStatus() == TechSpecStatus.OK ? l.getRequiredSpec() : null;
    }

    /** Описание лота: разобранное ТЗ заменило описание площадки — берём его из шапки ТЗ (исключения групп смотрят на текст лота). */
    static String descriptionOf(TenderLot l) {
        return l.getTechSpecStatus() == TechSpecStatus.OK ? LotDescriptiveText.lotDescription(l.getRequiredSpec()) : l.getRequiredSpec();
    }
```
и в `evaluate` три строки цикла (`boolean spec = …`, комментарий, `String description = …`, `inputs.add(…)`) заменить одной:
```java
            inputs.add(new LotInput(l.getId(), l.getEquipName(), descriptionOf(l), specOf(l)));
```

- [ ] **Step 4: Зелёный + блок сверки + мутации**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.productwatch.*' --tests 'com.vladoose.nir.productwatch.*' > .superpowers/sdd/2026-10-20-lot-model-in-work/t2.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t2.log`
Expected: PASS (гейты сверки без изменений). Мутации (каждая — свой тест красный): `isBefore` → `isAfter` (`registryCard_…`); источник `REG` → `DESCRIPTION` в `ofRegistry`; `specOf` всегда `null` (`ProductMatchLotTextTest`).

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/productwatch/fit/ProductCards.java \
  src/main/java/com/vladoose/nir/service/productwatch/ProductMatchService.java \
  src/test/java/com/vladoose/nir/service/productwatch/fit/ProductCardsModelTest.java \
  src/test/java/com/vladoose/nir/service/productwatch/ProductMatchLotTextTest.java
git commit -m "feat(lot-model): карточка модели реестра и позиции каталога; общий текст ТЗ лота

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `LotModelService` — одна точка для модели лота, событие в «Истории»

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/lotmodel/LotModelService.java`
- Modify: `src/main/java/com/vladoose/nir/service/deal/DealEventService.java` (`MODEL`)
- Modify: `src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java`, `src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/RegistryMatchService.java` (`adoptForLot`), `src/main/java/com/vladoose/nir/service/ComplectService.java` (`adoptComponent`), `src/main/java/com/vladoose/nir/controller/TenderLotController.java` (`setProposedEquipment`, `clearProposedEquipment`)
- Test: `src/test/java/com/vladoose/nir/lotmodel/LotModelFixtures.java`, `src/test/java/com/vladoose/nir/lotmodel/LotModelServiceTest.java`

**Interfaces:**
- Consumes: Task 1 поля.
- Produces: `LotModelService.lotInMarket(Long) → TenderLot`; `apply(TenderLot, MedEquipment, String note) → TenderLot`; `clear(TenderLot) → TenderLot`; `clearModel(Long) → TenderLot`; `setNote(Long, String) → TenderLot`; `pinRegistry(Long lotId, String regNumber, String note) → TenderLot`; `pinSiteProduct(Long lotId, Long siteProductId, String note) → TenderLot`; `equipmentForRegistry(MedRegistry) → MedEquipment`; `equipmentForSiteProduct(SiteProduct) → MedEquipment`; `LotModelService.NOTE_MAX = 1000`; `DealEventService.MODEL = "MODEL"`; `MedEquipmentRepository.findFirstBySiteProductId(Long) → Optional<MedEquipment>`; `MedRegistryRepository.findByNormalizedRegNumber(String) → Optional<MedRegistry>`; тестовый помощник `LotModelFixtures.lot(TenderRepository, Market, String) → TenderLot`.

- [ ] **Step 1: Тесты**

`LotModelFixtures`:
```java
package com.vladoose.nir.lotmodel;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.TenderRepository;

/** Тендер с одним лотом для тестов «модели в работе». */
final class LotModelFixtures {
    private LotModelFixtures() {}

    static TenderLot lot(TenderRepository tenders, Market market, String name) {
        Tender t = Tender.builder().tenderNumber("ZZ-МОД-" + System.nanoTime()).status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(market).currency(market.currencyCode()).build();
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setLotNumber(1);
        l.setEquipName(name);
        t.getLots().add(l);
        tenders.saveAndFlush(t);
        return t.getLots().get(0);
    }
}
```

`LotModelServiceTest`:
```java
package com.vladoose.nir.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.DealEvent;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.DealEventRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.RegistryMatchService;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.lotmodel.LotModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** «Модель в работе» (спека 2026-10-20-lot-model-in-work §5): все пути — через одну службу, «кто/когда», событие. */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class LotModelServiceTest {

    static final String REG = "ZZ РК МИ (МТ)-0№099001";

    @Autowired LotModelService service;
    @Autowired RegistryMatchService registryMatch;
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired MedRegistryRepository registry;
    @Autowired DealEventRepository events;

    TenderLot lot;
    SiteProduct dki;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Дефибриллятор");
        registry.saveAndFlush(MedRegistry.builder().regNumber(REG).name("ZZ Дефибриллятор ДКИ-Н-12").producer("Аксион")
                .unlimited(true).build());
        registry.saveAndFlush(MedRegistry.builder().regNumber("ZZ РК МИ (МТ)-0№099002").name("ZZ Дефибриллятор BeneHeart D3")
                .producer("Mindray").unlimited(true).build());
        // на сайте номер записан иначе, чем в реестре: регистр, двойной пробел, пробел в конце
        dki = products.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("ZZ Дефибриллятор ДКИ-Н-12")
                .brand("Аксион").slug("zz-dki").shortDescription("Дефибриллятор-монитор").registrationCert("zz рк ми  (мт)-0№099001 ")
                .syncedAt(OffsetDateTime.now()).build());
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    List<DealEvent> modelEvents(TenderLot l) {
        return events.findByTenderIdOrderByOccurredAtAscIdAsc(l.getTender().getId()).stream()
                .filter(e -> DealEventService.MODEL.equals(e.getCode())).toList();
    }

    @Test
    void pinSiteProduct_linksRegistryByNormalizedNumber_andWritesEvent() {
        TenderLot r = service.pinSiteProduct(lot.getId(), dki.getId(), "  ИИ: подходит 30 из 34  ");
        MedEquipment e = r.getProposedEquipment();
        assertThat(e.getSiteProduct().getId()).isEqualTo(dki.getId());
        assertThat(e.getManufact()).isEqualTo("Аксион");
        assertThat(e.getSpec()).isEqualTo("Дефибриллятор-монитор");
        assertThat(e.getRegistration()).isNotNull();
        assertThat(e.getRegistration().getRegNumber()).isEqualTo(REG);
        assertThat(r.getModelNote()).isEqualTo("ИИ: подходит 30 из 34");
        assertThat(r.getModelSetBy()).isEqualTo("vlad");
        assertThat(r.getModelSetAt()).isNotNull();
        assertThat(modelEvents(lot)).singleElement().satisfies(ev -> {
            assertThat(ev.getText()).isEqualTo("Лот «ZZ Дефибриллятор»: модель в работе — ZZ Дефибриллятор ДКИ-Н-12 (Аксион)");
            assertThat(ev.getAuthor()).isEqualTo("vlad");
            assertThat(ev.getRefId()).isEqualTo(lot.getId());
        });
    }

    @Test
    void sameSiteProduct_onePosition_renameFollows() {
        Long first = service.pinSiteProduct(lot.getId(), dki.getId(), null).getProposedEquipment().getId();
        dki.setName("ZZ Дефибриллятор ДКИ-Н-12 «Аксион»");
        TenderLot other = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Второй лот");
        MedEquipment e = service.pinSiteProduct(other.getId(), dki.getId(), null).getProposedEquipment();
        assertThat(e.getId()).isEqualTo(first);
        assertThat(e.getName()).isEqualTo("ZZ Дефибриллятор ДКИ-Н-12 «Аксион»");
    }

    @Test
    void notes_sameModel_newModel() {
        service.pinSiteProduct(lot.getId(), dki.getId(), "почему");
        OffsetDateTime at = lot.getModelSetAt();
        service.pinSiteProduct(lot.getId(), dki.getId(), null);                       // та же модель без заметки — ничего
        assertThat(lot.getModelNote()).isEqualTo("почему");
        assertThat(lot.getModelSetAt()).isEqualTo(at);
        service.pinSiteProduct(lot.getId(), dki.getId(), "другая причина");           // та же модель с заметкой — только заметка
        assertThat(lot.getModelNote()).isEqualTo("другая причина");
        assertThat(modelEvents(lot)).hasSize(1);
        service.pinRegistry(lot.getId(), "ZZ РК МИ (МТ)-0№099002", null);            // другая модель без заметки — заметка очищена
        assertThat(lot.getModelNote()).isNull();
        assertThat(lot.getProposedEquipment().getRegistration().getRegNumber()).isEqualTo("ZZ РК МИ (МТ)-0№099002");
        assertThat(modelEvents(lot)).hasSize(2);
    }

    @Test
    void clearModel_andNoteRules() {
        service.pinRegistry(lot.getId(), REG, null);
        assertThat(service.setNote(lot.getId(), "  ИИ советует  ").getModelNote()).isEqualTo("ИИ советует");
        assertThatThrownBy(() -> service.setNote(lot.getId(), "x".repeat(1001))).isInstanceOf(BadRequestException.class);
        TenderLot r = service.clearModel(lot.getId());
        assertThat(r.getProposedEquipment()).isNull();
        assertThat(r.getModelNote()).isNull();
        assertThat(modelEvents(lot)).last().satisfies(ev -> assertThat(ev.getText()).isEqualTo("Лот «ZZ Дефибриллятор»: модель снята"));
        assertThatThrownBy(() -> service.setNote(lot.getId(), "x")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void guards() {
        TenderLot rf = LotModelFixtures.lot(tenders, Market.RF, "ZZ РФ");
        assertThatThrownBy(() -> service.pinSiteProduct(rf.getId(), dki.getId(), null)).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.pinRegistry(rf.getId(), REG, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Только для Казахстана");
        MarketContext.set(Market.KZ);
        dki.setRemovedAt(OffsetDateTime.now());
        assertThatThrownBy(() -> service.pinSiteProduct(lot.getId(), dki.getId(), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("снят с сайта");
        assertThatThrownBy(() -> service.pinRegistry(lot.getId(), "ZZ-НЕТ-ТАКОГО", null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void oldPathAdoptForLot_goesThroughService_once() {
        TenderLot r = registryMatch.adoptForLot(lot.getId(), REG);
        assertThat(r.getModelSetBy()).isEqualTo("vlad");
        assertThat(modelEvents(lot)).hasSize(1);
        registryMatch.adoptForLot(lot.getId(), REG);                                   // повторно — без второго события
        assertThat(modelEvents(lot)).hasSize(1);
    }
}
```

- [ ] **Step 2: Красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lotmodel.LotModelServiceTest' > .superpowers/sdd/2026-10-20-lot-model-in-work/t3.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t3.log`
Expected: FAIL — компиляция: нет `LotModelService`, `DealEventService.MODEL`.

- [ ] **Step 3: Реализация**

`DealEventService` (рядом с `WINNER`):
```java
    public static final String MODEL = "MODEL";
```

`MedEquipmentRepository`:
```java
    /** Позиция каталога, заведённая из нашего товара сайта (V40: одна на товар). */
    Optional<MedEquipment> findFirstBySiteProductId(Long siteProductId);
```

`MedRegistryRepository`:
```java
    /** № РУ без учёта регистра и лишних пробелов (как `RegNumbers.normalize`): на сайте номер бывает записан иначе, чем в реестре. */
    @Query(value = "SELECT * FROM med_registry WHERE upper(btrim(regexp_replace(reg_number, '\\s+', ' ', 'g'))) = :n LIMIT 1",
            nativeQuery = true)
    Optional<MedRegistry> findByNormalizedRegNumber(@Param("n") String normalized);
```

`LotModelService`:
```java
package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * «Модель в работе» лота (спека 2026-10-20-lot-model-in-work §5) — предложенная модель лота. Все пути, которые её ставят и
 * снимают (окно «📌 Модель…», «Взять в работу» в «Подборе» и комплектности, «Утвердить» в подборе по каталогу), идут через
 * эту службу: кто и когда, заметка, событие в «Истории» тендера.
 */
@Service
public class LotModelService {

    public static final int NOTE_MAX = 1000;
    private static final String NO_MAKER = "не указан";

    private final TenderLotRepository lots;
    private final MedEquipmentRepository equipment;
    private final MedRegistryRepository registry;
    private final SiteProductRepository siteProducts;
    private final DealEventService events;

    public LotModelService(TenderLotRepository lots, MedEquipmentRepository equipment, MedRegistryRepository registry,
                           SiteProductRepository siteProducts, DealEventService events) {
        this.lots = lots;
        this.equipment = equipment;
        this.registry = registry;
        this.siteProducts = siteProducts;
        this.events = events;
    }

    /** Лот своего рынка: `findById` = `em.find`, фильтр рынка его не режет (CLAUDE.md §14). */
    public TenderLot lotInMarket(Long lotId) {
        TenderLot lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }
        return lot;
    }

    /** Поставить модель: та же модель — только заметка (если прислана); новая модель без заметки заметку очищает. */
    @Transactional
    public TenderLot apply(TenderLot lot, MedEquipment eq, String note) {
        String n = note(note);
        boolean same = lot.getProposedEquipment() != null && Objects.equals(lot.getProposedEquipment().getId(), eq.getId());
        if (same) {
            if (n == null) return lot;
            lot.setModelNote(n);
            return lots.save(lot);
        }
        lot.setProposedEquipment(eq);
        lot.setModelNote(n);
        stamp(lot);
        TenderLot saved = lots.save(lot);
        events.stage(lot.getTender().getId(), DealEventService.MODEL,
                "Лот «" + lotName(lot) + "»: модель в работе — " + label(eq), lot.getId());
        return saved;
    }

    @Transactional
    public TenderLot clear(TenderLot lot) {
        if (lot.getProposedEquipment() == null) return lot;
        lot.setProposedEquipment(null);
        lot.setModelNote(null);
        stamp(lot);
        TenderLot saved = lots.save(lot);
        events.stage(lot.getTender().getId(), DealEventService.MODEL, "Лот «" + lotName(lot) + "»: модель снята", lot.getId());
        return saved;
    }

    @Transactional
    public TenderLot clearModel(Long lotId) {
        return clear(lotInMarket(lotId));
    }

    @Transactional
    public TenderLot setNote(Long lotId, String note) {
        TenderLot lot = lotInMarket(lotId);
        if (lot.getProposedEquipment() == null) throw new BadRequestException("У лота нет модели в работе — сначала закрепите модель");
        lot.setModelNote(note(note));
        return lots.save(lot);
    }

    @Transactional
    public TenderLot pinRegistry(Long lotId, String regNumber, String note) {
        TenderLot lot = lotInMarket(lotId);
        kzOnly();
        MedRegistry reg = registry.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ не найдено в реестре: " + regNumber));
        return apply(lot, equipmentForRegistry(reg), note);
    }

    @Transactional
    public TenderLot pinSiteProduct(Long lotId, Long siteProductId, String note) {
        TenderLot lot = lotInMarket(lotId);
        kzOnly();
        SiteProduct p = siteProducts.findById(siteProductId).filter(x -> x.getMarket() == Market.KZ)
                .orElseThrow(() -> new NotFoundException("Товар сайта не найден: id=" + siteProductId));
        if (p.getRemovedAt() != null) throw new BadRequestException("Товар снят с сайта — выберите другой");
        return apply(lot, equipmentForSiteProduct(p), note);
    }

    /**
     * Позиция каталога по записи реестра: та, что заведена из этой записи (№ РУ и наименование реестра), иначе новая.
     * Только № РУ мало: с тем же № бывают позиции-комплектующие («Взять в работу» в комплектности) и наш товар сайта.
     */
    public MedEquipment equipmentForRegistry(MedRegistry reg) {
        String name = trim255(reg.getName());
        return equipment.findFirstByRegistrationRegNumberAndNameIgnoreCase(reg.getRegNumber(), name).orElseGet(() -> {
            MedEquipment e = new MedEquipment();
            e.setName(name);
            e.setManufact(reg.getProducer() != null && !reg.getProducer().isBlank() ? trim255(reg.getProducer()) : NO_MAKER);
            if (reg.getTechChars() != null && !reg.getTechChars().isBlank()) e.setSpec(reg.getTechChars());
            e.setRegistrationStatus(RegistrationStatus.REGISTERED);
            e.setRegistration(reg);
            e.setRegistrationCheckedAt(OffsetDateTime.now());
            e.setMarket(MarketContext.get());
            return equipment.save(e);
        });
    }

    /** Позиция каталога по нашему товару сайта — одна на товар; переименование на сайте подхватывается. */
    public MedEquipment equipmentForSiteProduct(SiteProduct p) {
        String name = trim255(p.getName());
        String maker = p.getBrand() != null && !p.getBrand().isBlank() ? trim255(p.getBrand()) : NO_MAKER;
        return equipment.findFirstBySiteProductId(p.getId()).map(e -> {
            e.setName(name);
            e.setManufact(maker);
            return e;
        }).orElseGet(() -> {
            MedEquipment e = new MedEquipment();
            e.setName(name);
            e.setManufact(maker);
            e.setSpec(p.getShortDescription());
            e.setSiteProduct(p);
            String n = RegNumbers.normalize(p.getRegistrationCert());
            MedRegistry reg = n == null ? null : registry.findByNormalizedRegNumber(n).orElse(null);
            e.setRegistrationStatus(reg != null ? RegistrationStatus.REGISTERED : RegistrationStatus.UNCHECKED);
            if (reg != null) {
                e.setRegistration(reg);
                e.setRegistrationCheckedAt(OffsetDateTime.now());
            }
            e.setMarket(Market.KZ);
            return equipment.save(e);
        });
    }

    private void kzOnly() {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
    }

    private static void stamp(TenderLot lot) {
        lot.setModelSetBy(CurrentUser.name());
        lot.setModelSetAt(OffsetDateTime.now());
    }

    static String note(String note) {
        if (note == null || note.isBlank()) return null;
        String n = note.strip();
        if (n.length() > NOTE_MAX) throw new BadRequestException("Заметка длиннее " + NOTE_MAX + " символов");
        return n;
    }

    private static String label(MedEquipment e) {
        return e.getName() + (e.getManufact() == null || NO_MAKER.equals(e.getManufact()) ? "" : " (" + e.getManufact() + ")");
    }

    private static String lotName(TenderLot l) {
        String n = Objects.toString(l.getEquipName(), "без названия");
        return n.length() > 80 ? n.substring(0, 80) + "…" : n;
    }

    private static String trim255(String s) {
        return s != null && s.length() > 255 ? s.substring(0, 255) : s;
    }
}
```

Старые пути — через службу:
- `RegistryMatchService.adoptForLot`: поле и параметр конструктора `LotModelService lotModel`; после поиска `reg` весь блок `MedEquipment eq = equipmentRepository.findFirstByRegistrationRegNumber(...)...` и `lot.setProposedEquipment(eq); return tenderLotRepository.save(lot);` заменить на
  ```java
          return lotModel.apply(lot, lotModel.equipmentForRegistry(reg), null);
  ```
  (если `trim255` в классе больше не используется — удалить).
- `ComplectService.adoptComponent`: поле и параметр конструктора `LotModelService lotModel`; `lot.setProposedEquipment(eq); return lotService.save(lot);` → `return lotModel.apply(lot, eq, null);`.
- `TenderLotController`: поле и параметр конструктора `LotModelService lotModel`; в `setProposedEquipment` — `lot.setProposedEquipment(eq); return mapper.toResponse(service.save(lot));` → `return mapper.toResponse(lotModel.apply(lot, eq, null));`; тело `clearProposedEquipment` → `return mapper.toResponse(lotModel.clearModel(id));`.

- [ ] **Step 4: Зелёный + соседи + мутации**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lotmodel.*' --tests 'com.vladoose.nir.service.RegistryAdoptTest' --tests 'com.vladoose.nir.service.ComplectAdoptTest' --tests 'com.vladoose.nir.tender.LotProposedEquipmentTest' --tests 'com.vladoose.nir.service.LotSourcingServiceTest' --tests 'com.vladoose.nir.integration.ImportPreservesLotWorkTest' > .superpowers/sdd/2026-10-20-lot-model-in-work/t3.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t3.log`
Expected: PASS. Мутации (каждая — свой тест красный): без ветки `if (same)` (второе событие — `oldPathAdoptForLot…`); `lot.setModelNote(n)` → `lot.setModelNote(n != null ? n : lot.getModelNote())` (`notes_…`); `findByNormalizedRegNumber(n)` → `findByRegNumber(p.getRegistrationCert())` (`pinSiteProduct_…`); без проверки `getRemovedAt` (`guards`); без `kzOnly()` в `pinRegistry` (`guards`); `adoptForLot` — назад к `lot.setProposedEquipment(eq)` (`oldPathAdoptForLot…`).

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/lotmodel/LotModelService.java \
  src/main/java/com/vladoose/nir/service/deal/DealEventService.java src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java \
  src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java src/main/java/com/vladoose/nir/service/RegistryMatchService.java \
  src/main/java/com/vladoose/nir/service/ComplectService.java src/main/java/com/vladoose/nir/controller/TenderLotController.java \
  src/test/java/com/vladoose/nir/lotmodel/LotModelFixtures.java src/test/java/com/vladoose/nir/lotmodel/LotModelServiceTest.java
git commit -m "feat(lot-model): одна служба модели лота — «кто/когда», заметка, событие; старые пути через неё

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Поиск модели — наши товары и реестр

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/lotmodel/LotModelSearch.java`, `src/main/java/com/vladoose/nir/dto/response/LotModelSearchResponse.java`
- Test: `src/test/java/com/vladoose/nir/lotmodel/LotModelSearchTest.java`

**Interfaces:**
- Consumes: `LotModelService.lotInMarket`; `RegistryMatchService.search(String, int) → List<RegistryCandidateResponse>` (геттеры `getRegNumber/getName/getProducer/getCountry/getExpirationDate/getUnlimited`); `MedRegistryRepository.findByNormalizedRegNumber`; `CatalogSource.get().card(long) → ProductCard`; `ProductText.normalize/words`; `WestmedLeadMapper.productUrl(siteUrl, slug)`; `RegNumbers.normalize`.
- Produces: `LotModelSearch.search(Long lotId, String q) → LotModelSearchResponse`; `record LotModelSearchResponse(List<Our> ours, List<Registry> registry)`, `record Our(long productId, String name, String brand, String sku, String regNumber, String regStatus, LocalDate regExpires, String url)`, `record Registry(String regNumber, String name, String producer, String country, LocalDate expires, boolean unlimited, boolean expired)`; `LotModelSearch.LIMIT = 10`.

- [ ] **Step 1: Тесты**

```java
package com.vladoose.nir.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotModelSearchResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.lotmodel.LotModelSearch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Поиск «модели в работе» (спека §5.4): слова — в названии, бренде или артикуле; снятые не ищутся; точный № РУ — первым. */
@SpringBootTest
@Transactional
class LotModelSearchTest {

    @Autowired LotModelSearch search;
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired MedRegistryRepository registry;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZQ Лот");
        products.saveAndFlush(product("ZZQ Дефибриллятор ДКИ-Н-12", "Аксион", "ZZQ-777", null));
        products.saveAndFlush(product("ZZQ Дефибриллятор старый", "Аксион", "ZZQ-778", OffsetDateTime.now()));
        registry.saveAndFlush(MedRegistry.builder().regNumber("ZZQ-РУ-4242").name("ZZQ Монитор пациента прикроватный")
                .producer("ZZQ Медтех").unlimited(true).build());
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    static SiteProduct product(String name, String brand, String sku, OffsetDateTime removed) {
        return SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name(name).brand(brand).sku(sku)
                .slug(sku.toLowerCase()).removedAt(removed).syncedAt(OffsetDateTime.now()).build();
    }

    @Test
    void ours_allWordsInNameBrandOrSku_removedSkipped() {
        assertThat(search.search(lot.getId(), "zzq дки").ours()).extracting(LotModelSearchResponse.Our::name)
                .containsExactly("ZZQ Дефибриллятор ДКИ-Н-12");
        assertThat(search.search(lot.getId(), "аксион zzq-777").ours()).hasSize(1)
                .allSatisfy(o -> assertThat(o.url()).endsWith("zzq-777"));
        assertThat(search.search(lot.getId(), "zzq дефибриллятор").ours()).extracting(LotModelSearchResponse.Our::name)
                .containsExactly("ZZQ Дефибриллятор ДКИ-Н-12");
        assertThat(search.search(lot.getId(), "z").ours()).isEmpty();
        assertThat(search.search(lot.getId(), "z").registry()).isEmpty();
    }

    @Test
    void registry_exactNumberFirst_andWords() {
        assertThat(search.search(lot.getId(), "zzq-ру-4242").registry()).first()
                .satisfies(r -> {
                    assertThat(r.regNumber()).isEqualTo("ZZQ-РУ-4242");
                    assertThat(r.unlimited()).isTrue();
                    assertThat(r.expired()).isFalse();
                });
        assertThat(search.search(lot.getId(), "ZZQ монитор прикроватный").registry())
                .extracting(LotModelSearchResponse.Registry::regNumber).contains("ZZQ-РУ-4242");
    }

    @Test
    void otherMarketLot_404_rfLot_400() {
        TenderLot rf = LotModelFixtures.lot(tenders, Market.RF, "ZZQ РФ");
        assertThatThrownBy(() -> search.search(rf.getId(), "дки")).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> search.search(rf.getId(), "дки"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Только для Казахстана");
    }
}
```

- [ ] **Step 2: Красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lotmodel.LotModelSearchTest' > .superpowers/sdd/2026-10-20-lot-model-in-work/t4.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t4.log`
Expected: FAIL — компиляция: нет `LotModelSearch`, `LotModelSearchResponse`.

- [ ] **Step 3: Реализация**

`LotModelSearchResponse`:
```java
package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.util.List;

/** Поиск «модели в работе» (спека 2026-10-20-lot-model-in-work §5.4): наши товары сайта и записи реестра НЦЭЛС. */
public record LotModelSearchResponse(List<Our> ours, List<Registry> registry) {
    public record Our(long productId, String name, String brand, String sku, String regNumber, String regStatus,
                      LocalDate regExpires, String url) {}
    public record Registry(String regNumber, String name, String producer, String country, LocalDate expires,
                           boolean unlimited, boolean expired) {}
}
```

`LotModelSearch`:
```java
package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotModelSearchResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.RegistryMatchService;
import com.vladoose.nir.service.productwatch.CatalogSnapshot;
import com.vladoose.nir.service.productwatch.CatalogSource;
import com.vladoose.nir.service.productwatch.ProductText;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Поиск «модели в работе» (спека 2026-10-20-lot-model-in-work §5.4): наши товары сайта и записи реестра НЦЭЛС. */
@Service
public class LotModelSearch {

    public static final int LIMIT = 10;
    private static final int MAX_QUERY = 200;

    private final LotModelService lotModel;
    private final SiteProductRepository siteProducts;
    private final MedRegistryRepository registry;
    private final RegistryMatchService registryMatch;
    private final CatalogSource catalog;
    private final String siteUrl;

    public LotModelSearch(LotModelService lotModel, SiteProductRepository siteProducts, MedRegistryRepository registry,
                          RegistryMatchService registryMatch, CatalogSource catalog,
                          @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.lotModel = lotModel;
        this.siteProducts = siteProducts;
        this.registry = registry;
        this.registryMatch = registryMatch;
        this.catalog = catalog;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public LotModelSearchResponse search(Long lotId, String q) {
        lotModel.lotInMarket(lotId);
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        String query = q == null ? "" : q.strip();
        if (query.length() < 2) return new LotModelSearchResponse(List.of(), List.of());
        if (query.length() > MAX_QUERY) query = query.substring(0, MAX_QUERY);
        return new LotModelSearchResponse(ours(query), registry(query));
    }

    /** Наши товары: каждое слово запроса — в названии, бренде или артикуле; снятые с сайта не ищутся. */
    private List<LotModelSearchResponse.Our> ours(String q) {
        List<String> words = ProductText.words(ProductText.normalize(q));
        if (words.isEmpty()) return List.of();
        CatalogSnapshot snap = catalog.get();
        return siteProducts.findByMarketAndRemovedAtIsNull(Market.KZ).stream()
                .filter(p -> {
                    String hay = ProductText.normalize(String.join(" ", nz(p.getName()), nz(p.getBrand()), nz(p.getSku())));
                    return words.stream().allMatch(hay::contains);
                })
                .sorted(Comparator.comparing((SiteProduct p) -> nz(p.getName()), String.CASE_INSENSITIVE_ORDER))
                .limit(LIMIT)
                .map(p -> {
                    ProductCard c = snap.card(p.getId());
                    return new LotModelSearchResponse.Our(p.getId(), p.getName(), p.getBrand(), p.getSku(),
                            RegNumbers.normalize(p.getRegistrationCert()), c == null ? RegStatus.UNKNOWN.name() : c.regStatus().name(),
                            c == null ? null : c.regExpires(),
                            p.getSlug() == null || p.getSlug().isBlank() ? null : WestmedLeadMapper.productUrl(siteUrl, p.getSlug()));
                }).toList();
    }

    /** Реестр: номер РУ в запросе (есть цифры) — точная запись первой; дальше — поиск по словам «Подбора». */
    private List<LotModelSearchResponse.Registry> registry(String q) {
        LocalDate today = LocalDate.now(Market.KZ.zone());
        List<LotModelSearchResponse.Registry> out = new ArrayList<>();
        if (q.chars().anyMatch(Character::isDigit)) {
            registry.findByNormalizedRegNumber(RegNumbers.normalize(q)).ifPresent(r -> out.add(row(r, today)));
        }
        for (RegistryCandidateResponse c : registryMatch.search(q, LIMIT)) {
            if (out.size() >= LIMIT) break;
            if (out.stream().anyMatch(r -> r.regNumber().equals(c.getRegNumber()))) continue;
            out.add(new LotModelSearchResponse.Registry(c.getRegNumber(), c.getName(), c.getProducer(), c.getCountry(),
                    c.getExpirationDate(), Boolean.TRUE.equals(c.getUnlimited()), expired(c.getUnlimited(), c.getExpirationDate(), today)));
        }
        return out;
    }

    private static LotModelSearchResponse.Registry row(MedRegistry r, LocalDate today) {
        return new LotModelSearchResponse.Registry(r.getRegNumber(), r.getName(), r.getProducer(), r.getCountry(), r.getExpirationDate(),
                Boolean.TRUE.equals(r.getUnlimited()), expired(r.getUnlimited(), r.getExpirationDate(), today));
    }

    static boolean expired(Boolean unlimited, LocalDate expires, LocalDate today) {
        return !Boolean.TRUE.equals(unlimited) && expires != null && expires.isBefore(today);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
```

- [ ] **Step 4: Зелёный + мутации**

Run: та же команда, что в Step 2.
Expected: PASS (3 теста). Мутации: `allMatch` → `anyMatch` (`ours_…`); `findByMarketAndRemovedAtIsNull` → `findByMarket` (`ours_…`: снятый в выдаче); без точного № РУ первым (`registry_exactNumberFirst…`).

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/lotmodel/LotModelSearch.java \
  src/main/java/com/vladoose/nir/dto/response/LotModelSearchResponse.java src/test/java/com/vladoose/nir/lotmodel/LotModelSearchTest.java
git commit -m "feat(lot-model): поиск модели — наши товары сайта и реестр НЦЭЛС

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Сверка модели в работе — чтение, ndda.kz, ответ

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/lotmodel/ModelSnapshot.java`, `LotModelReader.java`, `LotModelFit.java`, `LotModelViews.java` (пакет `service.lotmodel`)
- Create: `src/main/java/com/vladoose/nir/dto/response/LotModelFitResponse.java`
- Test: `src/test/java/com/vladoose/nir/lotmodel/LotModelFitTest.java` (Mockito, без Spring), `src/test/java/com/vladoose/nir/lotmodel/LotModelReaderTest.java` (Spring)

**Interfaces:**
- Consumes: Task 2 (`ofRegistry`, `ofEquipment`, `lotFitText`), Task 3 (`lotInMarket`, `apply`, `equipmentFor*`); `RegistryDetailService.detail(String) → RegistryDetailResponse` (пишет кеш карточки РУ в `med_registry`, ставит `detail_fetched_at`).
- Produces: `record ModelSnapshot(String text, ProductCard card, boolean needsDetail, String regNumber, LotModelFitResponse.Model model, String note, String setBy, OffsetDateTime setAt)`; `LotModelReader.read(Long lotId) → ModelSnapshot` (`@Transactional(readOnly = true)`); `LotModelFit.fit(Long lotId) → LotModelFitResponse`; `LotModelFit.prefetchCard(String regNumber) → boolean`; `LotModelViews.model(MedEquipment) → LotModelFitResponse.Model`, `LotModelViews.candidate(FitCandidate) → LotProductMatchDto.Candidate`, `LotModelViews.enrich(TenderLotResponse, TenderLot) → TenderLotResponse`; `record LotModelFitResponse(Model model, String note, String setBy, OffsetDateTime setAt, String card, String error, LotProductMatchDto.Candidate fit)` с `record Model(Long equipmentId, String name, String manufact, String source, Long siteProductId, String url, String regNumber, String regStatus, LocalDate regExpires, boolean regUnlimited)`; `card`: OK | MISSING | NONE.

- [ ] **Step 1: Тесты**

`LotModelFitTest`:
```java
package com.vladoose.nir.lotmodel;

import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.service.RegistryDetailService;
import com.vladoose.nir.service.lotmodel.LotModelFit;
import com.vladoose.nir.service.lotmodel.LotModelReader;
import com.vladoose.nir.service.lotmodel.LotModelViews;
import com.vladoose.nir.service.lotmodel.ModelSnapshot;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import com.vladoose.nir.service.productwatch.fit.ProductCards;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Сверка модели в работе (спека §6): карточка есть — сверка; карточки РУ нет — загрузить; ndda.kz недоступен — MISSING. */
class LotModelFitTest {

    static final LotModelFitResponse.Model MODEL = new LotModelFitResponse.Model(1L, "BeneHeart D3", "Mindray", "REGISTRY", null,
            null, "РК-1", "ACTIVE", null, true);
    static final String TEXT = "масса не более 8 кг; энергия разряда не менее 200 Дж";
    static final LotModelViews VIEWS = new LotModelViews("", null);

    static ProductCard card(String techChars) {
        return ProductCards.ofRegistry(MedRegistry.builder().regNumber("РК-1").name("BeneHeart D3").producer("Mindray")
                .techChars(techChars).unlimited(true).build(), LocalDate.of(2026, 10, 20));
    }

    static ModelSnapshot snap(ProductCard card, boolean needsDetail) {
        return new ModelSnapshot(TEXT, card, needsDetail, "РК-1", MODEL, "ИИ", "vlad", null);
    }

    @Test
    void cardReady_fitComputed() {
        LotModelReader reader = mock(LotModelReader.class);
        when(reader.read(5L)).thenReturn(snap(card("Масса не более 6 кг"), false));
        LotModelFitResponse r = new LotModelFit(reader, mock(RegistryDetailService.class), VIEWS).fit(5L);
        assertThat(r.card()).isEqualTo("OK");
        assertThat(r.fit().met()).isGreaterThanOrEqualTo(1);
        assertThat(r.note()).isEqualTo("ИИ");
        assertThat(r.model().name()).isEqualTo("BeneHeart D3");
    }

    @Test
    void cardNotLoaded_fetchedThenReread() {
        LotModelReader reader = mock(LotModelReader.class);
        RegistryDetailService details = mock(RegistryDetailService.class);
        when(reader.read(5L)).thenReturn(snap(card(null), true), snap(card("Масса не более 6 кг"), false));
        LotModelFitResponse r = new LotModelFit(reader, details, VIEWS).fit(5L);
        verify(details).detail("РК-1");
        assertThat(r.card()).isEqualTo("OK");
        assertThat(r.fit().met()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void nddaDown_missingWithoutFit_pinNotBroken() {
        LotModelReader reader = mock(LotModelReader.class);
        RegistryDetailService details = mock(RegistryDetailService.class);
        when(reader.read(5L)).thenReturn(snap(card(null), true));
        when(details.detail("РК-1")).thenThrow(new RuntimeException("timeout"));
        LotModelFit fit = new LotModelFit(reader, details, VIEWS);
        LotModelFitResponse r = fit.fit(5L);
        assertThat(r.card()).isEqualTo("MISSING");
        assertThat(r.fit()).isNull();
        assertThat(r.error()).isEqualTo("Карточка РУ недоступна — повторите позже");
        assertThat(fit.prefetchCard("РК-1")).isFalse();
    }

    @Test
    void noModel_none_noCard_none() {
        LotModelReader reader = mock(LotModelReader.class);
        when(reader.read(5L)).thenReturn(new ModelSnapshot(TEXT, null, false, null, null, null, null, null),
                new ModelSnapshot(TEXT, null, false, null, MODEL, null, null, null));
        LotModelFit fit = new LotModelFit(reader, mock(RegistryDetailService.class), VIEWS);
        LotModelFitResponse none = fit.fit(5L);
        assertThat(none.card()).isEqualTo("NONE");
        assertThat(none.model()).isNull();
        LotModelFitResponse noCard = fit.fit(5L);
        assertThat(noCard.card()).isEqualTo("NONE");
        assertThat(noCard.model()).isNotNull();
    }
}
```

`LotModelReaderTest`:
```java
package com.vladoose.nir.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.lotmodel.LotModelReader;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.service.lotmodel.ModelSnapshot;
import com.vladoose.nir.service.productwatch.ProductMatchService;
import com.vladoose.nir.service.productwatch.fit.SpecUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Чтение модели в работе для сверки (спека §6): наш товар (и снятый), реестр (карточка РУ), каталог. */
@SpringBootTest
@Transactional
class LotModelReaderTest {

    @Autowired LotModelReader reader;
    @Autowired LotModelService lotModel;
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired MedRegistryRepository registry;
    @Autowired MedEquipmentRepository equipment;

    @BeforeEach
    void kz() { MarketContext.set(Market.KZ); }

    @AfterEach
    void clear() { MarketContext.clear(); }

    @Test
    void siteProduct_removedAfterPin_cardFromDb() {
        TenderLot lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Аспиратор");
        SiteProduct p = products.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("ZZ Аспиратор хирургический").brand("ZZ").slug("zz-asp").syncedAt(OffsetDateTime.now()).build());
        lotModel.apply(lot, lotModel.equipmentForSiteProduct(p), null);
        p.setRemovedAt(OffsetDateTime.now());
        ModelSnapshot s = reader.read(lot.getId());
        assertThat(s.card()).isNotNull();
        assertThat(s.card().name()).isEqualTo("ZZ Аспиратор хирургический");
        assertThat(s.model().source()).isEqualTo("SITE");
        assertThat(s.model().url()).endsWith("zz-asp");
        assertThat(s.needsDetail()).isFalse();
    }

    @Test
    void registryModel_needsDetailUntilFetched() {
        TenderLot lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Дефибриллятор");
        MedRegistry r = registry.saveAndFlush(MedRegistry.builder().regNumber("ZZ-РУ-READ-1").name("ZZ Дефибриллятор D3")
                .producer("ZZ").unlimited(true).build());
        lotModel.apply(lot, lotModel.equipmentForRegistry(r), null);
        assertThat(reader.read(lot.getId()).needsDetail()).isTrue();
        r.setTechChars("Масса не более 6 кг");
        r.setDetailFetchedAt(OffsetDateTime.now());
        ModelSnapshot s = reader.read(lot.getId());
        assertThat(s.needsDetail()).isFalse();
        assertThat(s.model().source()).isEqualTo("REGISTRY");
        assertThat(s.model().regStatus()).isEqualTo("ACTIVE");
        assertThat(s.card().facts()).anySatisfy(f -> assertThat(f.unit()).isEqualTo(SpecUnit.KG));
    }

    @Test
    void catalogModel_cardOnlyWithSpec_andLotText() {
        TenderLot lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Монитор");
        lot.setRequiredSpec("масса не более 8 кг");
        MedEquipment bare = equipment.save(MedEquipment.builder().name("ZZ Монитор без описания").manufact("ZZ").market(Market.KZ).build());
        lotModel.apply(lot, bare, null);
        ModelSnapshot s = reader.read(lot.getId());
        assertThat(s.model().source()).isEqualTo("CATALOG");
        assertThat(s.card()).isNull();
        assertThat(s.text()).isEqualTo(ProductMatchService.lotFitText(lot));
        MedEquipment withSpec = equipment.save(MedEquipment.builder().name("ZZ Монитор 2").manufact("ZZ").market(Market.KZ)
                .spec("Масса не более 4 кг").build());
        lotModel.apply(lot, withSpec, null);
        assertThat(reader.read(lot.getId()).card()).isNotNull();
    }

    @Test
    void otherMarketLot_notFound() {
        TenderLot rf = LotModelFixtures.lot(tenders, Market.RF, "ZZ РФ");
        assertThatThrownBy(() -> reader.read(rf.getId())).isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lotmodel.LotModelFitTest' --tests 'com.vladoose.nir.lotmodel.LotModelReaderTest' > .superpowers/sdd/2026-10-20-lot-model-in-work/t5.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t5.log`
Expected: FAIL — компиляция: нет `ModelSnapshot`, `LotModelReader`, `LotModelFit`, `LotModelViews`, `LotModelFitResponse`.

- [ ] **Step 3: Реализация**

`LotModelFitResponse`:
```java
package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Сверка «модели в работе» с ТЗ лота (спека 2026-10-20-lot-model-in-work §6). card: OK | MISSING | NONE. */
public record LotModelFitResponse(Model model, String note, String setBy, OffsetDateTime setAt, String card, String error,
                                  LotProductMatchDto.Candidate fit) {
    public record Model(Long equipmentId, String name, String manufact, String source, Long siteProductId, String url,
                        String regNumber, String regStatus, LocalDate regExpires, boolean regUnlimited) {}
}
```

`ModelSnapshot`:
```java
package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.service.productwatch.fit.ProductCard;

import java.time.OffsetDateTime;

/** Всё для сверки модели в работе — одним чтением в транзакции; сеть (ndda.kz) — снаружи. */
public record ModelSnapshot(String text, ProductCard card, boolean needsDetail, String regNumber, LotModelFitResponse.Model model,
                            String note, String setBy, OffsetDateTime setAt) {}
```

`LotModelViews`:
```java
package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.dto.response.LotProductMatchDto;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.service.productwatch.CatalogSource;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** Как показать модель в работе: источник, ссылка на товар сайта, РУ (спека 2026-10-20-lot-model-in-work §5.5, §6). */
@Component
public class LotModelViews {

    private final String siteUrl;
    private final CatalogSource catalog;

    public LotModelViews(@Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl, CatalogSource catalog) {
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
        this.catalog = catalog;
    }

    public LotModelFitResponse.Model model(MedEquipment e) {
        SiteProduct p = e.getSiteProduct();
        MedRegistry r = e.getRegistration();
        String source = p != null ? "SITE" : r != null ? "REGISTRY" : "CATALOG";
        ProductCard c = p == null || catalog == null ? null : catalog.get().card(p.getId());
        String status;
        LocalDate expires;
        boolean unlimited;
        if (c != null) {
            status = c.regStatus().name();
            expires = c.regExpires();
            unlimited = c.regUnlimited();
        } else if (r != null) {
            unlimited = Boolean.TRUE.equals(r.getUnlimited());
            expires = r.getExpirationDate();
            status = unlimited ? "ACTIVE" : expires == null ? "UNKNOWN"
                    : expires.isBefore(LocalDate.now(Market.KZ.zone())) ? "EXPIRED" : "ACTIVE";
        } else {
            status = p != null ? "UNKNOWN" : "NOT_SPECIFIED";
            expires = null;
            unlimited = false;
        }
        return new LotModelFitResponse.Model(e.getId(), e.getName(), e.getManufact(), source, p == null ? null : p.getId(),
                p == null ? null : url(p.getSlug()), r == null ? null : r.getRegNumber(), status, expires, unlimited);
    }

    public LotProductMatchDto.Candidate candidate(FitCandidate c) {
        return new LotProductMatchDto.Candidate(c.productId(), c.name(), url(c.slug()), c.verdict() == null ? null : c.verdict().name(),
                c.met(), c.failed(), c.total(), c.unknown(), c.reason(), c.reasonShort(), c.regStatus(), c.regNumber(),
                c.rows() == null ? List.of() : c.rows().stream()
                        .map(x -> new LotProductMatchDto.Row(x.mark() == null ? null : x.mark().name(), x.text(), x.ours(), x.source()))
                        .toList());
    }

    /** Ссылка на товар сайта и РУ модели в ответе лота. */
    public TenderLotResponse enrich(TenderLotResponse r, TenderLot lot) {
        if (r != null && r.getProposedEquipment() != null && lot != null && lot.getProposedEquipment() != null) {
            LotModelFitResponse.Model m = model(lot.getProposedEquipment());
            r.getProposedEquipment().setProductUrl(m.url());
            r.getProposedEquipment().setRegActive("ACTIVE".equals(m.regStatus()));
            r.getProposedEquipment().setRegExpires(m.regExpires());
        }
        return r;
    }

    private String url(String slug) {
        return slug == null || slug.isBlank() ? null : WestmedLeadMapper.productUrl(siteUrl, slug);
    }
}
```

`LotModelReader`:
```java
package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.productwatch.CatalogSource;
import com.vladoose.nir.service.productwatch.ProductMatchService;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import com.vladoose.nir.service.productwatch.fit.ProductCards;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** Модель в работе для сверки — одним чтением (спека 2026-10-20-lot-model-in-work §6). */
@Service
public class LotModelReader {

    private final LotModelService lotModel;
    private final CatalogSource catalog;
    private final LotModelViews views;

    public LotModelReader(LotModelService lotModel, CatalogSource catalog, LotModelViews views) {
        this.lotModel = lotModel;
        this.catalog = catalog;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public ModelSnapshot read(Long lotId) {
        TenderLot lot = lotModel.lotInMarket(lotId);
        String text = ProductMatchService.lotFitText(lot);
        MedEquipment eq = lot.getProposedEquipment();
        if (eq == null) return new ModelSnapshot(text, null, false, null, null, null, null, null);
        ProductCard card;
        boolean needsDetail = false;
        String regNumber = eq.getRegistration() == null ? null : eq.getRegistration().getRegNumber();
        if (eq.getSiteProduct() != null) {
            card = catalog.get().card(eq.getSiteProduct().getId());
            if (card == null) card = ProductCards.of(eq.getSiteProduct(), null);   // снят с сайта или не в снимке
        } else if (eq.getRegistration() != null) {
            needsDetail = eq.getRegistration().getDetailFetchedAt() == null;
            card = ProductCards.ofRegistry(eq.getRegistration(), LocalDate.now(Market.KZ.zone()));
        } else {
            card = eq.getSpec() == null || eq.getSpec().isBlank() ? null : ProductCards.ofEquipment(eq);
        }
        return new ModelSnapshot(text, card, needsDetail, regNumber, views.model(eq), lot.getModelNote(), lot.getModelSetBy(),
                lot.getModelSetAt());
    }
}
```

`LotModelFit`:
```java
package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.service.RegistryDetailService;
import com.vladoose.nir.service.productwatch.fit.SpecFit;
import com.vladoose.nir.service.productwatch.fit.SpecRequirements;
import org.springframework.stereotype.Service;

/**
 * Сверка модели в работе с ТЗ лота (спека 2026-10-20-lot-model-in-work §6): ничего не хранится — по свежему тексту ТЗ и
 * карточке. Карточку РУ модели реестра, которую ещё не открывали, — один раз с ndda.kz, вне транзакции.
 */
@Service
public class LotModelFit {

    static final String MISSING_TEXT = "Карточка РУ недоступна — повторите позже";

    private final LotModelReader reader;
    private final RegistryDetailService details;
    private final LotModelViews views;

    public LotModelFit(LotModelReader reader, RegistryDetailService details, LotModelViews views) {
        this.reader = reader;
        this.details = details;
        this.views = views;
    }

    public LotModelFitResponse fit(Long lotId) {
        ModelSnapshot s = reader.read(lotId);
        if (s.model() == null) return new LotModelFitResponse(null, null, null, null, "NONE", null, null);
        if (s.needsDetail()) {
            if (!prefetchCard(s.regNumber())) {
                return new LotModelFitResponse(s.model(), s.note(), s.setBy(), s.setAt(), "MISSING", MISSING_TEXT, null);
            }
            s = reader.read(lotId);
        }
        if (s.card() == null) return new LotModelFitResponse(s.model(), s.note(), s.setBy(), s.setAt(), "NONE", null, null);
        FitCandidate c = SpecFit.compare(SpecRequirements.extract(s.text()), s.card());
        return new LotModelFitResponse(s.model(), s.note(), s.setBy(), s.setAt(), "OK", null, views.candidate(c));
    }

    /** Карточка РУ с ndda.kz (кеш — в реестре); сбой — false: закрепление и сверка не падают. */
    public boolean prefetchCard(String regNumber) {
        if (regNumber == null) return false;
        try {
            details.detail(regNumber);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Зелёный + мутации**

Run: та же команда, что в Step 2.
Expected: PASS (8 тестов). Мутации: `prefetchCard` без `catch` (`nddaDown_…` красный); без перечитывания `s = reader.read(lotId)` (`cardNotLoaded_…`); без запасного `ProductCards.of(...)` (`siteProduct_removedAfterPin_…`); `needsDetail` всегда `false` (`registryModel_…`).

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/lotmodel src/main/java/com/vladoose/nir/dto/response/LotModelFitResponse.java \
  src/test/java/com/vladoose/nir/lotmodel/LotModelFitTest.java src/test/java/com/vladoose/nir/lotmodel/LotModelReaderTest.java
git commit -m "feat(lot-model): сверка модели в работе — наш товар, реестр (карточка РУ), каталог

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: API — поиск, закрепить, заметка, снять, сверка; лоты и тендер

**Files:**
- Create: `src/main/java/com/vladoose/nir/controller/LotModelController.java`, `src/main/java/com/vladoose/nir/dto/request/LotModelRequest.java`, `src/main/java/com/vladoose/nir/dto/request/LotModelNoteRequest.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderController.java` (`getLots` — `views.enrich`; `withOurProduct` — `modelsInWork`), `src/main/java/com/vladoose/nir/dto/response/TenderResponse.java`, `src/main/java/com/vladoose/nir/repository/TenderLotRepository.java`
- Test: `src/test/java/com/vladoose/nir/lotmodel/LotModelApiTest.java`

**Interfaces:**
- Consumes: Tasks 3–5.
- Produces: `GET /api/lots/{id}/model-search?q=` → `LotModelSearchResponse`; `POST /api/lots/{id}/model {siteProductId | regNumber, note}` → `TenderLotResponse`; `PUT /api/lots/{id}/model-note {note}` → `TenderLotResponse`; `DELETE /api/lots/{id}/model` → `TenderLotResponse`; `GET /api/lots/{id}/model-fit` → `LotModelFitResponse`; `TenderResponse.modelsInWork` (`List<String>` — названия моделей лотов в порядке лотов); `TenderLotRepository.findModelsByTenderIds(Collection<Long>) → List<Object[]>` (`[tenderId, имя модели]`).

- [ ] **Step 1: Тесты**

```java
package com.vladoose.nir.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API «модели в работе» (спека §5.5, §6): права, рынок, ввод, сквозной путь. */
@SpringBootTest
@Transactional
class LotModelApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired MedRegistryRepository registry;
    MockMvc mvc;
    TenderLot lot;
    SiteProduct dki;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Дефибриллятор");
        // карточка РУ уже открыта — закрепление не ходит в ndda.kz
        registry.saveAndFlush(MedRegistry.builder().regNumber("ZZ-РУ-API-1").name("ZZ Дефибриллятор D3").producer("ZZ")
                .unlimited(true).detailFetchedAt(OffsetDateTime.now()).build());
        dki = products.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("ZZ Дефибриллятор ДКИ-Н-12")
                .brand("Аксион").slug("zz-dki").syncedAt(OffsetDateTime.now()).build());
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    private static MockHttpServletRequestBuilder in(Market market, MockHttpServletRequestBuilder b) {
        return b.header("X-Market", market.name());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void pinSiteProduct_thenLotsTenderFitAndClear() throws Exception {
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"),
                        "{\"siteProductId\":" + dki.getId() + ",\"note\":\"ИИ: подходит\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedEquipment.source").value("SITE"))
                .andExpect(jsonPath("$.proposedEquipment.productUrl").value(endsWith("zz-dki")))
                .andExpect(jsonPath("$.modelNote").value("ИИ: подходит"))
                .andExpect(jsonPath("$.modelSetBy").value("vlad"));
        mvc.perform(in(Market.KZ, get("/api/tenders/" + lot.getTender().getId() + "/lots")))
                .andExpect(jsonPath("$[0].proposedEquipment.siteProductId").value(dki.getId()))
                .andExpect(jsonPath("$[0].proposedEquipment.productUrl").value(endsWith("zz-dki")));
        mvc.perform(in(Market.KZ, get("/api/tenders/" + lot.getTender().getId())))
                .andExpect(jsonPath("$.modelsInWork[0]").value("ZZ Дефибриллятор ДКИ-Н-12"));
        mvc.perform(in(Market.KZ, get("/api/lots/" + lot.getId() + "/model-fit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.source").value("SITE"))
                .andExpect(jsonPath("$.note").value("ИИ: подходит"));
        mvc.perform(in(Market.KZ, json(put("/api/lots/" + lot.getId() + "/model-note"), "{\"note\":\"новая\"}")))
                .andExpect(jsonPath("$.modelNote").value("новая"));
        mvc.perform(in(Market.KZ, delete("/api/lots/" + lot.getId() + "/model")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedEquipment").doesNotExist());
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void pinRegistry_byNumber() throws Exception {
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"), "{\"regNumber\":\" ZZ-РУ-API-1 \"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedEquipment.source").value("REGISTRY"))
                .andExpect(jsonPath("$.proposedEquipment.regNumber").value("ZZ-РУ-API-1"))
                .andExpect(jsonPath("$.proposedEquipment.regActive").value(true));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operator_readsButCannotWrite() throws Exception {
        mvc.perform(in(Market.KZ, get("/api/lots/" + lot.getId() + "/model-search").param("q", "дки"))).andExpect(status().isOk());
        mvc.perform(in(Market.KZ, get("/api/lots/" + lot.getId() + "/model-fit"))).andExpect(status().isOk());
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"), "{\"regNumber\":\"ZZ-РУ-API-1\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(in(Market.KZ, json(put("/api/lots/" + lot.getId() + "/model-note"), "{\"note\":\"x\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(in(Market.KZ, delete("/api/lots/" + lot.getId() + "/model"))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void badInputAndOtherMarket() throws Exception {
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"),
                "{\"siteProductId\":" + dki.getId() + ",\"regNumber\":\"ZZ-РУ-API-1\"}"))).andExpect(status().isBadRequest());
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"), "{}"))).andExpect(status().isBadRequest());
        mvc.perform(in(Market.KZ, json(put("/api/lots/" + lot.getId() + "/model-note"), "{\"note\":\"x\"}")))
                .andExpect(status().isBadRequest());                                  // модели нет
        mvc.perform(in(Market.RF, get("/api/lots/" + lot.getId() + "/model-fit"))).andExpect(status().isNotFound());
        mvc.perform(in(Market.RF, get("/api/lots/" + lot.getId() + "/model-search").param("q", "дки"))).andExpect(status().isNotFound());
        mvc.perform(in(Market.RF, json(post("/api/lots/" + lot.getId() + "/model"), "{\"siteProductId\":" + dki.getId() + "}")))
                .andExpect(status().isNotFound());
        mvc.perform(in(Market.RF, delete("/api/lots/" + lot.getId() + "/model"))).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lotmodel.LotModelApiTest' > .superpowers/sdd/2026-10-20-lot-model-in-work/t6.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t6.log`
Expected: FAIL — 404 на новых путях (`/api/lots/{id}/model…`), нет `modelsInWork`.

- [ ] **Step 3: Реализация**

`LotModelRequest`:
```java
package com.vladoose.nir.dto.request;

import lombok.Data;

/** Закрепить модель в работе: ровно одно из двух — наш товар сайта или № РУ (спека §5.5). */
@Data
public class LotModelRequest {
    private Long siteProductId;
    private String regNumber;
    private String note;
}
```

`LotModelNoteRequest`:
```java
package com.vladoose.nir.dto.request;

import lombok.Data;

/** Заметка к модели в работе — «почему эта модель» (спека §5.5). */
@Data
public class LotModelNoteRequest {
    private String note;
}
```

`LotModelController`:
```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.LotModelNoteRequest;
import com.vladoose.nir.dto.request.LotModelRequest;
import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.dto.response.LotModelSearchResponse;
import com.vladoose.nir.dto.response.TenderLotResponse;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.TenderLotMapper;
import com.vladoose.nir.service.lotmodel.LotModelFit;
import com.vladoose.nir.service.lotmodel.LotModelSearch;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.service.lotmodel.LotModelViews;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** «Модель в работе» лота (спека 2026-10-20-lot-model-in-work §5.5, §6). */
@RestController
@RequestMapping("/api/lots")
public class LotModelController {

    private final LotModelService lotModel;
    private final LotModelSearch search;
    private final LotModelFit fit;
    private final LotModelViews views;
    private final TenderLotMapper mapper;

    public LotModelController(LotModelService lotModel, LotModelSearch search, LotModelFit fit, LotModelViews views, TenderLotMapper mapper) {
        this.lotModel = lotModel;
        this.search = search;
        this.fit = fit;
        this.views = views;
        this.mapper = mapper;
    }

    @GetMapping("/{id}/model-search")
    public LotModelSearchResponse search(@PathVariable Long id, @RequestParam(required = false) String q) {
        return search.search(id, q);
    }

    @PostMapping("/{id}/model")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse pin(@PathVariable Long id, @RequestBody LotModelRequest req) {
        boolean site = req.getSiteProductId() != null;
        boolean reg = req.getRegNumber() != null && !req.getRegNumber().isBlank();
        if (site == reg) throw new BadRequestException("Укажите либо товар сайта, либо № РУ");
        TenderLot lot = site ? lotModel.pinSiteProduct(id, req.getSiteProductId(), req.getNote())
                : lotModel.pinRegistry(id, req.getRegNumber().strip(), req.getNote());
        if (reg) fit.prefetchCard(req.getRegNumber().strip());       // сеть — после транзакции; сбой не мешает
        return views.enrich(mapper.toResponse(lot), lot);
    }

    @PutMapping("/{id}/model-note")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse note(@PathVariable Long id, @RequestBody LotModelNoteRequest req) {
        TenderLot lot = lotModel.setNote(id, req.getNote());
        return views.enrich(mapper.toResponse(lot), lot);
    }

    @DeleteMapping("/{id}/model")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse clear(@PathVariable Long id) {
        TenderLot lot = lotModel.clearModel(id);
        return views.enrich(mapper.toResponse(lot), lot);
    }

    @GetMapping("/{id}/model-fit")
    public LotModelFitResponse modelFit(@PathVariable Long id) {
        return fit.fit(id);
    }
}
```

`TenderLotRepository`:
```java
    /** Модели в работе лотов тендеров — одним запросом на список (`[tenderId, имя модели]`, порядок лотов). */
    @Query("SELECT l.tender.id, e.name FROM TenderLot l JOIN l.proposedEquipment e WHERE l.tender.id IN :ids ORDER BY l.id")
    List<Object[]> findModelsByTenderIds(@Param("ids") Collection<Long> ids);
```

`TenderResponse` (рядом с `ourProduct`):
```java
    private List<String> modelsInWork = new ArrayList<>();   // модели в работе лотов (спека 2026-10-20-lot-model-in-work §7)
```

`TenderController`: в конструктор — `TenderLotRepository tenderLotRepository`, `LotModelViews lotModelViews` (поля `private final`); `withOurProduct` — перед `return list;`:
```java
        Map<Long, List<String>> models = new HashMap<>();
        for (Object[] row : tenderLotRepository.findModelsByTenderIds(list.stream().map(TenderResponse::getId).toList())) {
            models.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
        for (TenderResponse r : list) r.setModelsInWork(models.getOrDefault(r.getId(), List.of()));
```
`getLots` — сущности держать рядом с ответами:
```java
        service.findInMarket(id);
        List<TenderLot> entities = tenderLotService.findByTenderId(id);
        List<TenderLotResponse> lots = tenderLotMapper.toResponseList(entities);
        Map<Long, TenderLot> byId = new HashMap<>();
        entities.forEach(e -> byId.put(e.getId(), e));
        for (TenderLotResponse l : lots) lotModelViews.enrich(l, byId.get(l.getId()));
```
(остальное тело `getLots` — без изменений).

- [ ] **Step 4: Зелёный + соседи + мутации**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lotmodel.*' --tests 'com.vladoose.nir.tender.*' --tests 'com.vladoose.nir.productwatch.TenderOurProductApiTest' > .superpowers/sdd/2026-10-20-lot-model-in-work/t6.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t6.log`
Expected: PASS. Мутации: без `@PreAuthorize` на POST (`operator_…`); `site == reg` → `site && reg` (`badInput…`: пустое тело проходит); без строки `setModelsInWork` (`pinSiteProduct_…`); без `enrich` в `getLots` (`pinSiteProduct_…`: нет `productUrl`).

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/controller/LotModelController.java \
  src/main/java/com/vladoose/nir/dto/request/LotModelRequest.java src/main/java/com/vladoose/nir/dto/request/LotModelNoteRequest.java \
  src/main/java/com/vladoose/nir/controller/TenderController.java src/main/java/com/vladoose/nir/dto/response/TenderResponse.java \
  src/main/java/com/vladoose/nir/repository/TenderLotRepository.java src/test/java/com/vladoose/nir/lotmodel/LotModelApiTest.java
git commit -m "feat(lot-model): API модели лота; ссылка на товар в лотах, модели в работе у тендера

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: «📋 Для ИИ» — модель в работе в тексте

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/productwatch/ProductAiBrief.java`
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductAiBriefTest.java` (новый тест)

**Interfaces:**
- Consumes: `LotModelReader.read` (Task 5), `SpecFit.compare`, `SpecRequirements.extract`.
- Produces: у лота с моделью в работе — строка «Модель в работе: <модель>[ (<производитель>)][, РУ <№>][; закрепил <кто> <ДД.ММ.ГГГГ>][; почему: <заметка>]» и «Сверка АИС модели в работе: ✓ N · ✗ K · ≈ D · нет данных U»; лот с моделью без отметки «наш товар» — тоже в тексте; в запросе — «Если у лота указана «Модель в работе» — проверь её первой.»

- [ ] **Step 1: Тест** (в `ProductAiBriefTest`; новые поля — `@Autowired LotModelService lotModel; @Autowired MedEquipmentRepository equipment;`)

```java
    /** «Модель в работе» (спека 2026-10-20-lot-model-in-work §7): строка, заметка и сверка; лот без отметки, но с моделью — тоже. */
    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void modelInWork_lineNoteAndFit_evenWithoutOurMark() {
        Tender t = ourTender("17000777-1", ProductFitEvaluateTest.ASPIRATOR_SPEC, 1);
        TenderLot first = t.getLots().get(0);
        MedEquipment eq = equipment.save(MedEquipment.builder().name("ZZ Отсасыватель").manufact("ZZ Завод").market(Market.KZ)
                .spec("Производительность по воздуху не менее 20 л/мин").build());
        lotModel.apply(first, eq, "ИИ: подходит по 3 из 3");
        TenderLot other = new TenderLot();
        other.setTender(t);
        other.setLotNumber(2);
        other.setEquipName("ZZ Шкаф");
        t.getLots().add(other);
        tenders.save(t);
        em.flush();
        lotModel.apply(other, equipment.save(MedEquipment.builder().name("ZZ Шкаф медицинский").manufact("не указан")
                .market(Market.KZ).build()), null);
        em.flush();
        String text = brief.forTender(t.getId());
        assertThat(text).contains("Если у лота указана «Модель в работе» — проверь её первой.")
                .contains("Модель в работе: ZZ Отсасыватель (ZZ Завод); закрепил vlad")
                .contains("почему: ИИ: подходит по 3 из 3")
                .contains("Сверка АИС модели в работе: ✓ ")
                .contains("--- Лот № 2: ZZ Шкаф")
                .contains("Модель в работе: ZZ Шкаф медицинский; закрепил vlad");
    }
```
(импорты: `MedEquipment`, `MedEquipmentRepository`, `LotModelService`, `WithMockUser`, если их нет.)

- [ ] **Step 2: Красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.productwatch.ProductAiBriefTest' > .superpowers/sdd/2026-10-20-lot-model-in-work/t7.log 2>&1; tail -30 .superpowers/sdd/2026-10-20-lot-model-in-work/t7.log`
Expected: FAIL — в тексте нет «Модель в работе».

- [ ] **Step 3: Реализация**

В `PROMPT` после строки «Ничего не выдумывай: нет данных — так и пиши.» — строка:
```
            Если у лота указана «Модель в работе» — проверь её первой.
```
Поле и параметр конструктора `LotModelReader modelReader`. В `block`:
```java
        for (TenderLot l : t.getLots()) {
            List<TenderLotProductMatch> ms = byLot.get(l.getId());
            if (ms == null && l.getProposedEquipment() == null) continue;
            lot(b, l, ms == null ? List.of() : ms, specChars);
        }
```
В `lot` после строки лота (`b.append('\n');` перед циклом «Сверка АИС»):
```java
        modelLine(b, l);
```
новый метод:
```java
    /** Модель в работе (спека 2026-10-20-lot-model-in-work §7): что закреплено, кем и почему, и её сверка — ИИ проверит её первой. */
    private void modelLine(StringBuilder b, TenderLot l) {
        MedEquipment model = l.getProposedEquipment();
        if (model == null) return;
        b.append("Модель в работе: ").append(one(model.getName()));
        if (model.getManufact() != null && !"не указан".equals(model.getManufact())) b.append(" (").append(one(model.getManufact())).append(')');
        if (model.getRegistration() != null) b.append(", РУ ").append(model.getRegistration().getRegNumber());
        if (l.getModelSetBy() != null) {
            b.append("; закрепил ").append(l.getModelSetBy());
            if (l.getModelSetAt() != null) b.append(' ').append(l.getModelSetAt().atZoneSameInstant(Market.KZ.zone()).format(DATE));
        }
        if (l.getModelNote() != null) b.append("; почему: ").append(one(l.getModelNote()));
        b.append('\n');
        try {
            ModelSnapshot s = modelReader.read(l.getId());
            if (s.card() != null && !s.needsDetail()) {
                FitCandidate c = SpecFit.compare(SpecRequirements.extract(s.text()), s.card());
                long diff = c.rows().stream().filter(r -> r.mark() == FitMark.DIFF).count();
                b.append("Сверка АИС модели в работе: ✓ ").append(c.met()).append(" · ✗ ").append(c.failed())
                        .append(" · ≈ ").append(diff).append(" · нет данных ").append(c.unknown()).append('\n');
            }
        } catch (RuntimeException ignored) {
            // сверка — подсказка: без неё текст полезен (в «Для ИИ» ndda.kz не зовём)
        }
    }
```

- [ ] **Step 4: Зелёный + мутация**

Run: та же команда, что в Step 2.
Expected: PASS (весь класс). Мутация: в `block` вернуть `if (ms == null) continue;` — новый тест красный («Лот № 2»).

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/productwatch/ProductAiBrief.java \
  src/test/java/com/vladoose/nir/productwatch/ProductAiBriefTest.java
git commit -m "feat(lot-model): «Для ИИ» — модель в работе, заметка и её сверка

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Экран — шапка лота, окно «📌 Модель…», сверка модели, чип и фильтр

**Files:**
- Create: `frontend/src/app/shared/lot-model.ts`, `frontend/src/app/pages/tenders/lot-model-dialog.component.ts`, `frontend/src/app/pages/tenders/lot-model-fit.component.ts`
- Modify: `frontend/src/app/services/api.service.ts`, `frontend/src/app/pages/tenders/lot-fit.component.ts`, `frontend/src/app/pages/tenders/tender-lots.component.ts`, `frontend/src/app/pages/tenders/tenders.component.ts`, `frontend/src/app/pages/tenders/tenders-filters.component.ts`, `frontend/src/app/shared/product-watch.ts`

**Interfaces:**
- Consumes: Task 6 API.
- Produces: `ApiService.searchLotModels / pinLotModel / setLotModelNote / clearLotModel / getLotModelFit`; `LotFitComponent` — входы `title`, `candidates`, `canPin`, `pinnedProductId`, выход `pin`; `app-lot-model-fit [lotId] [modelKey] [canEdit] (changed)`; `app-lot-model-dialog [lot] (pinned) (closed)`; `OUR_LABELS.PINNED`.

- [ ] **Step 1: Типы и API**

`shared/lot-model.ts`:
```ts
import type { FitCandidateView } from './product-watch';

/** «Модель в работе» лота (спека 2026-10-20-lot-model-in-work). */
export type ModelSource = 'SITE' | 'REGISTRY' | 'CATALOG';
export interface OurModelHit {
  productId: number; name: string; brand: string | null; sku: string | null; regNumber: string | null;
  regStatus: string; regExpires: string | null; url: string | null;
}
export interface RegistryModelHit {
  regNumber: string; name: string; producer: string | null; country: string | null; expires: string | null;
  unlimited: boolean; expired: boolean;
}
export interface LotModelSearch { ours: OurModelHit[]; registry: RegistryModelHit[]; }
export interface LotModelInfo {
  equipmentId: number; name: string; manufact: string | null; source: ModelSource; siteProductId: number | null;
  url: string | null; regNumber: string | null; regStatus: string; regExpires: string | null; regUnlimited: boolean;
}
export interface LotModelFit {
  model: LotModelInfo | null; note: string | null; setBy: string | null; setAt: string | null;
  card: 'OK' | 'MISSING' | 'NONE'; error: string | null; fit: FitCandidateView | null;
}

export const MODEL_SOURCE: Record<ModelSource, string> = { SITE: 'наш товар', REGISTRY: 'реестр', CATALOG: 'каталог' };

/** «2026-02-01» → «01.02.2026» — строкой, без new Date (пояс браузера сдвинул бы день). */
export function isoDate(s: string | null | undefined): string {
  return s ? s.slice(0, 10).split('-').reverse().join('.') : '';
}

/** «РУ ✓», «РУ истекло 01.02.2026», «РУ не найдено», «РУ не указано», «РУ не проверено». */
export function regShort(status: string | null | undefined, expires: string | null | undefined): string {
  if (status === 'ACTIVE') return 'РУ ✓';
  if (status === 'EXPIRED') return 'РУ истекло' + (expires ? ' ' + isoDate(expires) : '');
  if (status === 'NOT_FOUND') return 'РУ не найдено';
  if (status === 'NOT_SPECIFIED') return 'РУ не указано';
  return 'РУ не проверено';
}
```

`ApiService` (рядом с `clearProposedEquipment`; вверху — `import type { LotModelFit, LotModelSearch } from '../shared/lot-model';`):
```ts
  // ===== «Модель в работе» лота (спека 2026-10-20-lot-model-in-work) =====
  searchLotModels(lotId: number, q: string): Observable<LotModelSearch> {
    return this.http.get<LotModelSearch>(`${this.base}/lots/${lotId}/model-search`, { params: { q } });
  }
  pinLotModel(lotId: number, body: { siteProductId?: number; regNumber?: string; note?: string | null }): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/model`, body);
  }
  setLotModelNote(lotId: number, note: string | null): Observable<any> {
    return this.http.put<any>(`${this.base}/lots/${lotId}/model-note`, { note });
  }
  clearLotModel(lotId: number): Observable<any> {
    return this.http.delete<any>(`${this.base}/lots/${lotId}/model`);
  }
  getLotModelFit(lotId: number): Observable<LotModelFit> {
    return this.http.get<LotModelFit>(`${this.base}/lots/${lotId}/model-fit`);
  }
```

- [ ] **Step 2: `lot-fit` — заголовок, свои кандидаты, «📌 Закрепить»**

Входы и выход (рядом с `@Input() requirements`; в импорт `@angular/core` — `EventEmitter`, `Output`):
```ts
  @Input() title = 'Сверка с нашим товаром';
  /** Свои кандидаты — сверка модели в работе (без отметки «наш товар»). */
  @Input() candidates: FitCandidateView[] | null = null;
  /** Админ на KZ: у нашего товара — «📌 Закрепить». */
  @Input() canPin = false;
  /** Наш товар, уже закреплённый за лотом, — вместо кнопки метка «📌 в работе». */
  @Input() pinnedProductId: number | null = null;
  @Output() pin = new EventEmitter<number>();

  get list(): FitCandidateView[] { return this.candidates ?? this.match?.candidates ?? []; }
```
`get current()` → `return this.list[this.index] ?? null;`. Шаблон первой секции:
```html
    <section class="lf" *ngIf="list.length && current as c">
      <div class="lf-head">
        <span class="lf-title">{{ title }}</span>
        <span class="lf-foreign" *ngIf="match?.foreignBrand">ТЗ под {{ match!.foreignBrand }}</span>
      </div>
      <div class="lf-product">
        <a *ngIf="c.url; else plainName" [href]="c.url" target="_blank" rel="noopener">{{ c.name }} ↗</a>
        <ng-template #plainName><span>{{ c.name }}</span></ng-template>
        <span class="lf-reg" [ngClass]="c.regStatus === 'ACTIVE' ? 'lf-reg-ok' : 'lf-reg-bad'">{{ regText(c) }}</span>
        <button type="button" class="lf-pin" *ngIf="canPin && c.productId && c.productId !== pinnedProductId"
                (click)="pin.emit(c.productId)" title="Закрепить этот товар моделью в работе лота">📌 Закрепить</button>
        <span class="lf-pinned" *ngIf="c.productId && c.productId === pinnedProductId">📌 в работе</span>
      </div>
      <div class="lf-count">✓ {{ c.met }} · ✗ {{ c.failed }} · ≈ {{ diff(c) }} · нет данных {{ c.unknown }}</div>
      <div class="lf-alts" *ngIf="list.length > 1">
        <span class="lf-alts-label">ещё:</span>
        <ng-container *ngFor="let a of list; let i = index">
          <button type="button" class="lf-alt" *ngIf="i !== index" (click)="index = i"
                  [title]="'Сравнить ТЗ с ' + a.name">{{ short(a.name) }}{{ i === 0 ? ' (лучший)' : '' }}</button>
        </ng-container>
      </div>
      <!-- список строк <ol class="lf-rows"> — без изменений -->
    </section>
    <section class="lf" *ngIf="!match && !candidates && requirements?.length"> <!-- содержимое — без изменений --> </section>
```
Стили (рядом с `.lf-alt`):
```css
    .lf-pin { font-size: 12px; padding: 2px 10px; border-radius: 999px; border: 1px solid var(--accent); background: var(--surface);
      color: var(--accent); cursor: pointer; }
    .lf-pinned { font-size: 12px; padding: 1px 8px; border-radius: 999px; color: var(--accent);
      background: color-mix(in srgb, var(--accent) 15%, transparent); }
```
и в `@media (max-width: 900px)` — `.lf-alt, .lf-pin { min-height: 40px; }`.

- [ ] **Step 3: `lot-model-fit`**

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output, inject } from '@angular/core';
import { NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { LotFitComponent } from './lot-fit.component';
import { errorText } from '../../shared/error-text';
import type { FitCandidateView } from '../../shared/product-watch';
import type { LotModelFit } from '../../shared/lot-model';

/** «Сверка с моделью в работе» в раскрытом лоте (спека 2026-10-20-lot-model-in-work §6, §7); заметка — у админа. */
@Component({
  selector: 'app-lot-model-fit',
  standalone: true,
  imports: [NgIf, FormsModule, LotFitComponent],
  template: `
    <ng-container *ngIf="data?.model">
      <app-lot-fit *ngIf="fitList.length" [title]="'Сверка с моделью в работе'" [candidates]="fitList"></app-lot-fit>
      <div class="lm-box" *ngIf="!fitList.length">
        <span class="lm-title">Сверка с моделью в работе</span>
        <span *ngIf="data!.card === 'MISSING'">{{ data!.error }}
          <button type="button" class="lm-link" (click)="load()">Повторить</button></span>
        <span *ngIf="data!.card === 'NONE'">Характеристик модели нет — сверять не с чем</span>
      </div>
      <div class="lm-note" *ngIf="canEdit || data!.note || data!.setBy">
        <ng-container *ngIf="!editing">
          <span *ngIf="data!.note" class="lm-note-text">Почему: {{ data!.note }}</span>
          <span *ngIf="data!.setBy" class="lm-who">закрепил {{ data!.setBy }}{{ data!.setAt ? ', ' + when(data!.setAt) : '' }}</span>
          <button *ngIf="canEdit" type="button" class="lm-link" (click)="startEdit()">✎ Заметка</button>
        </ng-container>
        <ng-container *ngIf="editing">
          <textarea [(ngModel)]="draft" maxlength="1000" rows="3" placeholder="Почему эта модель — можно вставить вывод ИИ"></textarea>
          <div class="lm-actions">
            <button type="button" class="btn btn-line" (click)="editing = false" [disabled]="saving">Отмена</button>
            <button type="button" class="btn btn-primary" (click)="saveNote()" [disabled]="saving">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
          </div>
        </ng-container>
      </div>
    </ng-container>
    <div class="lm-box" *ngIf="loading && !data">Сверяю модель в работе…</div>
  `,
  styles: [`
    .lm-box { margin: 8px 0; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface);
      display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; color: var(--text-muted); font-size: 13px; }
    .lm-title { font-weight: 600; color: var(--text); }
    .lm-link { border: none; background: none; color: var(--accent); cursor: pointer; padding: 0; font-size: 13px; }
    .lm-note { margin: 4px 0 8px; display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; font-size: 13px; }
    .lm-note-text { color: var(--text); white-space: pre-line; overflow-wrap: anywhere; }
    .lm-who { color: var(--text-muted); }
    .lm-note textarea { width: 100%; box-sizing: border-box; font: inherit; padding: 6px 8px; border: 1px solid var(--border);
      border-radius: 6px; background: var(--surface); color: var(--text); }
    .lm-actions { display: flex; gap: 8px; justify-content: flex-end; width: 100%; }
    @media (max-width: 900px) {
      .lm-link { min-height: 40px; }
      .lm-note textarea { font-size: 16px; }
    }
  `],
})
export class LotModelFitComponent implements OnChanges {
  @Input({ required: true }) lotId!: number;
  /** Меняется при смене модели или заметки — перечитать сверку. */
  @Input() modelKey = '';
  @Input() canEdit = false;
  @Output() changed = new EventEmitter<void>();

  data: LotModelFit | null = null;
  fitList: FitCandidateView[] = [];
  loading = false;
  editing = false;
  saving = false;
  draft = '';
  private seq = 0;
  private destroyRef = inject(DestroyRef);

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnChanges(): void { this.load(); }

  load() {
    const seq = ++this.seq;
    this.loading = true;
    this.api.getLotModelFit(this.lotId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: d => {
        if (seq !== this.seq) return;
        this.data = d;
        this.fitList = d.card === 'OK' && d.fit ? [d.fit] : [];
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: e => {
        if (seq !== this.seq) return;
        this.loading = false;
        this.notify.error('Сверка модели не загрузилась: ' + errorText(e));
        this.cdr.markForCheck();
      },
    });
  }

  startEdit() { this.draft = this.data?.note ?? ''; this.editing = true; }

  saveNote() {
    this.saving = true;
    this.api.setLotModelNote(this.lotId, this.draft.trim() || null).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.saving = false; this.editing = false; this.notify.success('Заметка сохранена'); this.changed.emit(); },
      error: e => { this.saving = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  /** Дата и время — по Уральску (модель в работе — только KZ). */
  when(iso: string): string {
    return new Date(iso).toLocaleString('ru-RU', { timeZone: 'Asia/Oral', day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit' });
  }
}
```

- [ ] **Step 4: `lot-model-dialog`**

```ts
import { AfterViewInit, ChangeDetectorRef, Component, DestroyRef, ElementRef, EventEmitter, HostListener, Input, Output, ViewChild,
  inject } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { errorText } from '../../shared/error-text';
import { isoDate, regShort } from '../../shared/lot-model';
import type { LotModelSearch, OurModelHit, RegistryModelHit } from '../../shared/lot-model';

interface Picked { kind: 'our' | 'reg'; key: number | string; name: string; }

/** Окно «📌 Модель в работе» (спека 2026-10-20-lot-model-in-work §7): наши товары и реестр НЦЭЛС одним поиском. */
@Component({
  selector: 'app-lot-model-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="dismiss()">
      <div class="modal" role="dialog" aria-label="Модель в работе" (click)="$event.stopPropagation()">
        <h3>📌 Модель в работе — {{ lot.equipName }}</h3>
        <input #qInput class="q" type="search" [(ngModel)]="q" (ngModelChange)="query$.next($event)"
               placeholder="Название, бренд, модель или № РУ" aria-label="Поиск модели">
        <p class="hint" *ngIf="q.trim().length < 2">Вставьте модель из ответа ИИ — найдём её среди наших товаров и в реестре НЦЭЛС.</p>
        <p class="hint" *ngIf="searching">Ищу…</p>
        <ng-container *ngIf="result as r">
          <section *ngIf="r.ours.length">
            <h4>Наши товары (westmed.kz)</h4>
            <button type="button" class="hit" *ngFor="let o of r.ours" [class.on]="isPicked('our', o.productId)" (click)="pickOur(o)">
              <span class="h-name">{{ o.name }}</span>
              <span class="h-meta">{{ o.brand || '' }}{{ o.sku ? ' · ' + o.sku : '' }} ·
                <span [class.bad]="o.regStatus !== 'ACTIVE'">{{ regShort(o.regStatus, o.regExpires) }}</span></span>
            </button>
          </section>
          <section *ngIf="r.registry.length">
            <h4>Реестр НЦЭЛС</h4>
            <button type="button" class="hit" *ngFor="let g of r.registry" [class.on]="isPicked('reg', g.regNumber)" (click)="pickReg(g)">
              <span class="h-name">{{ g.name }}</span>
              <span class="h-meta">{{ g.producer || '' }}{{ g.country ? ', ' + g.country : '' }} · РУ {{ g.regNumber }} ·
                <span [class.bad]="g.expired">{{ regTerm(g) }}</span></span>
            </button>
          </section>
          <p class="hint" *ngIf="!searching && !r.ours.length && !r.registry.length">Ничего не нашлось — уточните запрос.</p>
        </ng-container>
        <label class="note" *ngIf="picked">Почему эта модель (можно вставить вывод ИИ)
          <textarea [(ngModel)]="note" maxlength="1000" rows="3"></textarea>
        </label>
        <p class="replace" *ngIf="picked && lot.proposedEquipment">Заменит «{{ lot.proposedEquipment.name }}».</p>
        <div class="error-banner" *ngIf="error">{{ error }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-line" (click)="dismiss()" [disabled]="busy">Отмена</button>
          <button type="button" class="btn btn-primary" (click)="pin()" [disabled]="!picked || busy">{{ busy ? 'Закрепляю…' : '📌 Закрепить' }}</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 680px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 10px; }
    h3 { margin: 0; overflow-wrap: anywhere; }
    h4 { margin: 6px 0 4px; font-size: 13px; color: var(--text-muted); font-weight: 600; }
    .q { padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    section { display: flex; flex-direction: column; gap: 6px; }
    .hit { text-align: left; border: 1px solid var(--border); border-radius: 10px; padding: 8px 10px; background: var(--surface);
           color: var(--text); cursor: pointer; display: flex; flex-direction: column; gap: 2px; }
    .hit.on { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-color: var(--accent); }
    .h-name { font-weight: 600; overflow-wrap: anywhere; }
    .h-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .h-meta .bad { color: var(--warn-text); }
    .note { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
    .note textarea { font: inherit; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); }
    .replace { margin: 0; font-size: 13px; color: var(--warn-text); overflow-wrap: anywhere; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; margin-top: 0; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .q, .note textarea { font-size: 16px; }
      .hit, .form-actions .btn { min-height: 40px; }
    }
  `],
})
export class LotModelDialogComponent implements AfterViewInit {
  @Input({ required: true }) lot!: any;
  @Output() pinned = new EventEmitter<void>();
  @Output() closed = new EventEmitter<void>();
  @ViewChild('qInput') qInput?: ElementRef<HTMLInputElement>;

  q = '';
  note = '';
  result: LotModelSearch | null = null;
  searching = false;
  picked: Picked | null = null;
  busy = false;
  error = '';
  readonly query$ = new Subject<string>();
  readonly regShort = regShort;
  private destroyRef = inject(DestroyRef);

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {
    this.query$.pipe(
      debounceTime(300),
      map(s => s.trim()),
      distinctUntilChanged(),
      switchMap(s => {
        if (s.length < 2) return of(null);
        this.searching = true;
        this.error = '';
        this.cdr.markForCheck();
        return this.api.searchLotModels(this.lot.id, s).pipe(catchError(e => {
          this.error = 'Поиск не удался: ' + errorText(e);
          return of(null);
        }));
      }),
      takeUntilDestroyed(),
    ).subscribe(r => {
      this.searching = false;
      this.result = r;
      this.cdr.detectChanges();
    });
  }

  ngAfterViewInit(): void { this.qInput?.nativeElement.focus(); }

  isPicked(kind: 'our' | 'reg', key: number | string): boolean { return this.picked?.kind === kind && this.picked.key === key; }
  pickOur(o: OurModelHit) { this.picked = { kind: 'our', key: o.productId, name: o.name }; }
  pickReg(g: RegistryModelHit) { this.picked = { kind: 'reg', key: g.regNumber, name: g.name }; }

  regTerm(g: RegistryModelHit): string {
    if (g.unlimited) return 'бессрочно';
    if (g.expired) return 'истекло ' + isoDate(g.expires);
    return g.expires ? 'до ' + isoDate(g.expires) : 'срок не указан';
  }

  pin() {
    if (!this.picked || this.busy) return;
    this.busy = true;
    this.error = '';
    const note = this.note.trim() || null;
    const body = this.picked.kind === 'our' ? { siteProductId: this.picked.key as number, note } : { regNumber: this.picked.key as string, note };
    this.api.pinLotModel(this.lot.id, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy = false; this.pinned.emit(); },
      error: e => { this.busy = false; this.error = errorText(e); this.cdr.detectChanges(); },
    });
  }

  /** Закрыть (фон, Esc, «Отмена»), но не во время закрепления. */
  @HostListener('document:keydown.escape')
  dismiss() {
    if (!this.busy) this.closed.emit();
  }
}
```

- [ ] **Step 5: `tender-lots` — шапка, кнопка, блок сверки, окно**

Конструктор — ещё `public auth: AuthService` (`import { AuthService } from '../../services/auth.service';`); `imports` компонента — ещё `LotModelFitComponent`, `LotModelDialogComponent`; импорт `MODEL_SOURCE`, `regShort` из `../../shared/lot-model`.

Блок `<div class="lot-proposed" …>` заменить:
```html
          <div class="lot-model" *ngIf="l.proposedEquipment">
            <span class="badge-model">📌 Модель в работе:</span>
            <a *ngIf="l.proposedEquipment.productUrl; else modelName" [href]="l.proposedEquipment.productUrl" target="_blank"
               rel="noopener" (click)="$event.stopPropagation()">{{ l.proposedEquipment.name }} ↗</a>
            <ng-template #modelName><span>{{ l.proposedEquipment.name }}</span></ng-template>
            <span *ngIf="l.proposedEquipment.manufact && l.proposedEquipment.manufact !== 'не указан'"> ({{ l.proposedEquipment.manufact }})</span>
            <span class="model-src">{{ modelSource(l) }}</span>
            <span class="model-reg" [class.model-reg-bad]="!l.proposedEquipment.regActive"
                  [title]="l.proposedEquipment.regNumber ? 'РУ ' + l.proposedEquipment.regNumber : ''">{{ modelReg(l) }}</span>
            <button class="x-mini" *ngIf="auth.isAdmin()" (click)="clearModel(l, $event)" title="Снять модель в работе">✕</button>
            <div class="model-note" *ngIf="l.modelNote">{{ l.modelNote }}</div>
          </div>
```
В ряд действий лота (рядом с кнопкой «Подбор»):
```html
          <button class="btn btn-model" *ngIf="canModel" (click)="openModel(l)">📌 Модель…</button>
```
В `@defer (on viewport)` блока сверки — первой строкой и с закреплением у кандидатов:
```html
          <app-lot-model-fit *ngIf="l.proposedEquipment" [lotId]="l.id" [canEdit]="auth.isAdmin()" (changed)="lotsChanged.emit()"
            [modelKey]="l.proposedEquipment.id + '|' + (l.modelSetAt || '') + '|' + (l.modelNote || '')"></app-lot-model-fit>
          <app-lot-fit *ngFor="let m of fitMatches(l)" [match]="m" [canPin]="canModel" (pin)="pinOur(l, $event)"
            [pinnedProductId]="l.proposedEquipment?.siteProductId ?? null"></app-lot-fit>
```
После списка лотов (в конце шаблона):
```html
    @defer (when modelLot) {
      <app-lot-model-dialog *ngIf="modelLot" [lot]="modelLot" (pinned)="onModelPinned()" (closed)="modelLot = null"></app-lot-model-dialog>
    }
```
Стили: правило `.lot-proposed { … }` заменить:
```css
    .lot-model { margin-top: 5px; font-size: 12px; color: var(--text); display: flex; flex-wrap: wrap; gap: 4px 6px; align-items: baseline;
      overflow-wrap: anywhere; }
    .lot-model a { color: var(--accent); }
    .badge-model { font-weight: 600; color: var(--accent); }
    .model-src { color: var(--text-muted); }
    .model-reg { padding: 0 8px; border-radius: 999px; color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .model-reg.model-reg-bad { color: var(--warn-text); background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .model-note { flex-basis: 100%; color: var(--text-muted); white-space: pre-line; }
```
(если `.badge-proposed` больше нигде не используется — удалить его правило.)

Методы (старый `clearProposed` удалить; подпись стадии `'Модель выбрана'` → `'Модель в работе'`):
```ts
  modelLot: any = null;

  /** Закреплять наш товар или запись реестра — админ на KZ (спека §5.1). */
  get canModel(): boolean { return this.auth.isAdmin() && this.isKz(); }

  modelSource(l: any): string { return MODEL_SOURCE[l.proposedEquipment?.source as 'SITE'] ?? 'каталог'; }

  /** «РУ ✓» / «РУ истекло ДД.ММ.ГГГГ» / «РУ не указано» — по ответу лота (точный статус товара сайта — в сверке). */
  modelReg(l: any): string {
    const p = l.proposedEquipment;
    if (!p) return '';
    if (p.regActive) return 'РУ ✓';
    return p.regNumber ? regShort('EXPIRED', p.regExpires) : 'РУ не указано';
  }

  openModel(l: any) { this.modelLot = l; this.cdr.detectChanges(); }

  onModelPinned() {
    this.modelLot = null;
    this.notify.success('Модель в работе закреплена');
    this.lotsChanged.emit();
  }

  pinOur(l: any, productId: number) {
    const go = () => this.api.pinLotModel(l.id, { siteProductId: productId }).subscribe({
      next: () => { this.notify.success('Модель в работе закреплена'); this.lotsChanged.emit(); },
      error: (e: any) => this.notify.error(e.error?.message || 'Не удалось закрепить модель'),
    });
    if (!l.proposedEquipment) { go(); return; }
    this.confirm.ask('Заменить модель в работе?', '«' + l.proposedEquipment.name + '» перестанет быть моделью лота.',
      { confirmLabel: 'Заменить' }).subscribe(ok => { if (ok) go(); });
  }

  clearModel(l: any, ev: Event) {
    ev.stopPropagation();
    this.confirm.ask('Снять модель в работе?', '«' + l.proposedEquipment?.name + '» больше не будет моделью лота.',
      { confirmLabel: 'Снять' }).subscribe(ok => {
      if (!ok) return;
      this.api.clearLotModel(l.id).subscribe({
        next: () => { this.notify.success('Модель снята'); this.lotsChanged.emit(); },
        error: (e: any) => this.notify.error(e.error?.message || 'Ошибка'),
      });
    });
  }
```

- [ ] **Step 6: Список тендеров — чип и фильтр**

`shared/product-watch.ts`: в `OUR_LABELS` — ещё `PINNED: '📌 С моделью в работе'`.

`tenders-filters.component.ts` — в обоих селектах «Наш товар» после варианта `EXACT`:
```html
<option value="PINNED">{{ ourLabels['PINNED'] }}</option>
```

`tenders.component.ts`: чип — после `pw-chip` в карточке списка:
```html
            <span *ngIf="t.modelsInWork?.length" class="pw-chip" [title]="'Модель в работе: ' + t.modelsInWork.join(', ')">📌 {{ modelsChip(t.modelsInWork) }}</span>
```
в `applyTendersFilter` строку `if (!ourMatches(t.ourProduct, this.filterOur)) return false;` заменить:
```ts
      if (this.filterOur === 'PINNED') { if (!t.modelsInWork?.length) return false; }
      else if (!ourMatches(t.ourProduct, this.filterOur)) return false;
```
в `aiIds` фильтр `.filter(t => t.ourProduct && !deadlinePassed(t, market))` →
```ts
      .filter(t => (this.filterOur === 'PINNED' ? t.modelsInWork?.length : t.ourProduct) && !deadlinePassed(t, market))
```
метод (`shortProduct` — импорт из `../../shared/product-watch` рядом с `ourMatches`):
```ts
  /** Чип «📌 …» карточки: одна модель — её короткое имя, несколько — число. */
  modelsChip(models: string[]): string {
    if (models.length === 1) return shortProduct(models[0]);
    const n = models.length, d = n % 10, h = n % 100;
    return n + ' ' + (d === 1 && h !== 11 ? 'модель' : d >= 2 && d <= 4 && (h < 12 || h > 14) ? 'модели' : 'моделей');
  }
```

- [ ] **Step 7: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build > ../.superpowers/sdd/2026-10-20-lot-model-in-work/t8.log 2>&1; tail -25 ../.superpowers/sdd/2026-10-20-lot-model-in-work/t8.log; cd /Users/vlad/IdeaProjects/AIS`
Expected: сборка без ошибок; «Initial total» ≤ 1,5 МБ (было 1,43); окно и сверка модели — в ленивых чанках (`grep -l 'Модель в работе —' frontend/dist/*/browser/*.js` — не `main-*.js`).

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/shared/lot-model.ts frontend/src/app/pages/tenders/lot-model-dialog.component.ts \
  frontend/src/app/pages/tenders/lot-model-fit.component.ts frontend/src/app/services/api.service.ts \
  frontend/src/app/pages/tenders/lot-fit.component.ts frontend/src/app/pages/tenders/tender-lots.component.ts \
  frontend/src/app/pages/tenders/tenders.component.ts frontend/src/app/pages/tenders/tenders-filters.component.ts \
  frontend/src/app/shared/product-watch.ts
git commit -m "feat(lot-model): экран — модель в работе у лота, окно поиска, сверка модели, чип и фильтр

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Живая проверка, документация, ревью ветки, мерж

**Files:**
- Modify: `CLAUDE.md` (§7 — V40; §8 — блок «Модель в работе»; §12 — фронт; §13 — число тестов; §15 — API; §16 — ограничения и мелочи), `docs/PROGRESS.md`, `DEPLOY.md` (§11 — откат V40)

- [ ] **Step 1: Полный прогон**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill; ./gradlew cleanTest test > .superpowers/sdd/2026-10-20-lot-model-in-work/full.log 2>&1; tail -20 .superpowers/sdd/2026-10-20-lot-model-in-work/full.log`
Expected: BUILD SUCCESSFUL, 0 падений (число тестов — в CLAUDE.md §13).

- [ ] **Step 2: Живая проверка** (бэкенд `JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun`, `cd frontend && npm start`, Playwright; 1280 / 390, обе темы, оператор, РФ). До проверки записать: `select max(id) from med_equipment; select max(id) from deal_event;` и лоты, которым ставим модель (`select id, proposed_equipment_id, model_note from tender_lot where id in (…)`).
  1. KZ-тендер с разобранным ТЗ → лот → «📌 Модель…» → поиск «ДКИ» → наш товар → заметка → «📌 Закрепить» → шапка «📌 Модель в работе: … ↗ · наш товар · РУ …», заметка; в раскрытом лоте — «Сверка с моделью в работе», «✎ Заметка».
  2. «📌 Закрепить» у кандидата в «Сверке с нашим товаром» → «Заменить модель в работе?» → модель сменилась, заметка очищена, у кандидата — «📌 в работе».
  3. Модель реестра по № РУ → сверка по карточке РУ (первое открытие — ndda.kz; сбой — «Карточка РУ недоступна — повторите позже» с «Повторить»).
  4. «КП» по лоту → предпросмотр письма: «— <модель> (<бренд>), РУ № … — N шт.».
  5. «История» тендера — «Лот «…»: модель в работе — …» и «модель снята» с автором.
  6. Список тендеров — чип «📌 …», фильтр «📌 С моделью в работе», «📋 Для ИИ» по нему.
  7. «📋 Для ИИ» в карточке — строка модели, заметка, сверка.
  8. Оператор — нет «📌 Модель…», «✕», «📌 Закрепить», «✎ Заметка»; сверка модели видна; 0 запросов записи. РФ — кнопки «📌 Модель…» нет.
  Уборка: `update tender_lot set proposed_equipment_id = <было>, model_note = <было>, model_set_by = null, model_set_at = null where id in (…); delete from deal_event where id > <max> and code = 'MODEL'; delete from med_equipment where id > <max> and id not in (select proposed_equipment_id from tender_lot where proposed_equipment_id is not null);`
- [ ] **Step 3: Документация** — CLAUDE.md (§7 V40: `med_equipment.site_product_id`, `tender_lot.model_note/model_set_by/model_set_at`, следующая свободная миграция — V41; §8 — блок «Модель в работе лота» с механикой и проверкой; §12 — фронт; §13 — прогон; §15 — пути; §16 — ограничения спеки §9 и мелочи ревью), PROGRESS «▶ Последняя задача», DEPLOY.md §11 (откат: V40 — только новые колонки, старый код их не читает; события `deal_event` с `code = 'MODEL'` старый код показывает строкой без ссылки).
- [ ] **Step 4: Ревью ветки самому** — `git diff main...HEAD` целиком свежим взглядом, Review Focus по пунктам; находки: важное — тест красный до правки → правка; мелочи — в §16.
- [ ] **Step 5: Мерж** — `cd /Users/vlad/IdeaProjects/AIS && git checkout main && git merge --ff-only feature/lot-model-in-work && git branch -d feature/lot-model-in-work`; push — оператор (`! git push origin main`); после деплоя — проверка по SSH (с согласия оператора): V40 применена, лог без ошибок.
