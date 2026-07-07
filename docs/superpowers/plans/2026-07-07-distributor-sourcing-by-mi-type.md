# Подбор поставщиков по виду МИ — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Под лот госзакуп-KZ тендера подтягивать *правильных* дистрибьюторов по виду МИ (специализации) + точечный поиск по бренду/аппарату для мелочи, вместо показа всех подряд по бренду.

**Architecture:** Классификатор `LotTypeClassifier` определяет вид МИ лота по обучаемому словарю `equipment_type_synonym` (имя+ТЗ). `LotSourcingService` ранжирует дистрибьюторов мягким скором `typeHit⊕brandHit` (релевантные сверху, нерелевантные свёрнуты), где brand-сигнал включает Tier-2 «эффективный термин» (бренд предложенной модели → `ComplectTermExtractor` аппарата → реестр-производитель → головной токен). Вид МИ персистится в `lot.equipmentType` через новую ручку. Реальные KZ-дистрибьюторы — веб-ресёрч → миграция.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA/Hibernate 6, Flyway, PostgreSQL 17, Angular 21, MapStruct, Lombok.

## Global Constraints

- **Спек:** `docs/superpowers/specs/2026-07-07-distributor-sourcing-by-mi-type-design.md` (источник истины).
- **Многорыночность (§6 CLAUDE.md):** записи на лот гардить рынком через тендер (`lot.getTender().getMarket() != MarketContext.get()` → `NotFoundException`); `service.findById` = `em.find` минует hibernate-фильтр. Пример-эталон — `TenderLotController.setProposedEquipment`.
- **Flyway (§10 CLAUDE.md):** менять схему ТОЛЬКО новыми миграциями (V7, V8…); V1/V2 не править. После применения миграцию не редактировать (checksum).
- **БД:** nirdb, UTF-8 локаль; `./gradlew` и psql — с `dangerouslyDisableSandbox: true`. psql: `/Library/PostgreSQL/17/bin/psql`, `PGPASSWORD=admin`, user `postgres`, db `nirdb`.
- **Тест-гейт (§13 CLAUDE.md):** зелёный = падают ТОЛЬКО 2 пред-существующих `ApplyAutoFillServiceTest`. Перед `./gradlew test` — `lsof -ti :8080 | xargs kill -9`. git/gradlew — из корня репо (компаунд `cd /Users/vlad/IdeaProjects/AIS && …`).
- **Коммиты:** на ветке `feat/distributor-sourcing-by-mi-type` (уже создана); каждый заканчивать `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **Все субагенты — наследуют модель сессии (§2 CLAUDE.md), `model` не переопределять.**

## File Structure

**Создаём:**
- `src/main/java/com/vladoose/nir/entity/EquipmentTypeSynonym.java` — сущность словаря (term_norm UK → EquipmentType).
- `src/main/java/com/vladoose/nir/repository/EquipmentTypeSynonymRepository.java` — репозиторий словаря.
- `src/main/java/com/vladoose/nir/service/LotTypeClassifier.java` — классификатор лот→вид МИ + обучение.
- `src/main/java/com/vladoose/nir/dto/request/EquipmentTypeAssignRequest.java` — тело POST equipment-type.
- `src/main/resources/db/migration/V7__equipment_type_dictionary.sql` — расширение типов + словарь синонимов.
- `src/main/resources/db/migration/V8__kz_distributors_seed.sql` — реальные KZ-дистрибьюторы (веб-ресёрч).
- `src/test/java/com/vladoose/nir/classifier/LotTypeClassifierTest.java`
- `src/test/java/com/vladoose/nir/sourcing/LotSourcingServiceTest.java`
- `src/test/java/com/vladoose/nir/lot/LotEquipmentTypeEndpointTest.java`

**Меняем:**
- `src/main/java/com/vladoose/nir/dto/response/LotSourcingResponse.java` — +relevant/score/reasons/detectedType/typeAlternatives/singleLot/sourcingTerm.
- `src/main/java/com/vladoose/nir/service/LotSourcingService.java` — новый `build(tenderId, lotIds, term)` со скорингом.
- `src/main/java/com/vladoose/nir/controller/TenderController.java` — `lot-sourcing` +`term`.
- `src/main/java/com/vladoose/nir/controller/TenderLotController.java` — `POST /{id}/equipment-type`.
- `frontend/src/app/services/api.service.ts` — `getLotSourcing(..,term?)` + `setLotEquipmentType`.
- `frontend/src/app/pages/tenders/tenders.component.ts` — КП-панель: селектор вида МИ + поле термина + группировка релевантных.
- `CLAUDE.md` — §8/§15/§16 (в финальной задаче).

---

### Task 1: Словарь видов МИ — сущность, репозиторий, миграция V7

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/EquipmentTypeSynonym.java`
- Create: `src/main/java/com/vladoose/nir/repository/EquipmentTypeSynonymRepository.java`
- Create: `src/main/resources/db/migration/V7__equipment_type_dictionary.sql`

**Interfaces:**
- Produces:
  - `EquipmentTypeSynonym { Long getId(); String getTermNorm(); EquipmentType getEquipmentType(); }` + Lombok `@Builder`.
  - `EquipmentTypeSynonymRepository extends JpaRepository<EquipmentTypeSynonym, Long> { boolean existsByTermNorm(String termNorm); }`
  - Таблица `equipment_type_synonym(term_norm UK → equipment_type_id)`; в `equipment_type` добавлены 8 категорий; словарь синонимов засеян.

- [ ] **Step 1: Сущность `EquipmentTypeSynonym`**

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "equipment_type_synonym")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EquipmentTypeSynonym {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Нормализованный термин (lower, trim) — подстрока имени/ТЗ лота. */
    @Column(name = "term_norm", length = 255, unique = true, nullable = false)
    private String termNorm;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "equipment_type_id", nullable = false)
    private EquipmentType equipmentType;
}
```

- [ ] **Step 2: Репозиторий**

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.EquipmentTypeSynonym;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentTypeSynonymRepository extends JpaRepository<EquipmentTypeSynonym, Long> {
    boolean existsByTermNorm(String termNorm);
}
```

- [ ] **Step 3: Миграция V7** (расширение типов + таблица + сид синонимов)

Термины — lowercased стеммы-подстроки (матч `contains`), поэтому «вентиляц» ловит «вентиляции».

