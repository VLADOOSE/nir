# Поиск по комплектности аппаратов — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Для «аксессуарных» лотов (электрод/пластина к аппарату) кнопка в панели «Реестр» находит родительский аппарат в НЦЭЛС, live-тянет его комплектность и показывает подходящий компонент с РУ/производителем/страной, с «Взять в работу» до предложенной модели лота.

**Architecture:** Кнопка → `ComplectService.search(lotId, term)`: `ComplectTermExtractor` вынимает бренд из лота → `MedRegistryRepository.findApparatusByTerm` находит аппараты «(МТ)» → `NddaClient.resolveId`+`fetchComplectList` (live, вне транзакции) → `ComplectWriter` кеширует компоненты в `registry_component` (V6, отдельный @Transactional-бин) → `ComplectComponentMatcher` ранжирует компоненты по токенам лота. `ComplectService.adoptComponent(lotId, regNumber, partNumber)` создаёт позицию каталога с именем компонента + производителем компонента + РУ аппарата и ставит предложенной моделью.

**Tech Stack:** Java 17 / Spring Boot 3.5.6, `java.net.http.HttpClient`, Jackson, Flyway (V6), pg_trgm (`word_similarity`/`<%`), JUnit 5 + AssertJ + JDK HttpServer-стаб + `@MockitoBean`, Angular 21 (инлайн `tenders.component.ts`).

**Spec:** `docs/superpowers/specs/2026-07-06-registry-complectness-search-design.md`

## Global Constraints

- Все `./gradlew`/psql — Bash `dangerouslyDisableSandbox: true` (sandbox блокирует :5432); из корня репо (`cd /Users/vlad/IdeaProjects/AIS && …`).
- Перед `./gradlew test` (полным): `lsof -ti :8080 | xargs kill -9` (может никого не убить — ок).
- Гейт «зелёного»: падают ТОЛЬКО 2 пред-существующих `ApplyAutoFillServiceTest`.
- Каждый commit заканчивать: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Ветка `feat/registry-complectness-search` (создаётся в Task 1, Step 0).
- Схему менять ТОЛЬКО новой миграцией V6 (V1–V5 не трогать).
- `med_registry`/`registry_component` — общие (без market): рыночные фильтр/штамп не участвуют; рыночный гард — только в `adoptComponent` (через лот, паттерн `adoptForLot`).
- Сеть — ВНЕ транзакции; запись кеша — в @Transactional-методе ОТДЕЛЬНОГО бина (`ComplectWriter`, паттерн §6 CLAUDE.md / TechSpecWriter).
- После крупных Edit в `tenders.component.ts` — `grep -c` на дубли вставленных методов + `npm run build`.
- Базовый URL НЦЭЛС берётся из уже существующего конфига `ndda.api.base-url` (default `https://oldregister.ndda.kz/register-backend`); в тестах клиент конструируется с URL стаба.

---

### Task 1: `NddaClient.fetchComplectList` + DTO

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/ndda/dto/NddaComplectItemDto.java`
- Modify: `src/main/java/com/vladoose/nir/integration/ndda/NddaClient.java`
- Modify: `src/main/java/com/vladoose/nir/integration/ndda/NddaHttpClient.java`
- Test: `src/test/java/com/vladoose/nir/integration/ndda/NddaHttpClientTest.java` (добавить тест в существующий класс)

**Interfaces:**
- Consumes: существующий `send(HttpRequest)`, `UpstreamException`, `baseUrl`.
- Produces: `NddaClient.fetchComplectList(long nddaId) : List<NddaComplectItemDto>`; `NddaComplectItemDto` с геттерами `getProductName()/getComponent()/getProducerName()/getCountryName()/getPartNumber()`. На них опираются Task 6/7.

- [ ] **Step 0: Ветка**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout -b feat/registry-complectness-search
```

- [ ] **Step 1: Failing-тест** (в конец класса `NddaHttpClientTest`, перед закрывающей `}`)

```java
    @Test
    void fetchComplectList_getsMtComplectList_andParsesComponents() {
        // живая форма 2026-07-06 (registerId 178624, аппарат ЭЛЭСКУЛАП)
        nextBody = """
            [{"registerId":178624,"productName":"1.Электронный блок – 1 шт.;","component":"основной блок",
              "producerName":"ООО «Мед ТеКо»","countryName":"Россия","partNumber":1},
             {"registerId":178624,"productName":"4.Электроды силиконовые электропроводящие, мм:\\n- 55 х 80 – 2 шт.;",
              "component":"комплектующие","producerName":"ООО «Мед ТеКо»","countryName":"Россия","partNumber":4}]
            """;
        var items = client.fetchComplectList(178624L);
        assertThat(lastPath).isEqualTo("/register-backend/RegisterService/MtComplectList");
        assertThat(lastQuery).isEqualTo("registerId=178624");
        assertThat(items).hasSize(2);
        assertThat(items.get(1).getPartNumber()).isEqualTo(4);
        assertThat(items.get(1).getProductName()).contains("силиконовые");
        assertThat(items.get(1).getProducerName()).isEqualTo("ООО «Мед ТеКо»");
        assertThat(items.get(1).getCountryName()).isEqualTo("Россия");
    }
```

- [ ] **Step 2: RED**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava 2>&1 | tail -5
```
(с `dangerouslyDisableSandbox: true`) Expected: `BUILD FAILED` — `cannot find symbol: method fetchComplectList` / `NddaComplectItemDto`.

- [ ] **Step 3: DTO**

Создать `src/main/java/com/vladoose/nir/integration/ndda/dto/NddaComplectItemDto.java`:

```java
package com.vladoose.nir.integration.ndda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Элемент комплектности МИ (RegisterService/MtComplectList) — живая форма закреплена NddaHttpClientTest. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NddaComplectItemDto {
    private String productName;
    private String component;
    private String producerName;
    private String countryName;
    private Integer partNumber;
}
```

- [ ] **Step 4: Интерфейс + реализация**

В `NddaClient.java` добавить импорт `import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;`, `import java.util.List;` и метод:

```java
    /** Комплектность (состав) аппарата по внутреннему id: список компонентов с производителем/страной. */
    List<NddaComplectItemDto> fetchComplectList(long nddaId);
```

В `NddaHttpClient.java` добавить импорт `import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;` и метод (рядом с `fetchDetail`):

```java
    @Override
    public List<NddaComplectItemDto> fetchComplectList(long nddaId) {
        String json = send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/RegisterService/MtComplectList?registerId=" + nddaId))
                .GET().timeout(Duration.ofSeconds(30)).build());
        try {
            return objectMapper.readValue(json, new TypeReference<List<NddaComplectItemDto>>() {});
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС: неожиданный ответ комплектности: " + e.getMessage(), e);
        }
    }