```sql
-- V7: справочник видов МИ — новые категории + обучаемый словарь синонимов для классификатора лота.

-- 1. Умеренное расширение equipment_type под реальные KZ-закупки (не мелочь — её ловит точечный поиск).
INSERT INTO equipment_type (name) VALUES
  ('Хирургическое оборудование'),
  ('Стерилизация и дезинфекция'),
  ('Анестезия и реанимация'),
  ('Физиотерапия'),
  ('Стоматологическое оборудование'),
  ('Реабилитационное оборудование'),
  ('Медицинская мебель'),
  ('Неонатальное оборудование')
ON CONFLICT (name) DO NOTHING;

-- 2. Обучаемый словарь синонимов (по образцу header_synonym).
CREATE TABLE IF NOT EXISTS equipment_type_synonym (
  id                BIGSERIAL PRIMARY KEY,
  term_norm         VARCHAR(255) NOT NULL UNIQUE,
  equipment_type_id BIGINT NOT NULL REFERENCES equipment_type(id)
);

-- 3. Сид синонимов: (термин-подстрока, имя типа). Термины в нижнем регистре.
INSERT INTO equipment_type_synonym (term_norm, equipment_type_id)
SELECT s.term, et.id FROM equipment_type et JOIN (VALUES
  ('узи', 'УЗИ'), ('ультразвук', 'УЗИ'), ('ультразвуков', 'УЗИ'), ('сканер ультразвук', 'УЗИ'), ('эхокардиограф', 'УЗИ'),
  ('рентген', 'Рентген'), ('рентгеновск', 'Рентген'), ('флюорограф', 'Рентген'), ('рентгендиагностическ', 'Рентген'),
  ('ивл', 'ИВЛ'), ('вентиляц', 'ИВЛ'), ('вентилятор лёгких', 'ИВЛ'), ('вентилятор легких', 'ИВЛ'), ('искусственной вентиляц', 'ИВЛ'),
  ('монитор пациента', 'Монитор пациента'), ('прикроватн', 'Монитор пациента'), ('монитор реанимационн', 'Монитор пациента'),
  ('компьютерн томограф', 'Компьютерный томограф'), ('компьютерной томограф', 'Компьютерный томограф'), ('кт-скан', 'Компьютерный томограф'),
  ('магнитно-резонанс', 'Магнитно-резонансный томограф'), ('мр-томограф', 'Магнитно-резонансный томограф'),
  ('электрокардиограф', 'ЭКГ'), ('экг', 'ЭКГ'), ('кардиограф', 'ЭКГ'),
  ('дефибриллятор', 'Дефибриллятор'),
  ('анализатор', 'Лабораторный анализатор'), ('гематологическ', 'Лабораторный анализатор'), ('биохимическ', 'Лабораторный анализатор'), ('коагулометр', 'Лабораторный анализатор'), ('центрифуга', 'Лабораторный анализатор'),
  ('эндоскоп', 'Эндоскоп'), ('гастроскоп', 'Эндоскоп'), ('колоноскоп', 'Эндоскоп'), ('бронхоскоп', 'Эндоскоп'), ('видеоэндоскоп', 'Эндоскоп'),
  ('хирургическ', 'Хирургическое оборудование'), ('электрохирург', 'Хирургическое оборудование'), ('операционн стол', 'Хирургическое оборудование'), ('операционн светильник', 'Хирургическое оборудование'), ('коагулятор', 'Хирургическое оборудование'), ('лапароскоп', 'Хирургическое оборудование'),
  ('стерилизатор', 'Стерилизация и дезинфекция'), ('автоклав', 'Стерилизация и дезинфекция'), ('стерилизац', 'Стерилизация и дезинфекция'), ('дезинфекц', 'Стерилизация и дезинфекция'),
  ('наркозно', 'Анестезия и реанимация'), ('наркозный', 'Анестезия и реанимация'), ('анестезиолог', 'Анестезия и реанимация'), ('аппарат ингаляционного наркоза', 'Анестезия и реанимация'),
  ('физиотерап', 'Физиотерапия'), ('электрофорез', 'Физиотерапия'), ('магнитотерап', 'Физиотерапия'), ('увч', 'Физиотерапия'), ('электростимул', 'Физиотерапия'),
  ('стоматолог', 'Стоматологическое оборудование'), ('дентальн', 'Стоматологическое оборудование'), ('стоматологическая установка', 'Стоматологическое оборудование'),
  ('реабилитац', 'Реабилитационное оборудование'), ('механотерап', 'Реабилитационное оборудование'),
  ('кровать медицинск', 'Медицинская мебель'), ('функциональная кровать', 'Медицинская мебель'), ('медицинская мебель', 'Медицинская мебель'), ('каталка', 'Медицинская мебель'),
  ('инкубатор', 'Неонатальное оборудование'), ('неонатальн', 'Неонатальное оборудование'), ('фототерап', 'Неонатальное оборудование'), ('обогреватель новорожд', 'Неонатальное оборудование')
) AS s(term, type_name) ON et.name = s.type_name
ON CONFLICT (term_norm) DO NOTHING;
```

- [ ] **Step 4: Применить миграцию и проверить**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew bootRun` (дать Flyway накатить V7, затем остановить: Ctrl-C / `lsof -ti :8080 | xargs kill -9`).
Затем: `PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "SELECT count(*) FROM equipment_type_synonym; SELECT count(*) FROM equipment_type;"` (с `dangerouslyDisableSandbox: true`).
Expected: synonym count ≥ 60; equipment_type count = 18 (10 старых + 8 новых).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/entity/EquipmentTypeSynonym.java src/main/java/com/vladoose/nir/repository/EquipmentTypeSynonymRepository.java src/main/resources/db/migration/V7__equipment_type_dictionary.sql && git commit -m "feat(sourcing): словарь видов МИ — equipment_type_synonym + расширение типов (V7)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Классификатор `LotTypeClassifier`

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/LotTypeClassifier.java`
- Test: `src/test/java/com/vladoose/nir/classifier/LotTypeClassifierTest.java`

**Interfaces:**
- Consumes: `EquipmentTypeSynonymRepository` (Task 1), `LotQueryTokenizer.tokenize(String,String)` (существует), `TenderLot` (equipName/requiredSpec).
- Produces:
  - `LotTypeClassifier.TypeGuess { Long typeId(); String typeName(); double confidence(); List<TypeGuess> alternatives(); }` (record)
  - `TypeGuess classify(TenderLot lot)` — при отсутствии совпадений `typeId()==null`, `confidence()==0`, `alternatives()` пуст.
  - `void learn(TenderLot lot, EquipmentType type)` — best-effort: головной токен имени → тип, если термина ещё нет.

- [ ] **Step 1: Failing-тест классификатора**

```java
package com.vladoose.nir.classifier;

import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.LotTypeClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class LotTypeClassifierTest {

    @Autowired LotTypeClassifier classifier;

    private TenderLot lot(String name, String spec) {
        TenderLot l = new TenderLot();
        l.setEquipName(name);
        l.setRequiredSpec(spec);
        return l;
    }

    @Test
    void detectsIvlFromName() {
        LotTypeClassifier.TypeGuess g = classifier.classify(
                lot("Аппарат искусственной вентиляции лёгких экспертного класса", null));
        assertThat(g.typeId()).isNotNull();
        assertThat(g.typeName()).isEqualTo("ИВЛ");
        assertThat(g.confidence()).isGreaterThan(0.0);
    }

    @Test
    void detectsUziFromSpec() {
        LotTypeClassifier.TypeGuess g = classifier.classify(
                lot("Диагностический комплекс", "Стационарный ультразвуковой сканер с конвексным датчиком"));
        assertThat(g.typeName()).isEqualTo("УЗИ");
    }

    @Test
    void unknownForGarbage() {
        LotTypeClassifier.TypeGuess g = classifier.classify(lot("Услуга по поставке товара", null));
        assertThat(g.typeId()).isNull();
        assertThat(g.confidence()).isEqualTo(0.0);
    }
}
```

- [ ] **Step 2: Прогнать — падает (нет класса)**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.classifier.LotTypeClassifierTest'` (с `dangerouslyDisableSandbox: true`).
Expected: FAIL — компиляция (класс `LotTypeClassifier` не существует).

- [ ] **Step 3: Реализовать `LotTypeClassifier`**

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.EquipmentTypeSynonym;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.EquipmentTypeSynonymRepository;
import com.vladoose.nir.util.LotQueryTokenizer;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Классификатор вида МИ лота по обучаемому словарю equipment_type_synonym: подстрочный матч
 * терминов в (имя + ТЗ). Совпадение в имени весит сильнее, чем в ТЗ. Пустой результат допустим.
 */
@Service
public class LotTypeClassifier {

    private final EquipmentTypeSynonymRepository synonymRepository;

    public LotTypeClassifier(EquipmentTypeSynonymRepository synonymRepository) {
        this.synonymRepository = synonymRepository;
    }

    public record TypeGuess(Long typeId, String typeName, double confidence, List<TypeGuess> alternatives) {}

    private static final TypeGuess UNKNOWN = new TypeGuess(null, null, 0.0, List.of());

    public TypeGuess classify(TenderLot lot) {
        String name = lot.getEquipName() == null ? "" : lot.getEquipName().toLowerCase();
        String spec = lot.getRequiredSpec() == null ? "" : lot.getRequiredSpec().toLowerCase();
        String all = (name + " " + spec).trim();
        if (all.isBlank()) return UNKNOWN;

        Map<Long, Double> score = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (EquipmentTypeSynonym syn : synonymRepository.findAll()) {
            String term = syn.getTermNorm();
            if (term == null || term.isBlank() || !all.contains(term)) continue;
            double w = term.length();
            if (name.contains(term)) w *= 1.5;              // сигнал из имени сильнее
            Long tid = syn.getEquipmentType().getId();
            score.merge(tid, w, Double::sum);
            names.putIfAbsent(tid, syn.getEquipmentType().getName());
        }
        if (score.isEmpty()) return UNKNOWN;

        double total = score.values().stream().mapToDouble(Double::doubleValue).sum();
        List<Map.Entry<Long, Double>> sorted = score.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).toList();
        Map.Entry<Long, Double> top = sorted.get(0);
        List<TypeGuess> alts = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            Long tid = sorted.get(i).getKey();
            alts.add(new TypeGuess(tid, names.get(tid), sorted.get(i).getValue() / total, List.of()));
        }
        return new TypeGuess(top.getKey(), names.get(top.getKey()), top.getValue() / total, alts);
    }

    /** Best-effort обучение: головной токен имени лота → выбранный тип, если термина ещё нет. */
    public void learn(TenderLot lot, EquipmentType type) {
        if (type == null || lot == null) return;
        List<LotQueryTokenizer.WeightedToken> toks = LotQueryTokenizer.tokenize(lot.getEquipName(), null);
        if (toks.isEmpty()) return;
        String head = toks.get(0).token();
        if (head == null || head.length() < 4 || synonymRepository.existsByTermNorm(head)) return;
        synonymRepository.save(EquipmentTypeSynonym.builder().termNorm(head).equipmentType(type).build());
    }
}
```

- [ ] **Step 4: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.classifier.LotTypeClassifierTest'` (sandbox off).
Expected: PASS (3 теста).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/LotTypeClassifier.java src/test/java/com/vladoose/nir/classifier/LotTypeClassifierTest.java && git commit -m "feat(sourcing): LotTypeClassifier — вид МИ лота по словарю синонимов

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Ручка персиста вида МИ на лоте

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/EquipmentTypeAssignRequest.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderLotController.java`
- Test: `src/test/java/com/vladoose/nir/lot/LotEquipmentTypeEndpointTest.java`

**Interfaces:**
- Consumes: `TenderLotService.findById/save`, `EquipmentTypeService.findById`, `LotTypeClassifier.learn` (Task 2), `MarketContext`.
- Produces: `POST /api/lots/{id}/equipment-type` body `{ "typeId": <id|null> }` → `TenderLotResponse`; гард чужого рынка (`NotFoundException`); best-effort обучение словаря.

- [ ] **Step 1: DTO запроса**

```java
package com.vladoose.nir.dto.request;

import lombok.Data;