```

- [ ] **Step 5: GREEN**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests "com.vladoose.nir.integration.ndda.NddaHttpClientTest" 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`, 6/6.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/ndda src/test/java/com/vladoose/nir/integration/ndda && git commit -m "feat(ndda): fetchComplectList — состав аппарата (MtComplectList)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Flyway V6 `registry_component` + entity + repository

**Files:**
- Create: `src/main/resources/db/migration/V6__registry_component.sql`
- Create: `src/main/java/com/vladoose/nir/entity/RegistryComponent.java`
- Create: `src/main/java/com/vladoose/nir/repository/RegistryComponentRepository.java`

**Interfaces:**
- Produces: таблица/entity `RegistryComponent` (поля `id/regNumber/partNumber/productName/component/producer/country/fetchedAt`); `RegistryComponentRepository.findByRegNumberOrderByPartNumber(String)`, `findByRegNumberAndPartNumber(String, Integer)`, `deleteByRegNumber(String)`. Опора Task 6/7.

- [ ] **Step 1: Миграция V6**

Создать `src/main/resources/db/migration/V6__registry_component.sql`:

```sql
-- Кеш комплектности (состава) аппаратов НЦЭЛС. Наполняется on-demand при поиске по комплектности
-- (кнопка в панели «Реестр»); reg_number ссылается на аппарат в med_registry. Общая таблица (без market).
CREATE TABLE IF NOT EXISTS registry_component (
    id           BIGSERIAL PRIMARY KEY,
    reg_number   TEXT NOT NULL,
    part_number  INT,
    product_name TEXT,
    component    TEXT,
    producer     TEXT,
    country      TEXT,
    fetched_at   TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_registry_component UNIQUE (reg_number, part_number)
);
CREATE INDEX IF NOT EXISTS idx_registry_component_reg ON registry_component(reg_number);
```

- [ ] **Step 2: Entity**

Создать `src/main/java/com/vladoose/nir/entity/RegistryComponent.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Кеш одной строки комплектности аппарата НЦЭЛС (см. ComplectService). Общая сущность (без market). */
@Entity
@Table(name = "registry_component")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistryComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reg_number", nullable = false)
    private String regNumber;

    @Column(name = "part_number")
    private Integer partNumber;

    @Column(name = "product_name", columnDefinition = "TEXT")
    private String productName;

    @Column(columnDefinition = "TEXT")
    private String component;

    @Column(columnDefinition = "TEXT")
    private String producer;

    @Column(columnDefinition = "TEXT")
    private String country;

    @Column(name = "fetched_at")
    private OffsetDateTime fetchedAt;
}
```

- [ ] **Step 3: Репозиторий**

Создать `src/main/java/com/vladoose/nir/repository/RegistryComponentRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.RegistryComponent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegistryComponentRepository extends JpaRepository<RegistryComponent, Long> {

    List<RegistryComponent> findByRegNumberOrderByPartNumber(String regNumber);

    Optional<RegistryComponent> findByRegNumberAndPartNumber(String regNumber, Integer partNumber);

    void deleteByRegNumber(String regNumber);
}
```

- [ ] **Step 4: Компиляция + миграция накатывается**

```bash
cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9; ./gradlew test --tests "com.vladoose.nir.service.RegistryImportServiceTest" 2>&1 | tail -12
```
Expected: `BUILD SUCCESSFUL` (Spring-контекст поднялся → Flyway накатил V6 на nirdb; entity мапится).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V6__registry_component.sql src/main/java/com/vladoose/nir/entity/RegistryComponent.java src/main/java/com/vladoose/nir/repository/RegistryComponentRepository.java && git commit -m "feat(registry): V6 registry_component — кеш комплектности аппаратов

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `MedRegistryRepository.findApparatusByTerm` (поиск аппаратов «(МТ)» по бренду)

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/ApparatusRow.java` (проекция)
- Modify: `src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java`
- Test: `src/test/java/com/vladoose/nir/repository/ApparatusSearchTest.java`

**Interfaces:**
- Consumes: pg_trgm `<%`/`word_similarity`, колонка `med_registry.ndda_id` (V5).
- Produces: `MedRegistryRepository.findApparatusByTerm(String term, int limit) : List<ApparatusRow>`; `ApparatusRow` (проекция) с `getRegNumber()/getName()/getProducer()/getCountry()/getNddaId()`. Опора Task 6.

- [ ] **Step 1: Failing-тест**

Создать `src/test/java/com/vladoose/nir/repository/ApparatusSearchTest.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.ApparatusRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Поиск аппаратов «(МТ)» по бренду из ТЗ — на живом реестре nirdb (ЭЛЭСКУЛАП зарегистрирован). */
@SpringBootTest
@Transactional
class ApparatusSearchTest {

    @Autowired MedRegistryRepository repo;

    @Test
    void findsElesculapApparatusByBrandTerm() {
        List<ApparatusRow> rows = repo.findApparatusByTerm("элэскулап", 3);
        assertThat(rows).isNotEmpty();
        assertThat(rows).anyMatch(r -> r.getRegNumber().contains("(МТ)")
                && r.getName().toLowerCase().contains("элэскулап"));
    }

    @Test
    void nonApparatusTerm_returnsEmpty() {
        // заведомо мусорный бренд-термин не должен тянуть аппараты
        assertThat(repo.findApparatusByTerm("zzzнеттакогобренда", 3)).isEmpty();
    }
}
```

- [ ] **Step 2: RED**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava 2>&1 | tail -5
```
Expected: `BUILD FAILED` — `ApparatusRow` / `findApparatusByTerm` не существуют.

- [ ] **Step 3: Проекция**

Создать `src/main/java/com/vladoose/nir/dto/response/ApparatusRow.java`:

```java
package com.vladoose.nir.dto.response;

/** Проекция аппарата-кандидата из med_registry (native query findApparatusByTerm). */
public interface ApparatusRow {
    String getRegNumber();
    String getName();
    String getProducer();
    String getCountry();
    Long getNddaId();
}
```

- [ ] **Step 4: Метод репозитория**

В `MedRegistryRepository.java` добавить (импорты `List`/`Query`/`Param` уже есть; добавить `import com.vladoose.nir.dto.response.ApparatusRow;`):

```java
    /**
     * Аппараты-кандидаты по бренду из ТЗ: только записи типа «(МТ)» (аппаратура, у них есть комплектность),
     * индексо-дружелюбный `<%` (word_similarity ≥ глобального порога), ранг по word_similarity термина к имени.
     */
    @Query(nativeQuery = true, value =
            "SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, " +
            "       m.country AS country, m.ndda_id AS nddaId " +
            "FROM med_registry m " +
            "WHERE m.reg_number LIKE '%(МТ)%' AND :term <% m.name " +
            "ORDER BY word_similarity(:term, m.name) DESC " +
            "LIMIT :limit")
    List<ApparatusRow> findApparatusByTerm(@Param("term") String term, @Param("limit") int limit);
```

- [ ] **Step 5: GREEN**