@Data
public class EquipmentTypeAssignRequest {
    /** id вида МИ; null — снять тип с лота. */
    private Long typeId;
}
```

- [ ] **Step 2: Failing-тест ручки** (создаёт KZ-тендер+лот, ставит тип, проверяет гард чужого рынка)

```java
package com.vladoose.nir.lot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderLotController;
import com.vladoose.nir.dto.request.EquipmentTypeAssignRequest;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.EquipmentTypeRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LotEquipmentTypeEndpointTest {

    @Autowired TenderLotController controller;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired EquipmentTypeRepository typeRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private TenderLot lotIn(Market market) {
        MarketContext.set(market);
        Tender t = tenderRepository.save(Tender.builder()
                .tenderNumber("T-" + market + "-" + System.nanoTime())
                .status("NEW").market(market).build());
        return lotRepository.save(TenderLot.builder()
                .tender(t).lotNumber(1).equipName("Аппарат ИВЛ реанимационный").quantity(1).build());
    }

    @Test
    void setsType() {
        TenderLot lot = lotIn(Market.KZ);
        EquipmentType type = typeRepository.findAll().stream()
                .filter(x -> x.getName().equals("ИВЛ")).findFirst().orElseThrow();
        EquipmentTypeAssignRequest req = new EquipmentTypeAssignRequest();
        req.setTypeId(type.getId());

        MarketContext.set(Market.KZ);
        controller.setEquipmentType(lot.getId(), req);

        assertThat(lotRepository.findById(lot.getId()).orElseThrow().getEquipmentType().getName()).isEqualTo("ИВЛ");
    }

    @Test
    void rejectsForeignMarket() {
        TenderLot lot = lotIn(Market.KZ);
        EquipmentTypeAssignRequest req = new EquipmentTypeAssignRequest();
        req.setTypeId(typeRepository.findAll().get(0).getId());

        MarketContext.set(Market.RF);   // чужой рынок
        assertThatThrownBy(() -> controller.setEquipmentType(lot.getId(), req))
                .isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 3: Прогнать — падает**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.lot.LotEquipmentTypeEndpointTest'` (sandbox off).
Expected: FAIL — метода `setEquipmentType` нет.

- [ ] **Step 4: Добавить зависимости и ручку в `TenderLotController`**

В конструктор/поля добавить `EquipmentTypeService equipmentTypeService` и `LotTypeClassifier classifier` (импорты: `com.vladoose.nir.service.EquipmentTypeService`, `com.vladoose.nir.service.LotTypeClassifier`, `com.vladoose.nir.dto.request.EquipmentTypeAssignRequest`, `com.vladoose.nir.entity.EquipmentType`). Затем добавить метод рядом с `setProposedEquipment`:

```java
    /** Назначить/снять вид МИ лота (питает подбор поставщиков; чинит несохранение типа). */
    @PostMapping("/{id}/equipment-type")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse setEquipmentType(@PathVariable Long id,
                                              @RequestBody EquipmentTypeAssignRequest request) {
        TenderLot lot = service.findById(id);
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + id);
        }
        EquipmentType type = request.getTypeId() == null ? null
                : equipmentTypeService.findById(request.getTypeId());
        lot.setEquipmentType(type);
        TenderLotResponse resp = mapper.toResponse(service.save(lot));
        if (type != null) {
            try { classifier.learn(lot, type); } catch (RuntimeException ignore) { /* best-effort */ }
        }
        return resp;
    }
```

> Если `EquipmentTypeService.findById` отсутствует — заинжектить `EquipmentTypeRepository` и использовать `.findById(request.getTypeId()).orElseThrow(() -> new NotFoundException("Тип не найден"))`. Проверить: `grep -n "findById" src/main/java/com/vladoose/nir/service/EquipmentTypeService.java`.

- [ ] **Step 5: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.lot.LotEquipmentTypeEndpointTest'` (sandbox off).
Expected: PASS (2 теста).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/request/EquipmentTypeAssignRequest.java src/main/java/com/vladoose/nir/controller/TenderLotController.java src/test/java/com/vladoose/nir/lot/LotEquipmentTypeEndpointTest.java && git commit -m "feat(sourcing): POST /api/lots/{id}/equipment-type — персист вида МИ + гард рынка

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Ранжирование поставщиков (type⊕brand + Tier 2) в `LotSourcingService`

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/response/LotSourcingResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/LotSourcingService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderController.java`
- Test: `src/test/java/com/vladoose/nir/sourcing/LotSourcingServiceTest.java`

**Interfaces:**
- Consumes: `LotTypeClassifier.classify` (Task 2), `ComplectTermExtractor.extract` (существует), `LotQueryTokenizer.tokenize`, `RegistryMatchService.candidatesForLot`, `DistributorService.findAll`, `BrandMatch.firstCarried`.
- Produces:
  - `LotSourcingService.build(Long tenderId, List<Long> lotIds, String termOverride)` → расширенный `LotSourcingResponse`.
  - `LotSourcingResponse`: top-level `detectedType`, `typeAlternatives`, `singleLot`, `sourcingTerm`; `Entry`: `relevant`, `score`, `reasons`; вложенные `DetectedType{id,name,confidence}`, `TypeRef{id,name}`, `Reason{kind,label}`.
  - `GET /api/tenders/{id}/lot-sourcing` +`@RequestParam(required=false) String term`.

- [ ] **Step 1: Расширить `LotSourcingResponse`** (полная новая версия файла)

```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class LotSourcingResponse {

    private List<Entry> distributors;
    private boolean singleLot;
    private DetectedType detectedType;              // только одно-лотовый режим, иначе null
    private List<TypeRef> typeAlternatives;         // альтернативы классификатора (одно-лотовый режим)
    private String sourcingTerm;                    // эффективный термин Tier 2 (префилл поля на фронте)

    @Data
    public static class Entry {
        private DistributorResponse distributor;
        private boolean preselect;
        private boolean relevant;
        private double score;
        private List<BrandHit> matchedBrands;       // обратная совместимость
        private List<Reason> reasons;               // суперсет: типы + бренды (фронт рисует его)
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandHit {
        private String brand;
        private String via;   // PROPOSED_MODEL | REGISTRY | SEARCH_TERM
        private Long lotId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reason {
        private String kind;  // TYPE | BRAND
        private String label;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectedType {
        private Long id;
        private String name;
        private double confidence;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypeRef {
        private Long id;
        private String name;
    }
}
```

- [ ] **Step 2: Failing-тест сценариев скоринга**

```java
package com.vladoose.nir.sourcing;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.LotSourcingService;
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

    @Autowired LotSourcingService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired EquipmentTypeRepository typeRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    private EquipmentType type(String name) {
        return typeRepository.findAll().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void typeHitRanksAboveNonRelevant() {
        MarketContext.set(Market.KZ);
        EquipmentType ivl = type("ИВЛ");
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("T-" + System.nanoTime())
                .status("NEW").market(Market.KZ).build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1)
                .equipName("Аппарат искусственной вентиляции лёгких").quantity(1).equipmentType(ivl).build());

        Distributor typed = distributorRepository.save(Distributor.builder()
                .name("Профильный ИВЛ " + System.nanoTime()).market(Market.KZ)
                .equipmentTypes(List.of(ivl)).build());
        Distributor other = distributorRepository.save(Distributor.builder()
                .name("Непрофильный " + System.nanoTime()).market(Market.KZ).build());

        LotSourcingResponse resp = service.build(t.getId(), List.of(lot.getId()), null);

        LotSourcingResponse.Entry typedEntry = resp.getDistributors().stream()
                .filter(e -> e.getDistributor().getId().equals(typed.getId())).findFirst().orElseThrow();
        LotSourcingResponse.Entry otherEntry = resp.getDistributors().stream()
                .filter(e -> e.getDistributor().getId().equals(other.getId())).findFirst().orElseThrow();

        assertThat(typedEntry.isRelevant()).isTrue();
        assertThat(typedEntry.getReasons()).anyMatch(r -> r.getKind().equals("TYPE") && r.getLabel().equals("ИВЛ"));
        assertThat(otherEntry.isRelevant()).isFalse();
        assertThat(resp.getDistributors().indexOf(typedEntry)).isLessThan(resp.getDistributors().indexOf(otherEntry));
        assertThat(resp.isSingleLot()).isTrue();
        assertThat(resp.getDetectedType()).isNotNull();
        assertThat(resp.getDetectedType().getName()).isEqualTo("ИВЛ");
    }

    @Test
    void tier2AccessoryTermFindsApparatusBrand() {
        MarketContext.set(Market.KZ);
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("T-" + System.nanoTime())
                .status("NEW").market(Market.KZ).build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1)
                .equipName("Электроды силиконовые для аппарата «Элэскулап» 55×80").quantity(1).build());

        Distributor carriesApparatus = distributorRepository.save(Distributor.builder()
                .name("Возит Элэскулап " + System.nanoTime()).market(Market.KZ)
                .brands(List.of("Элэскулап")).build());

        LotSourcingResponse resp = service.build(t.getId(), List.of(lot.getId()), null);

        assertThat(resp.getSourcingTerm()).containsIgnoringCase("элэскулап");
        LotSourcingResponse.Entry e = resp.getDistributors().stream()
                .filter(x -> x.getDistributor().getId().equals(carriesApparatus.getId())).findFirst().orElseThrow();
        assertThat(e.isRelevant()).isTrue();
        assertThat(e.getReasons()).anyMatch(r -> r.getKind().equals("BRAND"));
    }

    @Test
    void manualTermOverridesAuto() {
        MarketContext.set(Market.KZ);
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("T-" + System.nanoTime())
                .status("NEW").market(Market.KZ).build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1)
                .equipName("Расходный материал").quantity(1).build());
        Distributor d = distributorRepository.save(Distributor.builder()
                .name("Возит Mindray " + System.nanoTime()).market(Market.KZ)
                .brands(List.of("Mindray")).build());

        LotSourcingResponse resp = service.build(t.getId(), List.of(lot.getId()), "Mindray");

        assertThat(resp.getSourcingTerm()).isEqualTo("Mindray");
        assertThat(resp.getDistributors().stream()
                .filter(x -> x.getDistributor().getId().equals(d.getId())).findFirst().orElseThrow()
                .isRelevant()).isTrue();
    }
}
```

- [ ] **Step 3: Прогнать — падает**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.sourcing.LotSourcingServiceTest'` (sandbox off).
Expected: FAIL — сигнатура `build(.., null)` не существует / нет полей ответа.

- [ ] **Step 4: Переписать `LotSourcingService`** (полная новая версия файла)

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotSourcingResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.util.BrandMatch;
import com.vladoose.nir.util.ComplectTermExtractor;
import com.vladoose.nir.util.LotQueryTokenizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Подбор поставщиков по лоту(ам): мягкий ранг typeHit⊕brandHit. Вид МИ лота — сохранённый или
 * от LotTypeClassifier. Brand-сигнал = бренд предложенной модели + производители реестр-кандидатов +
 * эффективный термин Tier 2 (бренд/аппарат для аксессуарных лотов). Релевантные сверху.
 */
@Service
public class LotSourcingService {

    static final int REGISTRY_TOP = 5;
    static final double REGISTRY_SCORE_MIN = 0.35;

    private final TenderLotService tenderLotService;
    private final DistributorService distributorService;
    private final RegistryMatchService registryMatchService;
    private final DistributorMapper distributorMapper;
    private final LotTypeClassifier classifier;

    public LotSourcingService(TenderLotService tenderLotService,
                              DistributorService distributorService,
                              RegistryMatchService registryMatchService,
                              DistributorMapper distributorMapper,
                              LotTypeClassifier classifier) {
        this.tenderLotService = tenderLotService;
        this.distributorService = distributorService;
        this.registryMatchService = registryMatchService;
        this.distributorMapper = distributorMapper;
        this.classifier = classifier;
    }

    private record BrandSource(Long lotId, String text, String via) {}

    public LotSourcingResponse build(Long tenderId, List<Long> lotIds, String termOverride) {
        if (lotIds == null || lotIds.isEmpty()) throw new BadRequestException("Не выбраны лоты");

        List<TenderLot> lots = new ArrayList<>();
        List<BrandSource> sources = new ArrayList<>();
        for (Long lotId : lotIds) {
            TenderLot lot = tenderLotService.findById(lotId); // em.find обходит фильтр рынка
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + lotId + " не принадлежит тендеру " + tenderId);
            }
            if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
                throw new BadRequestException("Лот " + lotId + " не найден");
            }
            lots.add(lot);
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

        boolean singleLot = lots.size() == 1;

        // вид(ы) МИ: сохранённый на лоте или классификатор
        Set<Long> lotTypeIds = new LinkedHashSet<>();
        LotSourcingResponse.DetectedType detectedType = null;
        List<LotSourcingResponse.TypeRef> typeAlternatives = new ArrayList<>();
        for (TenderLot lot : lots) {
            if (lot.getEquipmentType() != null) {
                lotTypeIds.add(lot.getEquipmentType().getId());
                if (singleLot) detectedType = new LotSourcingResponse.DetectedType(
                        lot.getEquipmentType().getId(), lot.getEquipmentType().getName(), 1.0);
            } else {
                LotTypeClassifier.TypeGuess g = classifier.classify(lot);
                if (g.typeId() != null) {
                    lotTypeIds.add(g.typeId());
                    if (singleLot) {
                        detectedType = new LotSourcingResponse.DetectedType(g.typeId(), g.typeName(), g.confidence());
                        for (LotTypeClassifier.TypeGuess alt : g.alternatives()) {
                            typeAlternatives.add(new LotSourcingResponse.TypeRef(alt.typeId(), alt.typeName()));
                        }
                    }
                }
            }
        }

        // Tier 2: эффективный термин точечного поиска (одно-лотовый режим)
        String sourcingTerm = null;
        if (singleLot) {
            TenderLot lot = lots.get(0);
            if (termOverride != null && !termOverride.isBlank()) {
                sourcingTerm = termOverride.trim();
            } else if (lot.getProposedEquipment() != null
                    && lot.getProposedEquipment().getManufact() != null
                    && !lot.getProposedEquipment().getManufact().isBlank()) {
                sourcingTerm = lot.getProposedEquipment().getManufact().trim();
            } else {
                String complect = ComplectTermExtractor.extract(lot.getEquipName(), lot.getRequiredSpec());
                if (complect != null && !complect.isBlank()) {
                    sourcingTerm = complect.trim();
                } else {
                    String regProducer = sources.stream().filter(s -> "REGISTRY".equals(s.via()))
                            .map(BrandSource::text).filter(x -> x != null && !x.isBlank()).findFirst().orElse(null);
                    if (regProducer != null) {
                        sourcingTerm = regProducer.trim();
                    } else {
                        List<LotQueryTokenizer.WeightedToken> toks = LotQueryTokenizer.tokenize(lot.getEquipName(), null);
                        sourcingTerm = toks.isEmpty() ? null : toks.get(0).token();
                    }
                }
            }
            if (sourcingTerm != null && !sourcingTerm.isBlank()) {
                sources.add(new BrandSource(lot.getId(), sourcingTerm, "SEARCH_TERM"));
            }
        }

        // скоринг
        List<LotSourcingResponse.Entry> entries = new ArrayList<>();
        for (Distributor d : distributorService.findAll()) {
            List<LotSourcingResponse.Reason> reasons = new ArrayList<>();
            boolean typeHit = false;
            if (!lotTypeIds.isEmpty() && d.getEquipmentTypes() != null) {
                for (EquipmentType et : d.getEquipmentTypes()) {
                    if (lotTypeIds.contains(et.getId())) {
                        typeHit = true;
                        if (reasons.stream().noneMatch(r -> "TYPE".equals(r.getKind()) && r.getLabel().equals(et.getName()))) {
                            reasons.add(new LotSourcingResponse.Reason("TYPE", et.getName()));
                        }
                    }
                }
            }
            List<LotSourcingResponse.BrandHit> brandHits = new ArrayList<>();
            for (BrandSource s : sources) {
                String brand = BrandMatch.firstCarried(d.getBrands(), s.text());
                if (brand == null) continue;
                if (brandHits.stream().noneMatch(h -> h.getBrand().equals(brand)
                        && h.getVia().equals(s.via()) && h.getLotId().equals(s.lotId()))) {
                    brandHits.add(new LotSourcingResponse.BrandHit(brand, s.via(), s.lotId()));
                }
                if (reasons.stream().noneMatch(r -> "BRAND".equals(r.getKind()) && r.getLabel().equals(brand))) {
                    reasons.add(new LotSourcingResponse.Reason("BRAND", brand));
                }
            }
            boolean brandHit = !brandHits.isEmpty();
            double score = (brandHit ? 1.0 : 0.0) + (typeHit ? 0.7 : 0.0) + (brandHit && typeHit ? 0.3 : 0.0);

            LotSourcingResponse.Entry e = new LotSourcingResponse.Entry();
            e.setDistributor(distributorMapper.toResponse(d));
            e.setMatchedBrands(brandHits);
            e.setReasons(reasons);
            e.setRelevant(brandHit || typeHit);
            e.setScore(score);
            e.setPreselect(brandHit);
            entries.add(e);
        }
        entries.sort((a, b) -> {
            if (a.isRelevant() != b.isRelevant()) return a.isRelevant() ? -1 : 1;
            return Double.compare(b.getScore(), a.getScore());
        });

        LotSourcingResponse resp = new LotSourcingResponse();
        resp.setDistributors(entries);
        resp.setSingleLot(singleLot);
        resp.setDetectedType(detectedType);
        resp.setTypeAlternatives(typeAlternatives);
        resp.setSourcingTerm(sourcingTerm);
        return resp;
    }
}
```

- [ ] **Step 5: Обновить эндпоинт в `TenderController`**

Заменить метод `lotSourcing` (около строки 60):

```java
    @GetMapping("/{id}/lot-sourcing")
    public LotSourcingResponse lotSourcing(@PathVariable Long id, @RequestParam List<Long> lotIds,
                                           @RequestParam(required = false) String term) {
        return lotSourcingService.build(id, lotIds, term);
    }