```bash
cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9; ./gradlew test --tests "com.vladoose.nir.repository.ApparatusSearchTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, 2/2.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/response/ApparatusRow.java src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java src/test/java/com/vladoose/nir/repository/ApparatusSearchTest.java && git commit -m "feat(registry): findApparatusByTerm — аппараты «(МТ)» по бренду из ТЗ

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: `ComplectTermExtractor` (бренд из лота)

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/ComplectTermExtractor.java`
- Test: `src/test/java/com/vladoose/nir/util/ComplectTermExtractorTest.java`

**Interfaces:**
- Produces: `ComplectTermExtractor.extract(String equipName, String spec) : String` (nullable — не нашли). Опора Task 6.

- [ ] **Step 1: Failing-тест**

Создать `src/test/java/com/vladoose/nir/util/ComplectTermExtractorTest.java`:

```java
package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComplectTermExtractorTest {

    @Test
    void extractsQuotedApparatusBrand() {
        String term = ComplectTermExtractor.extract("Электрод",
                "Резиновые пластинки для аппарата электрофореза \"Элэскулап\", размеры 55*80 мм");
        assertThat(term).isEqualTo("Элэскулап");
    }

    @Test
    void extractsBrandAfterApparatusKeyword_whenNotQuoted() {
        String term = ComplectTermExtractor.extract("Электрод",
                "электроды для аппарата Элэскулап 55х80");
        assertThat(term).isEqualTo("Элэскулап");
    }

    @Test
    void pureGenericText_returnsNull() {
        String term = ComplectTermExtractor.extract("Электрод",
                "электроды силиконовые электропроводящие, размеры 55*80 мм");
        assertThat(term).isNull();
    }
}
```

- [ ] **Step 2: RED**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava 2>&1 | tail -5
```
Expected: `BUILD FAILED` — `ComplectTermExtractor` не существует.

- [ ] **Step 3: Реализация**

Создать `src/main/java/com/vladoose/nir/util/ComplectTermExtractor.java`:

```java
package com.vladoose.nir.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вынимает имя родительского аппарата (бренд) из названия+ТЗ аксессуарного лота — для поиска
 * по комплектности. В отличие от матчинга компонентов здесь generic-слова изделия (электрод,
 * силиконовый, размер…) выбрасываются, чтобы остался отличительный проприетарный токен («Элэскулап»).
 */
public final class ComplectTermExtractor {

    // закавыченная фраза в любых кавычках — сильнейший кандидат на имя аппарата
    private static final Pattern QUOTED = Pattern.compile("[«\"„']([\\p{L}\\p{Nd} .\\-]{3,40}?)[»\"'']");
    // «(для|к) аппарат.../систем... <Бренд>» → следующее слово с заглавной
    private static final Pattern AFTER_KW = Pattern.compile(
            "(?:аппарат\\p{L}*|систем\\p{L}*|прибор\\p{L}*)\\s+([\\p{Lu}][\\p{L}\\-]{2,})");

    // generic-слова изделия и канцелярит: не могут быть именем аппарата
    private static final Set<String> GENERIC = Set.of(
            "электрод", "электроды", "пластина", "пластины", "пластинка", "пластинки",
            "резиновый", "резиновые", "резиновая", "силиконовый", "силиконовые", "силиконовая",
            "электропроводящий", "электропроводящие", "токопроводящий", "токопроводящие",
            "терапевтический", "терапевтические", "размер", "размеры", "электрофорез", "электрофореза",
            "аппарат", "аппарата", "аппаратный", "система", "системы", "прибор", "прибора",
            "медицинский", "медицинская", "изделие", "изделия", "комплект", "набор",
            "для", "к", "и", "или", "с", "со", "по", "на", "мм", "см");

    private ComplectTermExtractor() {}

    public static String extract(String equipName, String spec) {
        String text = (equipName == null ? "" : equipName) + " " + (spec == null ? "" : spec);
        if (text.isBlank()) return null;

        Matcher q = QUOTED.matcher(text);
        if (q.find()) {
            String cand = q.group(1).trim();
            if (isDistinctive(cand)) return cand;
        }
        Matcher kw = AFTER_KW.matcher(text);
        if (kw.find()) {
            String cand = kw.group(1).trim();
            if (isDistinctive(cand)) return cand;
        }
        // иначе — самый длинный отличительный (не-generic) токен с буквами
        String best = null;
        for (String raw : text.split("[^\\p{L}\\-]+")) {
            String t = raw.replaceAll("^-+|-+$", "");
            if (t.length() < 4) continue;
            if (GENERIC.contains(t.toLowerCase())) continue;
            if (best == null || t.length() > best.length()) best = t;
        }
        return best;
    }