```

- [ ] **Step 6: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.sourcing.LotSourcingServiceTest'` (sandbox off).
Expected: PASS (3 теста). Если фон-автоформат задублировал/съел метод — `grep -c "public LotSourcingResponse build" src/main/java/com/vladoose/nir/service/LotSourcingService.java` (ждём 1) + `./gradlew compileJava`.

- [ ] **Step 7: Регресс всей сборки**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test` (sandbox off).
Expected: падают ТОЛЬКО 2 `ApplyAutoFillServiceTest`.

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/response/LotSourcingResponse.java src/main/java/com/vladoose/nir/service/LotSourcingService.java src/main/java/com/vladoose/nir/controller/TenderController.java src/test/java/com/vladoose/nir/sourcing/LotSourcingServiceTest.java && git commit -m "feat(sourcing): ранг поставщиков по виду МИ + Tier 2 точечный поиск по бренду/аппарату

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Реальные KZ-дистрибьюторы — веб-ресёрч + миграция V8

**Files:**
- Create: `src/main/resources/db/migration/V8__kz_distributors_seed.sql`

**Interfaces:**
- Consumes: `equipment_type` (Task 1, включая новые типы), таблицы `distributor`/`distributor_brand`/`distributor_equipment_type`.
- Produces: ≥8 реальных KZ-дистрибьюторов (market='KZ') с брендами и видами МИ; удаление 2 фейков из V2.

- [ ] **Step 1: Веб-ресёрч**

Через `WebSearch`/`WebFetch` собрать реальных дистрибьюторов медтехники Казахстана. Запросы (примеры):
`"дистрибьютор медицинского оборудования Казахстан"`, `"поставщик медтехники Алматы официальный дилер Mindray"`, `"медицинское оборудование Казахстан УЗИ ИВЛ дистрибьютор"`, `"medical equipment distributor Kazakhstan official dealer"`.
Для каждого зафиксировать: название (ТОО «…»), город, сайт, публичный контакт/телефон если есть, **портфель брендов** (кого возят → в `distributor_brand`), **виды МИ** (маппинг на имена `equipment_type` — существующие и новые из V7).
Требования: **≥8 дистрибьюторов**, у каждого ≥1 бренд и ≥1 вид МИ. **email оставить NULL** (пользователь верифицирует; в чат адреса не эхо-печатать) — в SQL пометка `-- email: verify`. В комментарии миграции указать источник (URL) каждого.

- [ ] **Step 2: Написать V8** (шаблон; заменить example-строки реальными из ресёрча — ≥8 дистрибьюторов)

```sql
-- V8: реальные KZ-дистрибьюторы медтехники (веб-ресёрч 2026-07-07). email — verify перед рассылкой.
-- Источники в комментариях у строк.

-- Чистим демо-фейки из V2 (их type/brand-привязки уйдут по FK-каскаду или отдельными DELETE ниже).
DELETE FROM distributor_brand WHERE distributor_id IN
  (SELECT id FROM distributor WHERE market='KZ' AND name IN ('ТОО «МедСнаб Казахстан»','ТОО «Алматы Медтехника»'));
DELETE FROM distributor_equipment_type WHERE distributor_id IN
  (SELECT id FROM distributor WHERE market='KZ' AND name IN ('ТОО «МедСнаб Казахстан»','ТОО «Алматы Медтехника»'));
DELETE FROM distributor WHERE market='KZ' AND name IN ('ТОО «МедСнаб Казахстан»','ТОО «Алматы Медтехника»');

-- Реальные дистрибьюторы. ПРИМЕР строки — заменить/дополнить результатами ресёрча (≥8).
INSERT INTO distributor (name, address, website, market) VALUES
  ('ТОО «<Реальное название>»', 'г. Алматы, <адрес>', 'https://<сайт>', 'KZ')  -- источник: <URL>
ON CONFLICT (name) DO NOTHING;

-- Бренды дистрибьютора (кого возит) — по названию.
INSERT INTO distributor_brand (distributor_id, brand)
SELECT d.id, b FROM distributor d, (VALUES ('Mindray'), ('Philips')) AS x(b)
WHERE d.name = 'ТОО «<Реальное название>»'
ON CONFLICT DO NOTHING;

-- Виды МИ дистрибьютора — маппинг на equipment_type по имени.
INSERT INTO distributor_equipment_type (distributor_id, equipment_type_id)
SELECT d.id, et.id FROM distributor d, equipment_type et
WHERE d.name = 'ТОО «<Реальное название>»' AND et.name IN ('УЗИ','Монитор пациента')
ON CONFLICT DO NOTHING;
```

> Проверить наличие уникального индекса для `ON CONFLICT` на `distributor_brand`/`distributor_equipment_type`; если его нет — убрать `ON CONFLICT DO NOTHING` у этих двух вставок (таблица наполняется впервые, дублей не будет).

- [ ] **Step 3: Применить и проверить**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew bootRun` (Flyway накатит V8), затем стоп (`lsof -ti :8080 | xargs kill -9`).
Проверка: `PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "SELECT d.name, count(DISTINCT b.brand) brands, count(DISTINCT et.equipment_type_id) types FROM distributor d LEFT JOIN distributor_brand b ON b.distributor_id=d.id LEFT JOIN distributor_equipment_type et ON et.distributor_id=d.id WHERE d.market='KZ' GROUP BY d.name;"` (sandbox off).
Expected: ≥8 KZ-дистрибьюторов, у каждого brands≥1 и types≥1; фейков нет.

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V8__kz_distributors_seed.sql && git commit -m "feat(sourcing): реальные KZ-дистрибьюторы с брендами и видами МИ (V8)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Фронт — селектор вида МИ, поле термина, группировка релевантных

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `GET /tenders/{id}/lot-sourcing?term=` (Task 4), `POST /lots/{id}/equipment-type` (Task 3), `GET /equipment-types` (существует).
- Produces: КП-панель с авто-видом МИ (editable), полем «Поиск поставщика», релевантными сверху + сворачиваемыми нерелевантными.

- [ ] **Step 1: `ApiService` — `getLotSourcing(..,term?)` + `setLotEquipmentType`**

Заменить метод `getLotSourcing` (строки ~202-207) на:

```ts
  /** Подсказки поставщиков для запроса КП по лотам тендера (опц. точечный термин Tier 2). */
  getLotSourcing(tenderId: number, lotIds: number[], term?: string): Observable<any> {
    const params: any = { lotIds: lotIds.join(',') };
    if (term) params.term = term;
    return this.http.get<any>(`${this.base}/tenders/${tenderId}/lot-sourcing`, { params });
  }

  /** Назначить/снять вид МИ лота. */
  setLotEquipmentType(lotId: number, typeId: number | null): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/equipment-type`, { typeId });
  }
```

- [ ] **Step 2: Component — состояние + загрузка типов**

Найти `kpPanel: { loading: boolean; sending: boolean; entries: any[] } | null = null;` (строка ~776) и заменить на:

```ts
  kpPanel: {
    loading: boolean; sending: boolean; entries: any[];
    _relevant: any[]; _nonrel: any[]; _showNonrel: boolean;
    singleLot: boolean;
    detectedType: { id: number; name: string; confidence: number } | null;
    typeAlternatives: { id: number; name: string }[];
    sourcingTerm: string;
    lotId: number | null;
  } | null = null;
  equipmentTypesList: any[] = [];
```

В `ngOnInit()` (найти `ngOnInit`) добавить загрузку типов:

```ts
    this.api.getEquipmentTypes().subscribe(ts => { this.equipmentTypesList = ts || []; this.cdr.detectChanges(); });