    private static boolean isDistinctive(String s) {
        for (String w : s.toLowerCase().split("\\s+")) {
            if (!GENERIC.contains(w) && w.length() >= 3) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: GREEN**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests "com.vladoose.nir.util.ComplectTermExtractorTest" 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`, 3/3.

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/util/ComplectTermExtractor.java src/test/java/com/vladoose/nir/util/ComplectTermExtractorTest.java && git commit -m "feat(registry): ComplectTermExtractor — бренд аппарата из ТЗ лота

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: `ComplectComponentMatcher` (ранжирование компонентов)

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/ComplectComponentMatcher.java`
- Test: `src/test/java/com/vladoose/nir/util/ComplectComponentMatcherTest.java`

**Interfaces:**
- Produces: `ComplectComponentMatcher.tokenize(String lotText) : java.util.Set<String>`; `ComplectComponentMatcher.score(java.util.Set<String> lotTokens, String componentText) : double`. Опора Task 6.

- [ ] **Step 1: Failing-тест**

Создать `src/test/java/com/vladoose/nir/util/ComplectComponentMatcherTest.java`:

```java
package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ComplectComponentMatcherTest {

    @Test
    void siliconeElectrode55x80_ranksAboveTherapeutic() {
        Set<String> lot = ComplectComponentMatcher.tokenize("Электрод электроды силиконовые 55*80 мм");
        double silicone = ComplectComponentMatcher.score(lot,
                "4.Электроды силиконовые электропроводящие, мм: - 25 х 30; - 55 х 80; - 100 х 120;");
        double therapeutic = ComplectComponentMatcher.score(lot,
                "2.Электроды токопроводящие терапевтические: - 40 х 50; - 90 х 140;");
        assertThat(silicone).isGreaterThan(therapeutic);
        assertThat(silicone).isGreaterThan(0.0);
    }

    @Test
    void tokenizeDropsNoiseKeepsDigitsAndWords() {
        Set<String> t = ComplectComponentMatcher.tokenize("Электрод силиконовый 55 мм для");
        assertThat(t).contains("55", "силиконовый").doesNotContain("мм", "для");
    }
}
```

- [ ] **Step 2: RED**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava 2>&1 | tail -5
```
Expected: `BUILD FAILED` — `ComplectComponentMatcher` не существует.

- [ ] **Step 3: Реализация**

Создать `src/main/java/com/vladoose/nir/util/ComplectComponentMatcher.java`:

```java
package com.vladoose.nir.util;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ранжирует компоненты комплектности аппарата против айтема лота: доля токенов лота (слова изделия
 * + размеры «55»/«80» — здесь это дискриминаторы), встречающихся в тексте компонента. Чистый Java —
 * компонентов у аппарата ≤~15, БД не нужна. Абсолютное значение — не метрика, важен относительный ранг.
 */
public final class ComplectComponentMatcher {

    // мусорные короткие слова единиц/предлогов; цифры и слова-изделия сохраняем
    private static final Set<String> NOISE = Set.of("мм", "см", "шт", "для", "или", "по", "на", "штук");

    private ComplectComponentMatcher() {}

    public static Set<String> tokenize(String lotText) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (lotText == null) return out;
        for (String raw : lotText.toLowerCase().split("[^\\p{L}\\p{Nd}]+")) {
            if (raw.length() < 2) continue;              // одиночные буквы/цифры-шум
            if (NOISE.contains(raw)) continue;
            out.add(raw);
        }
        return out;
    }

    /** Доля токенов лота, встречающихся подстрокой в тексте компонента (0..1). */
    public static double score(Set<String> lotTokens, String componentText) {
        if (lotTokens.isEmpty() || componentText == null) return 0.0;
        String hay = componentText.toLowerCase();
        long hit = lotTokens.stream().filter(hay::contains).count();
        return (double) hit / lotTokens.size();
    }
}
```

- [ ] **Step 4: GREEN**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests "com.vladoose.nir.util.ComplectComponentMatcherTest" 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`, 2/2.

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/util/ComplectComponentMatcher.java src/test/java/com/vladoose/nir/util/ComplectComponentMatcherTest.java && git commit -m "feat(registry): ComplectComponentMatcher — ранжирование компонентов по токенам лота

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: `ComplectService.search` + `ComplectWriter` + DTO + endpoint

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/ComplectSearchResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/ComplectWriter.java`
- Create: `src/main/java/com/vladoose/nir/service/ComplectService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderLotController.java`
- Test: `src/test/java/com/vladoose/nir/service/ComplectServiceTest.java`

**Interfaces:**
- Consumes: Task 1 (`NddaClient.fetchComplectList`, `resolveId`), Task 2 (`RegistryComponentRepository`, `RegistryComponent`), Task 3 (`findApparatusByTerm`, `ApparatusRow`), Task 4 (`ComplectTermExtractor`), Task 5 (`ComplectComponentMatcher`), `TenderLotService.findById`, `MarketContext`, `NotFoundException`.
- Produces: `ComplectService.search(Long lotId, String termOverride) : ComplectSearchResponse`; `ComplectWriter.cache(String regNumber, Long nddaId, List<NddaComplectItemDto> items) : void`; REST `POST /api/lots/{id}/complect-search?term=…`. Опора Task 7 (endpoint соседство), Task 8 (фронт).

- [ ] **Step 1: Failing-тест**

Создать `src/test/java/com/vladoose/nir/service/ComplectServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ComplectSearchResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.RegistryComponentRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class ComplectServiceTest {

    @Autowired ComplectService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired RegistryComponentRepository componentRepository;
    @MockitoBean NddaClient nddaClient;

    MedRegistry apparatus;
    Long lotId;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        apparatus = registryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РК МИ (МТ)-COMPLECT-1")
                .name("ZZ Аппарат электротерапии ЭЛЭСКУЛАПZZ Мед ТеКо")
                .producer("Мед ТеКо").country("РОССИЯ").build());
        Tender t = new Tender();
        t.setTenderNumber("ZZ-COMPLECT-" + System.nanoTime());
        t.setStatus("ACTIVE");
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName("Электрод");
        l.setRequiredSpec("Резиновые пластинки для аппарата электрофореза \"ЭЛЭСКУЛАПZZ\", силиконовые 55*80 мм");
        t.getLots().add(l);
        tenderRepository.saveAndFlush(t);
        lotId = t.getLots().get(0).getId();
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    private List<NddaComplectItemDto> components() {
        NddaComplectItemDto a = new NddaComplectItemDto();
        a.setPartNumber(2); a.setComponent("комплектующие");
        a.setProductName("2.Электроды токопроводящие терапевтические: 40 х 50; 90 х 140;");
        a.setProducerName("ООО «Мед ТеКо»"); a.setCountryName("Россия");
        NddaComplectItemDto b = new NddaComplectItemDto();
        b.setPartNumber(4); b.setComponent("комплектующие");
        b.setProductName("4.Электроды силиконовые электропроводящие, мм: 25 х 30; 55 х 80; 100 х 120;");
        b.setProducerName("ООО «Мед ТеКо»"); b.setCountryName("Россия");
        return List.of(a, b);
    }

    @Test
    void search_findsApparatus_fetchesComplect_ranksSiliconeFirst() {
        when(nddaClient.resolveId("ZZ-РК МИ (МТ)-COMPLECT-1")).thenReturn(178624L);
        when(nddaClient.fetchComplectList(178624L)).thenReturn(components());

        ComplectSearchResponse r = service.search(lotId, null);

        assertThat(r.getTerm()).isEqualTo("ЭЛЭСКУЛАПZZ");
        assertThat(r.getApparatuses()).isNotEmpty();
        var comps = r.getApparatuses().get(0).getComponents();
        assertThat(comps.get(0).getPartNumber()).isEqualTo(4);            // силиконовый 55×80 — первым
        assertThat(comps.get(0).getScore()).isGreaterThan(comps.get(1).getScore());
        assertThat(comps.get(0).getCountry()).isEqualTo("Россия");
        // компоненты закешированы
        assertThat(componentRepository.findByRegNumberOrderByPartNumber("ZZ-РК МИ (МТ)-COMPLECT-1")).hasSize(2);
    }

    @Test
    void search_secondCall_servedFromCache_withoutFetch() {
        when(nddaClient.resolveId(anyString())).thenReturn(178624L);
        when(nddaClient.fetchComplectList(anyLong())).thenReturn(components());

        service.search(lotId, null);
        service.search(lotId, null);

        verify(nddaClient, times(1)).fetchComplectList(anyLong()); // второй раз — из кеша
    }

    @Test
    void search_emptyTerm_noApparatus_noNetwork() {
        ComplectSearchResponse r = service.search(lotId, "zzzнетбренда");
        assertThat(r.getApparatuses()).isEmpty();
        verify(nddaClient, never()).fetchComplectList(anyLong());
    }
}
```

- [ ] **Step 2: RED**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava 2>&1 | tail -5
```
Expected: `BUILD FAILED` — `ComplectService`/`ComplectSearchResponse` не существуют.

- [ ] **Step 3: DTO ответа**

Создать `src/main/java/com/vladoose/nir/dto/response/ComplectSearchResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

/** Результат поиска по комплектности аппаратов: термин + аппараты с подходящими компонентами. */
@Data
public class ComplectSearchResponse {
    private String term;
    private List<ApparatusMatch> apparatuses;

    @Data
    public static class ApparatusMatch {
        private String regNumber;
        private String name;
        private String producer;
        private String country;
        private List<ComponentMatch> components;
    }

    @Data
    public static class ComponentMatch {
        private Integer partNumber;
        private String productName;
        private String component;
        private String producer;
        private String country;
        private Double score;
    }
}
```

- [ ] **Step 4: Writer (кеш в отдельной транзакции)**

Создать `src/main/java/com/vladoose/nir/service/ComplectWriter.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.RegistryComponent;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.RegistryComponentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Запись кеша комплектности отдельным транзакционным бином (сеть — вне транзакции, §6). */
@Service
public class ComplectWriter {

    private final RegistryComponentRepository componentRepository;
    private final MedRegistryRepository registryRepository;

    public ComplectWriter(RegistryComponentRepository componentRepository,
                          MedRegistryRepository registryRepository) {
        this.componentRepository = componentRepository;
        this.registryRepository = registryRepository;
    }

    /** Перезаписывает комплектность аппарата и бэкфилит ndda_id (чтобы не резолвить повторно). */
    @Transactional
    public void cache(String regNumber, Long nddaId, List<NddaComplectItemDto> items) {
        componentRepository.deleteByRegNumber(regNumber);
        for (NddaComplectItemDto it : items) {
            componentRepository.save(RegistryComponent.builder()
                    .regNumber(regNumber)
                    .partNumber(it.getPartNumber())
                    .productName(it.getProductName())
                    .component(it.getComponent())
                    .producer(it.getProducerName())
                    .country(it.getCountryName())
                    .fetchedAt(OffsetDateTime.now())
                    .build());
        }
        registryRepository.findByRegNumber(regNumber).ifPresent(reg -> {
            if (reg.getNddaId() == null) { reg.setNddaId(nddaId); registryRepository.save(reg); }
        });
    }
}
```

- [ ] **Step 5: Сервис**

Создать `src/main/java/com/vladoose/nir/service/ComplectService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ApparatusRow;
import com.vladoose.nir.dto.response.ComplectSearchResponse;
import com.vladoose.nir.dto.response.ComplectSearchResponse.ApparatusMatch;
import com.vladoose.nir.dto.response.ComplectSearchResponse.ComponentMatch;
import com.vladoose.nir.entity.RegistryComponent;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.RegistryComponentRepository;
import com.vladoose.nir.util.ComplectComponentMatcher;
import com.vladoose.nir.util.ComplectTermExtractor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Поиск по комплектности аппаратов: бренд из ТЗ → аппараты «(МТ)» → live-комплектность → ранжирование
 * компонентов. Сеть ВНЕ транзакции; кеш — ComplectWriter (отдельный @Transactional-бин, §6).
 */
@Service
public class ComplectService {

    private static final int MAX_APPARATUS = 3;

    private final TenderLotService lotService;
    private final MedRegistryRepository registryRepository;
    private final RegistryComponentRepository componentRepository;
    private final NddaClient nddaClient;
    private final ComplectWriter writer;

    public ComplectService(TenderLotService lotService,
                           MedRegistryRepository registryRepository,
                           RegistryComponentRepository componentRepository,
                           NddaClient nddaClient,
                           ComplectWriter writer) {
        this.lotService = lotService;
        this.registryRepository = registryRepository;
        this.componentRepository = componentRepository;
        this.nddaClient = nddaClient;
        this.writer = writer;
    }

    public ComplectSearchResponse search(Long lotId, String termOverride) {
        TenderLot lot = lotService.findById(lotId);
        // em.find обходит фильтр рынка → явный гард (паттекн adopt/proposed-equipment)
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }

        String term = (termOverride != null && !termOverride.isBlank())
                ? termOverride.trim()
                : ComplectTermExtractor.extract(lot.getEquipName(),
                    lot.getRequiredSpec() != null ? lot.getRequiredSpec() : lot.getManufact());

        ComplectSearchResponse resp = new ComplectSearchResponse();
        resp.setTerm(term);
        resp.setApparatuses(new ArrayList<>());
        if (term == null || term.isBlank()) return resp;

        Set<String> lotTokens = ComplectComponentMatcher.tokenize(
                lot.getEquipName() + " " + (lot.getRequiredSpec() != null ? lot.getRequiredSpec() : ""));

        for (ApparatusRow row : registryRepository.findApparatusByTerm(term, MAX_APPARATUS)) {
            List<RegistryComponent> cached = componentRepository.findByRegNumberOrderByPartNumber(row.getRegNumber());
            if (cached.isEmpty()) {
                Long nddaId = row.getNddaId() != null ? row.getNddaId() : nddaClient.resolveId(row.getRegNumber());
                if (nddaId == null) continue;                              // на портале не найден — пропускаем
                List<NddaComplectItemDto> items = nddaClient.fetchComplectList(nddaId); // сеть вне tx
                if (items.isEmpty()) continue;
                writer.cache(row.getRegNumber(), nddaId, items);           // кеш в отдельной tx
                cached = componentRepository.findByRegNumberOrderByPartNumber(row.getRegNumber());
            }
            resp.getApparatuses().add(toMatch(row, cached, lotTokens));
        }
        return resp;
    }

    private ApparatusMatch toMatch(ApparatusRow row, List<RegistryComponent> cached, Set<String> lotTokens) {
        ApparatusMatch am = new ApparatusMatch();
        am.setRegNumber(row.getRegNumber());
        am.setName(row.getName());
        am.setProducer(row.getProducer());
        am.setCountry(row.getCountry());
        List<ComponentMatch> comps = new ArrayList<>();
        for (RegistryComponent c : cached) {
            ComponentMatch cm = new ComponentMatch();
            cm.setPartNumber(c.getPartNumber());
            cm.setProductName(c.getProductName());
            cm.setComponent(c.getComponent());
            cm.setProducer(c.getProducer());
            cm.setCountry(c.getCountry());
            cm.setScore(ComplectComponentMatcher.score(lotTokens, c.getProductName()));
            comps.add(cm);
        }
        comps.sort(Comparator.comparingDouble(ComponentMatch::getScore).reversed()); // лучший компонент сверху
        am.setComponents(comps);
        return am;
    }
}
```

- [ ] **Step 6: Endpoint**

В `TenderLotController.java` добавить импорт `import com.vladoose.nir.dto.response.ComplectSearchResponse;` и `import com.vladoose.nir.service.ComplectService;`, поле `private final ComplectService complectService;`, расширить конструктор седьмым параметром `ComplectService complectService` (+ присвоение `this.complectService = complectService;`), и метод (рядом с `registryCandidates`):

```java
    /** Поиск по комплектности аппаратов: бренд из ТЗ → аппарат → компоненты (аксессуарные лоты). */
    @PostMapping("/{id}/complect-search")
    public ComplectSearchResponse complectSearch(@PathVariable Long id,
                                                 @RequestParam(required = false) String term) {
        return complectService.search(id, term);
    }
```

- [ ] **Step 7: GREEN**

```bash
cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9; ./gradlew test --tests "com.vladoose.nir.service.ComplectServiceTest" 2>&1 | tail -12
```
Expected: `BUILD SUCCESSFUL`, 3/3.

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/response/ComplectSearchResponse.java src/main/java/com/vladoose/nir/service/ComplectWriter.java src/main/java/com/vladoose/nir/service/ComplectService.java src/main/java/com/vladoose/nir/controller/TenderLotController.java src/test/java/com/vladoose/nir/service/ComplectServiceTest.java && git commit -m "feat(registry): ComplectService — поиск по комплектности аппаратов + POST /complect-search

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: component-adopt (компонент → позиция каталога → предложенная модель)

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/AdoptComponentRequest.java`
- Modify: `src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/ComplectService.java` (метод `adoptComponent`)
- Modify: `src/main/java/com/vladoose/nir/controller/TenderLotController.java` (endpoint)
- Test: `src/test/java/com/vladoose/nir/service/ComplectAdoptTest.java`

**Interfaces:**
- Consumes: Task 2 (`RegistryComponentRepository.findByRegNumberAndPartNumber`), Task 6 (`ComplectService`), `MedRegistryRepository.findByRegNumber`, `TenderLotRepository`, `MedEquipmentRepository`.
- Produces: `ComplectService.adoptComponent(Long lotId, String regNumber, Integer partNumber) : TenderLot`; `MedEquipmentRepository.findFirstByRegistrationRegNumberAndNameIgnoreCase(String, String)`; REST `POST /api/lots/{id}/adopt-component`.

- [ ] **Step 1: Failing-тест**

Создать `src/test/java/com/vladoose/nir/service/ComplectAdoptTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ComplectAdoptTest {

    @Autowired ComplectService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired RegistryComponentRepository componentRepository;
    @Autowired MedEquipmentRepository equipmentRepository;

    MedRegistry apparatus;
    Long lotId;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        apparatus = registryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РК МИ (МТ)-ADOPTC-1").name("ZZ Аппарат ЭЛЭСКУЛАПZZ").producer("Мед ТеКо")
                .country("РОССИЯ").build());
        componentRepository.saveAndFlush(RegistryComponent.builder()
                .regNumber("ZZ-РК МИ (МТ)-ADOPTC-1").partNumber(4)
                .productName("4.Электроды силиконовые электропроводящие 55 х 80")
                .component("комплектующие").producer("ООО «Мед ТеКо»").country("Россия")
                .fetchedAt(OffsetDateTime.now()).build());
        Tender t = new Tender();
        t.setTenderNumber("ZZ-ADOPTC-" + System.nanoTime());
        t.setStatus("ACTIVE");
        TenderLot l = new TenderLot();
        l.setTender(t); l.setEquipName("Электрод");
        t.getLots().add(l);
        tenderRepository.saveAndFlush(t);
        lotId = t.getLots().get(0).getId();
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    @Test
    void adoptComponent_createsCatalogItem_withComponentNameAndApparatusReg() {
        TenderLot lot = service.adoptComponent(lotId, "ZZ-РК МИ (МТ)-ADOPTC-1", 4);

        MedEquipment eq = lot.getProposedEquipment();
        assertThat(eq).isNotNull();
        assertThat(eq.getName()).contains("силиконовые");                 // имя из компонента, не из аппарата
        assertThat(eq.getManufact()).isEqualTo("ООО «Мед ТеКо»");         // производитель компонента
        assertThat(eq.getRegistration().getRegNumber()).isEqualTo("ZZ-РК МИ (МТ)-ADOPTC-1"); // РУ аппарата
        assertThat(eq.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(eq.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void adoptComponent_twice_reusesSameCatalogItem() {
        service.adoptComponent(lotId, "ZZ-РК МИ (МТ)-ADOPTC-1", 4);
        long before = equipmentRepository.count();
        service.adoptComponent(lotId, "ZZ-РК МИ (МТ)-ADOPTC-1", 4);
        assertThat(equipmentRepository.count()).isEqualTo(before);        // дубль не плодится
    }

    @Test
    void adoptComponent_uncachedPart_throws404() {
        assertThatThrownBy(() -> service.adoptComponent(lotId, "ZZ-РК МИ (МТ)-ADOPTC-1", 99))
                .isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: RED**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava 2>&1 | tail -5
```
Expected: `BUILD FAILED` — `adoptComponent` / `findFirstByRegistrationRegNumberAndNameIgnoreCase` не существуют.

- [ ] **Step 3: Finder дедупа**

В `MedEquipmentRepository.java` добавить рядом с `findFirstByRegistrationRegNumber`:

```java
    /** Дедуп позиции по РУ + имени (разные компоненты одного аппарата не сливаются). */
    Optional<MedEquipment> findFirstByRegistrationRegNumberAndNameIgnoreCase(String regNumber, String name);
```

- [ ] **Step 4: Request DTO**

Создать `src/main/java/com/vladoose/nir/dto/request/AdoptComponentRequest.java`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Тело POST /api/lots/{id}/adopt-component: РУ аппарата + номер компонента в комплектности. */
@Data
public class AdoptComponentRequest {
    @NotBlank
    private String regNumber;
    @NotNull
    private Integer partNumber;
}
```

- [ ] **Step 5: Метод `adoptComponent`**

В `ComplectService.java` добавить импорты:

```java
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.repository.MedEquipmentRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
```

Добавить в конструктор ОДИН параметр `MedEquipmentRepository equipmentRepository` (+ поле `private final MedEquipmentRepository equipmentRepository;` + присваивание). `lotService` уже инжектирован (Task 6) — переиспользуем его `findById`/`save` (тот же em.find-обход фильтра + гард, что в `adoptForLot`). Затем метод:

```java
    /**
     * component-adopt: компонент комплектности → позиция каталога (имя/производитель — из компонента,
     * РУ — аппарата, его допуском компонент и покрыт) → предложенная модель лота. Отдельно от adoptForLot.
     */
    @Transactional
    public TenderLot adoptComponent(Long lotId, String regNumber, Integer partNumber) {
        TenderLot lot = lotService.findById(lotId);
        // findById = em.find обходит фильтр рынка → явный гард (паттерн adoptForLot)
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }
        MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ аппарата не найдено: " + regNumber));
        RegistryComponent comp = componentRepository.findByRegNumberAndPartNumber(regNumber, partNumber)
                .orElseThrow(() -> new NotFoundException("Компонент не найден (сначала поиск по комплектности): "
                        + regNumber + " #" + partNumber));

        String name = trim255(comp.getProductName());
        MedEquipment eq = equipmentRepository
                .findFirstByRegistrationRegNumberAndNameIgnoreCase(regNumber, name)
                .orElseGet(() -> {
                    MedEquipment e = new MedEquipment();
                    e.setName(name);
                    e.setManufact(comp.getProducer() != null && !comp.getProducer().isBlank()
                            ? trim255(comp.getProducer()) : "не указан");
                    e.setSpec(comp.getProductName());      // текст компонента с размерами
                    e.setRegistrationStatus(RegistrationStatus.REGISTERED);
                    e.setRegistration(reg);                // РУ аппарата покрывает компонент
                    e.setRegistrationCheckedAt(OffsetDateTime.now());
                    e.setMarket(MarketContext.get());
                    return equipmentRepository.save(e);
                });
        lot.setProposedEquipment(eq);
        return lotService.save(lot);
    }

    private static String trim255(String s) {
        return s != null && s.length() > 255 ? s.substring(0, 255) : s;
    }
```

- [ ] **Step 6: Endpoint**

В `TenderLotController.java` добавить импорт `import com.vladoose.nir.dto.request.AdoptComponentRequest;` и метод (рядом с `complectSearch`):

```java
    /** «Взять в работу» компонент комплектности → предложенная модель лота (РУ аппарата). */
    @PostMapping("/{id}/adopt-component")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderLotResponse adoptComponent(@PathVariable Long id,
                                            @Valid @RequestBody AdoptComponentRequest request) {
        return mapper.toResponse(complectService.adoptComponent(id, request.getRegNumber(), request.getPartNumber()));
    }
```

- [ ] **Step 7: GREEN + гард дублей**

```bash
cd /Users/vlad/IdeaProjects/AIS && grep -c "adoptComponent" src/main/java/com/vladoose/nir/service/ComplectService.java && ./gradlew test --tests "com.vladoose.nir.service.ComplectAdoptTest" 2>&1 | tail -10
```
Expected: `grep -c` = 1, `BUILD SUCCESSFUL`, 3/3.

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/request/AdoptComponentRequest.java src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java src/main/java/com/vladoose/nir/service/ComplectService.java src/main/java/com/vladoose/nir/controller/TenderLotController.java src/test/java/com/vladoose/nir/service/ComplectAdoptTest.java && git commit -m "feat(registry): component-adopt — компонент комплектности в предложенную модель лота

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Фронт — кнопка «Комплектность аппаратов» + результаты + adopt

**Files:**
- Modify: `frontend/src/app/services/api.service.ts` (2 метода после `getRegistryDetail`)
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts` (состояние/методы/шаблон/стили панели «Реестр»)

**Interfaces:**
- Consumes: Task 6 (`POST /api/lots/{id}/complect-search?term=`), Task 7 (`POST /api/lots/{id}/adopt-component`).
- Produces: UI-блок; новых контрактов нет.

- [ ] **Step 1: ApiService**

В `frontend/src/app/services/api.service.ts` после метода `getRegistryDetail` добавить:

```typescript
  complectSearch(lotId: number, term?: string): Observable<any> {
    const params: any = term ? { term } : {};
    return this.http.post<any>(`${this.base}/lots/${lotId}/complect-search`, {}, { params });
  }

  adoptComponent(lotId: number, regNumber: string, partNumber: number): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/adopt-component`, { regNumber, partNumber });
  }
```

- [ ] **Step 2: Состояние + методы компонента**

В `frontend/src/app/pages/tenders/tenders.component.ts` сразу после метода `closeRegistryPanel()` добавить поле и методы:

```typescript
  complectPanel: { lot: any; term: string; loading: boolean; searched: boolean; apparatuses: any[] } | null = null;

  openComplect(l: any) {
    this.complectPanel = { lot: l, term: '', loading: true, searched: false, apparatuses: [] };
    this.cdr.detectChanges();
    // первый прогон — без term: бэк сам извлечёт бренд из ТЗ
    this.runComplect(l, undefined);
  }

  runComplect(l: any, term?: string) {
    if (!this.complectPanel) return;
    this.complectPanel.loading = true;
    this.cdr.detectChanges();
    this.api.complectSearch(l.id, term).subscribe({
      next: (r: any) => {
        this.complectPanel = {
          lot: l, term: r?.term || '', loading: false, searched: true,
          apparatuses: r?.apparatuses || []
        };
        this.cdr.detectChanges();
      },
      error: err => {
        if (this.complectPanel) { this.complectPanel.loading = false; this.complectPanel.searched = true; }
        this.notify.error('Комплектность: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }

  closeComplect() { this.complectPanel = null; this.cdr.detectChanges(); }

  adoptComponent(c: any, comp: any) {
    if (!this.complectPanel) return;
    this.adoptBusy = true;
    this.cdr.detectChanges();
    this.api.adoptComponent(this.complectPanel.lot.id, c.regNumber, comp.partNumber).subscribe({
      next: () => {
        this.adoptBusy = false;
        this.notify.success('Компонент взят в работу — предложенная модель лота обновлена');
        this.closeComplect();
        this.loadLots();
      },
      error: err => {
        this.adoptBusy = false;
        this.notify.error('Не удалось взять компонент: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }
```

Примечание для реализатора: `adoptBusy` (поле, ~строка 690) и `loadLots()` (~строка 1167) уже существуют — те же, что использует `adoptFromRegistry`. `this.notify`/`this.cdr`/`this.api` — существующие поля компонента.

- [ ] **Step 3: Шаблон — кнопка + блок результатов**

В шаблоне панели «Реестр» найти закрывающий `</table>` таблицы кандидатов (тот, что внутри `<div class="registry-panel" *ngIf="registryPanel">`) и сразу ПОСЛЕ него (перед закрывающим `</div>` этой панели) вставить:

```html
        <div class="complect-cta">
          <button class="btn btn-registry" (click)="openComplect(registryPanel.lot)"
                  title="Найти лот в комплектности родительского аппарата (для электродов/пластин/принадлежностей)">
            🔧 Комплектность аппаратов
          </button>
          <span class="registry-note">Если лот — принадлежность к аппарату (электрод, пластина), допуск может быть в комплектности аппарата.</span>
        </div>
```

Затем ПОСЛЕ всего блока `<div class="registry-panel" …>…</div>` (после его закрывающего `</div>`) вставить отдельную панель:

```html
      <div class="registry-panel" *ngIf="complectPanel">
        <div class="registry-panel-head">
          <span><b>Комплектность аппаратов:</b> {{ complectPanel.lot.equipName }}</span>
          <button class="btn btn-cancel" (click)="closeComplect()">✕ Закрыть</button>
        </div>
        <div class="complect-term">
          <input type="text" [(ngModel)]="complectPanel.term" placeholder="Название аппарата (напр. Элэскулап)"
                 (keyup.enter)="runComplect(complectPanel.lot, complectPanel.term)">
          <button class="btn btn-primary" [disabled]="complectPanel.loading"
                  (click)="runComplect(complectPanel.lot, complectPanel.term)">Искать</button>
        </div>
        <div *ngIf="complectPanel.loading" class="registry-loading">Ищем в комплектности аппаратов…</div>
        <div *ngIf="!complectPanel.loading && complectPanel.searched && !complectPanel.apparatuses.length" class="empty">
          Аппарат не найден — уточните его название в поле выше и нажмите «Искать».
        </div>
        <div *ngFor="let a of complectPanel.apparatuses" class="complect-apparatus">
          <div class="complect-app-head">
            {{ a.name }} · <b>{{ a.country || '—' }}</b> · {{ a.producer || '—' }} · РУ {{ a.regNumber }}
          </div>
          <table class="registry-table">
            <thead><tr><th>Совпадение</th><th>Компонент (состав)</th><th>Тип</th><th>Страна</th><th></th></tr></thead>
            <tbody>
              <tr *ngFor="let comp of a.components">
                <td><span class="score-badge" [class.score-good]="comp.score >= 0.5">{{ scorePct(comp) }}%</span></td>
                <td><pre class="complect-pre">{{ comp.productName }}</pre></td>
                <td>{{ comp.component || '—' }}</td>
                <td>{{ comp.country || '—' }}</td>
                <td><button class="btn btn-adopt" [disabled]="adoptBusy" (click)="adoptComponent(a, comp)"
                            title="Создать позицию каталога из компонента (РУ аппарата) и предложить лоту">Взять в работу</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
```

Примечание: `scorePct` уже существует (`Math.round(score*100)`), работает и для компонента (`comp.score`). `FormsModule` уже в `imports` компонента (строка 18) — для `[(ngModel)]` действий не нужно.

- [ ] **Step 4: Стили**

В `styles` компонента рядом с `.registry-*` добавить:

```scss
    .complect-cta { display: flex; align-items: center; gap: 10px; margin-top: 10px; flex-wrap: wrap; }
    .complect-term { display: flex; gap: 8px; margin-bottom: 10px; }
    .complect-term input { flex: 1; max-width: 360px; padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; }
    .complect-apparatus { margin: 10px 0; padding: 8px 10px; border: 1px solid #ddd6fe; border-radius: 8px; background: #fff; }
    .complect-app-head { font-size: 13px; margin-bottom: 6px; color: #374151; }
    .complect-pre { white-space: pre-wrap; margin: 0; font: inherit; max-width: 520px; }
```

- [ ] **Step 5: Сборка (гейт) + гард дублей**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -8
cd /Users/vlad/IdeaProjects/AIS && grep -c "openComplect" frontend/src/app/pages/tenders/tenders.component.ts
```
Expected: сборка успешна (бюджет `anyComponentStyle` не превышен); `grep -c "openComplect"` = 2 (метод + вызов в шаблоне).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts && git commit -m "feat(ui): панель «Комплектность аппаратов» — поиск компонента + Взять в работу

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Полный гейт, живая проверка, CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (§8 — буллет про комплектность; §15 — endpoints)

- [ ] **Step 1: Полный прогон**

```bash
cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9; ./gradlew test 2>&1 | tail -25
```
Expected: падают ТОЛЬКО 2 `ApplyAutoFillServiceTest`.

- [ ] **Step 2: Запуск для живой проверки**

```bash
cd /Users/vlad/IdeaProjects/AIS && GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" ./gradlew bootRun
```
(фоном, `dangerouslyDisableSandbox: true`; фронт на :4200 уже крутится)

- [ ] **Step 3: Playwright — лот 3088 «Электрод» (тендер 17279420-1)**

1. `http://localhost:4200` → `admin`/`admin` → `localStorage.setItem('ais.market','KZ')`.
2. `/tenders?openId=966` → карточка → у лота «Электрод» кнопка «Реестр» → в панели кнопка «🔧 Комплектность аппаратов».
3. Проверить: авто-термин «Элэскулап» → аппарат «АСЭтМ-01/6 ЭЛЭСКУЛАП», **Страна РОССИЯ**, Мед ТеКо → компонент #4 «Электроды силиконовые… 55×80» вверху с наибольшим %.
4. «Взять в работу» на компоненте → тост + предложенная модель лота = электрод (не аппарат); проверить `/equipment` — позиция с РУ аппарата, spec = текст компонента. Screenshot.
5. БД: `PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "SELECT reg_number, part_number, left(product_name,40), country FROM registry_component WHERE reg_number LIKE '%027673%';"` — компоненты закешированы.

- [ ] **Step 4: CLAUDE.md**

В §8 после буллета «Описание кандидата (карточка НЦЭЛС)» добавить:

```markdown
  - **Поиск по комплектности аппаратов (аксессуарные лоты):** кнопка «🔧 Комплектность аппаратов» в панели «Реестр». Для лота-принадлежности (электрод/пластина к аппарату), которого нет отдельной записью реестра, `POST /api/lots/{id}/complect-search[?term=]` → `ComplectService`: `ComplectTermExtractor` вынимает бренд аппарата из названия+ТЗ («Элэскулап») → `MedRegistryRepository.findApparatusByTerm` (записи «(МТ)», `<%`/`word_similarity`) → `NddaClient.resolveId`+`fetchComplectList` (live `MtComplectList?registerId=`, вне tx) → кеш в `registry_component` (V6, `ComplectWriter` — отдельный @Transactional-бин, бэкфилит `ndda_id`) → `ComplectComponentMatcher` ранжирует компоненты по токенам лота. UI: редактируемое поле термина (штурвал при промахе эвристики) + аппараты со страной + компоненты (лучший сверху). `POST /api/lots/{id}/adopt-component {regNumber, partNumber}` → `ComplectService.adoptComponent` (ОТДЕЛЬНО от `adoptForLot`): позиция каталога с именем/производителем КОМПОНЕНТА + РУ АППАРАТА (его допуском компонент покрыт) → предложенная модель лота. Живой кейс: лот «Электрод» тендера 17279420-1 (ТЗ «пластинки для аппарата Элэскулап 55×80») → аппарат ЭЛЭСКУЛАП (РФ, Мед ТеКо) → компонент «Электроды силиконовые 55×80» (было: ЭКГ-электроды из Китая/Индии).
```

В §15 добавить в перечень `/api/lots/…`: `/api/lots/{id}/complect-search` (POST — поиск по комплектности) и `/api/lots/{id}/adopt-component` (POST — компонент → предложенная модель).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md && git commit -m "docs: CLAUDE.md — поиск по комплектности аппаратов (V6, ComplectService)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

(мерж ветки в main — после whole-branch review по SDD/finishing-a-development-branch)