```

- [ ] **Step 3: Component — `openKpPanel` c группировкой + `changeLotType`/`researchSupplier`**

Заменить `openKpPanel()` (строки ~1501-1517) на версию с параметром термина и группировкой; `openKpPanelFor` не трогаем (он зовёт `openKpPanel()`):

```ts
  openKpPanel(term?: string) {
    if (!this.selectedTender || this.lotSel.size === 0) return;
    const single = this.lotSel.size === 1;
    const lotId = single ? [...this.lotSel][0] : null;
    this.kpPanel = {
      loading: true, sending: false, entries: [], _relevant: [], _nonrel: [], _showNonrel: false,
      singleLot: single, detectedType: null, typeAlternatives: [], sourcingTerm: '', lotId,
    };
    this.cdr.detectChanges();
    this.api.getLotSourcing(this.selectedTender.id, [...this.lotSel], term).subscribe({
      next: (r) => {
        const entries = (r?.distributors || []).map((e: any) => ({ ...e, _checked: !!e.preselect }));
        this.kpPanel = {
          loading: false, sending: false, entries,
          _relevant: entries.filter((e: any) => e.relevant),
          _nonrel: entries.filter((e: any) => !e.relevant),
          _showNonrel: false,
          singleLot: !!r?.singleLot,
          detectedType: r?.detectedType || null,
          typeAlternatives: r?.typeAlternatives || [],
          sourcingTerm: r?.sourcingTerm || '',
          lotId,
        };
        this.cdr.detectChanges();
      },
      error: (e) => {
        this.kpPanel = null;
        this.notify.error('Ошибка подбора поставщиков: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  /** Сменить вид МИ лота из панели → сохранить и пересобрать подбор. */
  changeLotType(typeId: any) {
    const id = typeId === '' || typeId == null ? null : Number(typeId);
    if (!this.kpPanel?.lotId) return;
    const term = this.kpPanel.sourcingTerm || undefined;
    this.api.setLotEquipmentType(this.kpPanel.lotId, id).subscribe({
      next: () => { this.loadLots(); this.openKpPanel(term); },
      error: (e) => this.notify.error(e.error?.message || 'Ошибка сохранения типа'),
    });
  }

  /** Точечный поиск поставщика по введённому термину (Tier 2). */
  researchSupplier() { this.openKpPanel(this.kpPanel?.sourcingTerm || undefined); }
```

- [ ] **Step 4: Component — шаблон КП-панели**

Заменить содержимое `<ng-container *ngIf="!kpPanel.loading">` … `</ng-container>` (строки ~443-466) на:

```html
        <ng-container *ngIf="!kpPanel.loading">
          <div class="kp-controls" *ngIf="kpPanel.singleLot">
            <label>Вид МИ:
              <select [ngModel]="kpPanel.detectedType?.id ?? ''" (ngModelChange)="changeLotType($event)">
                <option value="">— не задан —</option>
                <option *ngFor="let t of equipmentTypesList" [value]="t.id">{{ t.name }}</option>
              </select>
            </label>
            <span class="kp-conf" *ngIf="kpPanel.detectedType && kpPanel.detectedType.confidence < 1">
              авто · {{ (kpPanel.detectedType.confidence * 100) | number:'1.0-0' }}%
            </span>
            <label class="kp-term">Поиск поставщика:
              <input type="text" [(ngModel)]="kpPanel.sourcingTerm" placeholder="бренд/аппарат"
                     (keyup.enter)="researchSupplier()">
            </label>
            <button class="btn btn-line" (click)="researchSupplier()">Найти</button>
          </div>

          <div class="empty" *ngIf="!kpPanel.entries.length">На этом рынке нет поставщиков — добавьте их в справочнике «Дистрибьюторы»</div>
          <div class="empty" *ngIf="kpPanel.entries.length && !kpPanel._relevant.length && !kpPanel.detectedType">
            Нужна техспецификация или вид МИ, чтобы подобрать по специализации.
          </div>

          <table *ngIf="kpPanel._relevant.length" class="kp-suppliers">
            <thead><tr><th class="w-36"></th><th>Поставщик</th><th>Email</th><th>Почему</th></tr></thead>
            <tbody>
              <tr *ngFor="let e of kpPanel._relevant; let i = index" [class.kp-hit]="e.preselect" [class.recommended]="i === 0">
                <td class="w-36"><input type="checkbox" [(ngModel)]="e._checked" /></td>
                <td>{{ e.distributor?.name }}<span *ngIf="!e.distributor?.equipmentTypes?.length" class="tag-all"> · все виды</span></td>
                <td>{{ e.distributor?.email || '—' }} <span class="no-email" *ngIf="!e.distributor?.email">письмо не уйдёт</span></td>
                <td>
                  <span class="reason-chip" *ngFor="let r of e.reasons"
                        [class.reason-type]="r.kind === 'TYPE'" [class.reason-brand]="r.kind === 'BRAND'">
                    {{ r.kind === 'TYPE' ? '✓' : 'возит' }} {{ r.label }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>

          <div class="kp-nonrel" *ngIf="kpPanel._nonrel.length">
            <button class="complect-zero-toggle" (click)="kpPanel._showNonrel = !kpPanel._showNonrel">
              {{ kpPanel._showNonrel ? '▴ скрыть нерелевантных' : '▾ ещё ' + kpPanel._nonrel.length + ' нерелевантных' }}
            </button>
            <table *ngIf="kpPanel._showNonrel" class="kp-suppliers">
              <tbody>
                <tr *ngFor="let e of kpPanel._nonrel">
                  <td class="w-36"><input type="checkbox" [(ngModel)]="e._checked" /></td>
                  <td>{{ e.distributor?.name }}</td>
                  <td>{{ e.distributor?.email || '—' }}</td>
                  <td></td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="kp-panel-actions" *ngIf="kpPanel.entries.length">
            <button class="btn btn-save" [disabled]="kpPanel.sending || checkedSuppliers().length === 0" (click)="sendKpRequests()">
              {{ kpPanel.sending ? 'Отправка…' : 'Отправить запросы (' + checkedSuppliers().length + ')' }}
            </button>
          </div>
        </ng-container>
```

Добавить стили в `styles:` (рядом с `.kp-suppliers`):

```css
    .kp-controls { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
    .kp-controls select, .kp-term input { padding: 5px 8px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
    .kp-conf { font-size: 12px; color: #6b7280; }
    .kp-suppliers tr.recommended td { background: #ecfdf5; }
    .reason-chip { display: inline-block; border-radius: 999px; padding: 2px 8px; font-size: 11px; margin: 0 3px 3px 0; }
    .reason-type { background: #dcfce7; color: #166534; }
    .reason-brand { background: #eef2ff; color: #3730a3; }
    .tag-all { color: #9ca3af; font-size: 11px; }
```

- [ ] **Step 5: Сборка фронта**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`.
Expected: BUILD SUCCESS без ошибок (бюджет `anyComponentStyle` 16 кБ).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts && git commit -m "feat(sourcing): КП-панель — селектор вида МИ + точечный поиск + релевантные сверху

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Живая проверка (Playwright) + CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (§8 блоки, §15 API, §16 бэклог)

- [ ] **Step 1: Поднять стек**

Backend: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew bootRun` (sandbox off, фон). Frontend: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm start` (фон). Дождаться :8080 и :4200.

- [ ] **Step 2: Playwright — сценарий по спеку §11**

Navigate `http://localhost:4200`, логин admin/admin, `localStorage.setItem('ais.market','KZ')`, перейти на `/tenders`. Открыть импортный KZ-тендер с лотом без бренда. Нажать «КП» в строке лота → снять снапшот:
- виден селектор «Вид МИ» с авто-значением + % уверенности;
- релевантные поставщики (по типу/бренду) — сверху, с чипами «✓<тип>»/«возит <бренд>»; нерелевантные — под «▾ ещё N нерелевантных».
Сменить «Вид МИ» в селекторе → список пересобрался (тип сохранился: перезагрузить страницу, повторно открыть — тип на месте).
Для аксессуарного лота (электроды/пластины) — поле «Поиск поставщика» предзаполнено брендом аппарата; «Найти» находит возящего его дистрибьютора.
Снять скриншот `sourcing-by-mi-type.png`.

- [ ] **Step 3: Обновить CLAUDE.md**

В §8 добавить пункт про подбор по виду МИ (LotTypeClassifier + equipment_type_synonym V7 + ранг typeHit⊕brandHit + Tier 2 ComplectTermExtractor; персист вида МИ ручкой; V8 реальные KZ-дистрибьюторы). В §15 добавить `POST /api/lots/{id}/equipment-type` и `?term=` у `lot-sourcing`. В §16: отметить сделанным «подбор по виду МИ»; добавить follow-up подблоки 2 (грамотная отправка) и 3 (разбор ответов); отметить, что дефект несохранения типа лота частично закрыт ручкой equipment-type.

- [ ] **Step 4: Commit + предложить финиш ветки**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md sourcing-by-mi-type.png && git commit -m "docs: CLAUDE.md — подбор поставщиков по виду МИ (V7/V8, классификатор, Tier 2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Затем — whole-branch review (superpowers:requesting-code-review) и предложить finishing-a-development-branch (мерж `--ff-only` в main, удалить ветку).

---

## Self-Review (выполнено при написании)

- **Покрытие спека:** §4 таксономия → Task 1/5; §5.1 словарь → Task 1; §5.3 данные → Task 5; §6 классификатор → Task 2; §7 персист → Task 3; §8.1 ранг → Task 4; §8.2 Tier 2 → Task 4 (`ComplectTermExtractor`, sourcingTerm, term-override); §9 API → Task 4 (response+term), Task 3 (endpoint); §10 фронт → Task 6; §11 тесты → Task 2/3/4 + Task 7 (Playwright); §12 миграции → Task 1 (V7), Task 5 (V8).
- **Отклонение от спека:** персист вида МИ — БЕЗ отдельного writer-бина (в спеке §7 упоминался): сети нет → чистый DB-write через `service.save` (эталон `setProposedEquipment`) корректнее и проще. Обучение словаря (§5.1/§7) реализовано минимально (`LotTypeClassifier.learn`, головной токен, best-effort, try/catch).
- **Плейсхолдеры:** V8 (Task 5) — единственный data-dependent шаг; это процедура ресёрча с точной схемой миграции, не заглушка (реальные строки заполняются из WebSearch на исполнении).
- **Согласованность типов:** `TypeGuess`(record, typeId/typeName/confidence/alternatives), `LotSourcingResponse.{DetectedType,TypeRef,Reason,Entry}`, `build(tenderId,lotIds,term)`, `setEquipmentType`, `setLotEquipmentType`, `classify`/`learn` — имена и сигнатуры едины между задачами 2/3/4/6.
