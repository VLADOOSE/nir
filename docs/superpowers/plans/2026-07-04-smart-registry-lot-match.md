# Умный реестр-матч по лоту + габариты-v2 — план имплементации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Кнопка «Реестр» у лота находит зарегистрированные модели по канцелярскому названию лота (+ разобранному ТЗ), а парсер габаритов берёт поосевые и двумерные формы.

**Architecture:** `LotQueryTokenizer` (стоп-слова + позиционные веса) → нативный `searchByTokens` (пословный `<%` — GIN-индекс, ранг = взвешенное покрытие всех токенов, отсечка 0.2) → `candidatesForLot` (бренд задан → старый путь; иначе токены имени + токены из ТЗ ×0.5). `SpecConstraintExtractor`: триплет → поосевые → двумерный. UI/DTO не меняются.

**Tech Stack:** Java 17 / Spring Boot 3.5.6, PostgreSQL 17 + pg_trgm (существующие GIN-индексы), без новых зависимостей.

**Spec:** `docs/superpowers/specs/2026-07-04-smart-registry-lot-match-design.md`. SQL-форма провалидирована живьём (REGIUS №2, score 0.60).

## Global Constraints

- Ветка: `feat/smart-registry-lot-match` (активна, спека на ней).
- **Sandbox:** `./gradlew`/psql — `dangerouslyDisableSandbox: true`; перед полным прогоном `lsof -ti :8080 | xargs kill -9 || true`.
- Гейт: полный `./gradlew test` — только 2 известных `ApplyAutoFillServiceTest`; `cd frontend && npm run build` (smoke).
- Миграций НЕТ; `@FilterDef` не трогаем (med_registry — общая); **глобальный `pg_trgm.word_similarity_threshold` НЕ менять**.
- `findCandidates` (оборудование/частные заявки/сверка) — НЕ трогать.
- Тестовые данные — префикс `ZZ`; рыночные тесты — `MarketContext.set(...)` + `@AfterEach clear()`.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Субагенты — Fable 5.

---

### Task 1: `LotQueryTokenizer`

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/LotQueryTokenizer.java`
- Test: `src/test/java/com/vladoose/nir/util/LotQueryTokenizerTest.java`

**Interfaces:**
- Produces: `record WeightedToken(String token, double weight)`; `static List<WeightedToken> tokenize(String lotName, String specCharacteristics)` — до 5 токенов имени (веса 1.0/0.7/0.5/0.4/0.3) + до 5 из характеристик ТЗ (та же лестница ×0.5, без дублей); пусто → пустой список.
- Consumes: — (чистая утилита).

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/util/LotQueryTokenizerTest.java`:

```java
package com.vladoose.nir.util;

import com.vladoose.nir.util.LotQueryTokenizer.WeightedToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LotQueryTokenizerTest {

    @Test
    void stripsCancelariteAndKeepsSignificantWithPositionalWeights() {
        List<WeightedToken> t = LotQueryTokenizer.tokenize("Устройство оцифровки рентген снимков", null);
        assertThat(t).extracting(WeightedToken::token)
                .containsExactly("оцифровки", "рентген", "снимков");
        assertThat(t.get(0).weight()).isEqualTo(1.0);
        assertThat(t.get(1).weight()).isEqualTo(0.7);
        assertThat(t.get(2).weight()).isEqualTo(0.5);
    }

    @Test
    void hyphenWordsStayWhole_andServiceWordsDropped() {
        List<WeightedToken> t = LotQueryTokenizer.tokenize("Дефибриллятор-монитор для отделения и палат", null);
        assertThat(t).extracting(WeightedToken::token)
                .containsExactly("дефибриллятор-монитор", "отделения", "палат");
    }

    @Test
    void specCharacteristicsAddTokensAtHalfWeight_noDuplicates() {
        List<WeightedToken> t = LotQueryTokenizer.tokenize("Электрод",
                "Резиновые пластинки для аппарата электрофореза \"Элэскулап\", размеры 55*80 мм, электрод");
        assertThat(t).extracting(WeightedToken::token)
                .startsWith("электрод")                       // из имени, вес 1.0
                .contains("резиновые", "пластинки", "электрофореза", "элэскулап")
                .doesNotContain("аппарата", "размеры", "мм"); // канцелярит/служебные/короткие — вон
        assertThat(t).filteredOn(x -> x.token().equals("электрод")).hasSize(1); // без дублей
        WeightedToken rez = t.stream().filter(x -> x.token().equals("резиновые")).findFirst().orElseThrow();
        assertThat(rez.weight()).isEqualTo(0.5); // 1.0 × 0.5
    }

    @Test
    void numbersShortAndBlankDropped_emptyGivesEmpty() {
        assertThat(LotQueryTokenizer.tokenize("Аппарат 2 шт", null)).isEmpty();
        assertThat(LotQueryTokenizer.tokenize(null, null)).isEmpty();
        assertThat(LotQueryTokenizer.tokenize("  ", "  ")).isEmpty();
    }

    @Test
    void capsAtFiveNameTokens() {
        List<WeightedToken> t = LotQueryTokenizer.tokenize(
                "Оцифровщик рентгеновских снимков панорамный цифровой беспроводной переносной", null);
        assertThat(t).hasSize(5);
        assertThat(t.get(4).weight()).isEqualTo(0.3);
    }
}
```

- [ ] **Step 2: Прогнать — падает (компиляция).**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.LotQueryTokenizerTest"`
Expected: FAIL (класс не существует).

- [ ] **Step 3: Реализация**

`src/main/java/com/vladoose/nir/util/LotQueryTokenizer.java`:

```java
package com.vladoose.nir.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Название лота (+ характеристики из ТЗ) → значимые токены для пословного триграммного
 * реестр-матча. Канцелярит («устройство», «аппарат»…) и служебные слова выбрасываются:
 * они цепляют мусор при пониженном пороге и топят реальные изделия. Веса позиционные —
 * головное существительное в госзакуп-названиях идёт первым.
 */
public final class LotQueryTokenizer {

    public record WeightedToken(String token, double weight) {}

    private static final double[] WEIGHTS = {1.0, 0.7, 0.5, 0.4, 0.3};
    private static final double SPEC_FACTOR = 0.5;
    private static final int MAX_PER_SOURCE = 5;

    private static final Set<String> STOP = Set.of(
            // канцелярит госзакуп-названий
            "устройство", "устройства", "аппарат", "аппарата", "аппаратный", "аппаратная",
            "система", "системы", "комплекс", "комплекса", "изделие", "изделия",
            "прибор", "прибора", "оборудование", "оборудования", "комплект", "комплекта",
            "набор", "набора", "товар", "товара", "товаров", "штука", "штук",
            "размер", "размеры", "размера", "размеров",
            "медицинский", "медицинская", "медицинское", "медицинские", "медицинских",
            // служебные
            "для", "или", "не", "более", "менее", "с", "со", "по", "на", "из", "к", "от", "до", "в", "и");

    private LotQueryTokenizer() {}

    public static List<WeightedToken> tokenize(String lotName, String specCharacteristics) {
        LinkedHashSet<String> nameTokens = significant(lotName);
        LinkedHashSet<String> specTokens = significant(specCharacteristics);
        specTokens.removeAll(nameTokens);

        List<WeightedToken> out = new ArrayList<>();
        int i = 0;
        for (String t : nameTokens) {
            if (i >= MAX_PER_SOURCE) break;
            out.add(new WeightedToken(t, WEIGHTS[i++]));
        }
        int j = 0;
        for (String t : specTokens) {
            if (j >= MAX_PER_SOURCE) break;
            out.add(new WeightedToken(t, WEIGHTS[j++] * SPEC_FACTOR));
        }
        return out;
    }

    private static LinkedHashSet<String> significant(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        // дефисные слова целиком; всё остальное не-буквенное — разделитель
        for (String raw : text.toLowerCase().split("[^\\p{L}-]+")) {
            String t = raw.replaceAll("^-+|-+$", "");
            if (t.length() < 3) continue;          // короткие и «мм/шт»
            if (!t.chars().anyMatch(Character::isLetter)) continue;
            if (STOP.contains(t)) continue;
            out.add(t);
        }
        return out;
    }
}
```

- [ ] **Step 4: Прогнать — зелёный.**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.LotQueryTokenizerTest"`
Expected: PASS (5/5).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/util/LotQueryTokenizer.java \
  src/test/java/com/vladoose/nir/util/LotQueryTokenizerTest.java
git commit -m "feat(registry): LotQueryTokenizer — значимые токены лота со стоп-словами и позиционными весами

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `searchByTokens` + проводка в `candidatesForLot` + золотой набор

**Files:**
- Modify: `src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/RegistryMatchService.java`
- Test: `src/test/java/com/vladoose/nir/service/RegistryLotMatchTest.java` (расширить)

**Interfaces:**
- Consumes: `LotQueryTokenizer.tokenize` (T1), `TechSpecExtractor.characteristics` (существует), `RegistryCandidateRow` (существует).
- Produces: `MedRegistryRepository.searchByTokens(String tokens, String weights, int limit): List<RegistryCandidateRow>` (токены/веса через `|`); `candidatesForLot`: manufact задан → старый путь; иначе токенный с фолбэком на старый при пустых токенах.

- [ ] **Step 1: Расширить золотой набор (падающие тесты)**

В `src/test/java/com/vladoose/nir/service/RegistryLotMatchTest.java` добавить перед закрывающей скобкой класса (используются существующие поля/импорты; живой реестр ~14k в nirdb):

```java
    private TenderLot savedLot(String equipName, String manufact, String requiredSpec) {
        Tender t = new Tender();
        t.setTenderNumber("ZZ-GOLD-" + System.nanoTime());
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        TenderLot lot = new TenderLot();
        lot.setTender(t);
        lot.setEquipName(equipName);
        lot.setManufact(manufact);
        lot.setRequiredSpec(requiredSpec);
        t.getLots().add(lot);
        tenderRepository.save(t);
        return t.getLots().get(0);
    }

    @Test
    void golden_xrayDigitizer_findsRegistryModels() {
        TenderLot lot = savedLot("Устройство оцифровки рентген снимков", null, null);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 5);
        assertThat(top).isNotEmpty();
        assertThat(top).anyMatch(c -> {
            String n = c.getName().toLowerCase();
            return n.contains("оцифровщик") || (n.contains("рентген") && n.contains("снимк"));
        });
    }

    @Test
    void golden_defibrillatorMonitor_topContainsDefibrillator() {
        TenderLot lot = savedLot("Дефибриллятор-монитор бифазный портативный", null, null);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 3);
        assertThat(top).isNotEmpty();
        assertThat(top.get(0).getName().toLowerCase()).contains("дефибриллятор");
    }

    @Test
    void golden_shortName_pulseOximeter_stillWorks() {
        TenderLot lot = savedLot("Пульсоксиметр", null, null);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 3);
        assertThat(top).isNotEmpty();
        assertThat(top.get(0).getName().toLowerCase()).contains("пульсоксиметр");
    }

    @Test
    void golden_electrode_enrichedFromParsedTechSpec() {
        TenderLot lot = savedLot("Электрод", null, """
                Приложение 2
                Описание и требуемые функциональные, технические, качественные и эксплуатационные
                характеристики
                закупаемых товаров:
                Резиновые пластинки для аппарата электрофореза "Элэскулап", размеры 55*80 мм
                """);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 5);
        assertThat(top).isNotEmpty();
        assertThat(top).anyMatch(c -> {
            String n = c.getName().toLowerCase();
            return n.contains("электрофорез") || n.contains("элэскулап");
        });
    }

    @Test
    void golden_manufactSet_usesOldBrandPath() {
        TenderLot lot = savedLot("Монитор пациента", "Mindray", null);
        List<RegistryCandidateResponse> top = registryMatchService.candidatesForLot(lot.getId(), 5);
        assertThat(top).isNotEmpty();
        // бренд-путь: производитель в топе содержит Mindray
        assertThat(top.get(0).getProducer().toLowerCase()).contains("mindray");
    }
```

- [ ] **Step 2: Прогнать — золотые падают** (сейчас канцелярские имена дают пусто/мусор).

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.RegistryLotMatchTest"`
Expected: FAIL — `golden_xrayDigitizer…` (пусто) и/или `golden_electrode…`; старые 2 теста зелёные.

- [ ] **Step 3: Репозиторий — `searchByTokens`**

В `MedRegistryRepository.java` добавить (SQL-форма провалидирована живьём 2026-07-04):

```java
    /**
     * Пословный триграммный матч значимых токенов лота: фильтр по «токен <% name» (GIN-индекс,
     * глобальный порог 0.6 не трогаем), ранг — взвешенное покрытие ВСЕХ токенов
     * (Σ wᵢ·word_similarity(tᵢ,name)/Σ wᵢ), отсечка мусора score >= 0.2.
     * Токены/веса — строками через '|' (string_to_array), чтобы не возиться с массивами в Hibernate.
     */
    @Query(nativeQuery = true, value =
            "SELECT * FROM ( " +
            "  SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, " +
            "         m.country AS country, m.reg_date AS regDate, m.expiration_date AS expirationDate, " +
            "         m.unlimited AS unlimited, " +
            "         (SELECT sum(w.wgt::float8 * word_similarity(t.tok, m.name)) / sum(w.wgt::float8) " +
            "          FROM unnest(string_to_array(:tokens,'|'))  WITH ORDINALITY AS t(tok, i) " +
            "          JOIN unnest(string_to_array(:weights,'|')) WITH ORDINALITY AS w(wgt, j) ON t.i = w.j " +
            "         ) AS score " +
            "  FROM med_registry m " +
            "  WHERE EXISTS (SELECT 1 FROM unnest(string_to_array(:tokens,'|')) tk(tok) WHERE tk.tok <% m.name) " +
            ") s WHERE s.score >= 0.2 " +
            "ORDER BY s.score DESC " +
            "LIMIT :limit")
    List<RegistryCandidateRow> searchByTokens(@Param("tokens") String tokens,
                                              @Param("weights") String weights,
                                              @Param("limit") int limit);
```

- [ ] **Step 4: Сервис — проводка**

В `RegistryMatchService.java`:

импорты добавить:
```java
import com.vladoose.nir.util.LotQueryTokenizer;
import com.vladoose.nir.util.LotQueryTokenizer.WeightedToken;
import com.vladoose.nir.util.TechSpecExtractor;
import java.util.Locale;
import java.util.stream.Collectors;
```

`candidatesForLot` заменить на:

```java
    /** Кандидаты реестра по лоту: бренд задан → бренд-путь; иначе значимые токены имени
     *  (+ токены из характеристик разобранного ТЗ, вес ×0.5) → пословный триграммный матч. */
    public List<RegistryCandidateResponse> candidatesForLot(Long lotId, int limit) {
        TenderLot lot = tenderLotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        if (lot.getManufact() != null && !lot.getManufact().isBlank()) {
            return findCandidates(lot.getEquipName(), lot.getManufact(), limit);
        }
        List<WeightedToken> tokens = LotQueryTokenizer.tokenize(
                lot.getEquipName(), TechSpecExtractor.characteristics(lot.getRequiredSpec()));
        if (tokens.isEmpty()) {
            return findCandidates(lot.getEquipName(), lot.getManufact(), limit); // фолбэк как раньше
        }
        String toks = tokens.stream().map(WeightedToken::token).collect(Collectors.joining("|"));
        String wgts = tokens.stream()
                .map(t -> String.format(Locale.ROOT, "%.2f", t.weight()))
                .collect(Collectors.joining("|"));
        return registryRepository.searchByTokens(toks, wgts, limit).stream()
                .map(this::toCandidate)
                .toList();
    }
```

и вынести существующий маппинг row→response в приватный хелпер (используется обоими путями):

```java
    private RegistryCandidateResponse toCandidate(com.vladoose.nir.dto.response.RegistryCandidateRow row) {
        RegistryCandidateResponse c = new RegistryCandidateResponse();
        c.setRegNumber(row.getRegNumber());
        c.setName(row.getName());
        c.setProducer(row.getProducer());
        c.setCountry(row.getCountry());
        c.setRegDate(row.getRegDate());
        c.setExpirationDate(row.getExpirationDate());
        c.setUnlimited(row.getUnlimited());
        c.setScore(row.getScore());
        return c;
    }
```

(в `findCandidates` заменить лямбда-маппинг на `.map(this::toCandidate)` — DRY).

- [ ] **Step 5: Прогнать — золотой набор зелёный** (+ сверка/частники не сломаны)

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.RegistryLotMatchTest" --tests "com.vladoose.nir.service.RegistryMatchServiceTest" --tests "com.vladoose.nir.privaterequest.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java \
  src/main/java/com/vladoose/nir/service/RegistryMatchService.java \
  src/test/java/com/vladoose/nir/service/RegistryLotMatchTest.java
git commit -m "feat(registry): умный реестр-матч по лоту — пословные триграммы + обогащение из ТЗ + золотой набор

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Габариты-v2 (`SpecConstraintExtractor`)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/util/SpecConstraintExtractor.java`
- Test: `src/test/java/com/vladoose/nir/util/SpecConstraintExtractorTest.java` (расширить)

**Interfaces:**
- Produces (поведение): приоритет триплет → поосевые (длина|глубина→length, ширина→width, высота→height) → двумерный «(размер|габарит)… A[х×*]B (мм|см|м)» → length+width. «Не менее» игнор везде. API (`extract`, `SpecConstraints`) не меняется — `scoreLot` и «ТЗ» подхватывают автоматически.

- [ ] **Step 1: Падающие тесты**

В `SpecConstraintExtractorTest.java` добавить перед закрывающей скобкой:

```java
    @Test
    void perAxisConstraints() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Длина не более 1200 мм. Ширина до 80 см. Высота не более 1,5 м. Глубина не менее 100 мм.");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
        assertThat(c.maxWidthMm()).isEqualTo(800);
        assertThat(c.maxHeightMm()).isEqualTo(1500);
        // «глубина не менее» — нижняя граница, игнор (и не перетирает length)
    }

    @Test
    void depthMapsToLength_whenNoLength() {
        SpecConstraints c = SpecConstraintExtractor.extract("Глубина не более 450 мм, высота до 900 мм");
        assertThat(c.maxLengthMm()).isEqualTo(450);
        assertThat(c.maxHeightMm()).isEqualTo(900);
        assertThat(c.maxWidthMm()).isNull();
    }

    @Test
    void twoDimensionalWithKeyword() {
        SpecConstraints c = SpecConstraintExtractor.extract("электроды силиконовые, размеры 55*80 мм");
        assertThat(c.maxLengthMm()).isEqualTo(55);
        assertThat(c.maxWidthMm()).isEqualTo(80);
        assertThat(c.maxHeightMm()).isNull();
    }

    @Test
    void twoDimensionalWithoutKeywordIgnored() {
        SpecConstraints c = SpecConstraintExtractor.extract("кабель сечением 2*4 мм");
        assertThat(c.isEmpty()).isTrue();
    }

    @Test
    void tripleTakesPriorityOverAxesAndTwoD() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Габариты не более 1000х600х400 мм. Длина не более 9999 мм. Размер 55*80 мм.");
        assertThat(c.maxLengthMm()).isEqualTo(1000);
        assertThat(c.maxWidthMm()).isEqualTo(600);
        assertThat(c.maxHeightMm()).isEqualTo(400);
    }
```

- [ ] **Step 2: Прогнать — падают.**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.SpecConstraintExtractorTest"`
Expected: FAIL (новые 5), старые 11 зелёные.

- [ ] **Step 3: Реализация**

В `SpecConstraintExtractor.java`: добавить паттерны и ветки после триплета (структура — извлекли триплет? иначе поосевые, затем двумерный на незаполненные оси):

```java
    /** «длина/глубина/ширина/высота … 450 мм»: g1 — зазор (проверка «не менее»), g2 — число, g3 — единица. */
    private static final Pattern AXIS = Pattern.compile(
            "(длин\\w*|глубин\\w*|ширин\\w*|высот\\w*)([^\\n\\d]{0,40}?)" + NUM + "\\s*(мм|см|м)\\b", FLAGS);

    /** «размер/габарит … 55*80 мм»: двумерный (пластины, электроды). g1 — зазор, g2,g3 — числа, g4 — единица. */
    private static final Pattern TWO_D = Pattern.compile(
            "(?:размер\\w*|габарит\\w*)([^\\n]{0,40}?)" + NUM + X + NUM + "\\s*(мм|см|м)\\b", FLAGS);
```

в `extract(...)` после блока триплета (переменные len/wid/hei уже объявлены):

```java
            if (len == null && wid == null && hei == null) {
                // поосевые: каждая ось независимо; «не менее/от/минимум» — игнор
                Matcher a = AXIS.matcher(spec);
                while (a.find()) {
                    if (LOWER_BOUND.matcher(a.group(2)).find()) continue;
                    double k = unitToMm(a.group(4));
                    Integer v = toMm(a.group(3), k);
                    String axis = a.group(1).toLowerCase();
                    if ((axis.startsWith("длин") || axis.startsWith("глубин")) && len == null) len = v;
                    else if (axis.startsWith("ширин") && wid == null) wid = v;
                    else if (axis.startsWith("высот") && hei == null) hei = v;
                    else continue;
                    snippets.add(spec.substring(a.start(), a.end()).trim());
                }
            }
            if (len == null && wid == null && hei == null) {
                // двумерный «размеры 55*80 мм» (пластины/электроды): length+width
                Matcher d2 = TWO_D.matcher(spec);
                while (d2.find()) {
                    if (LOWER_BOUND.matcher(d2.group(1)).find()) continue;
                    double k = unitToMm(d2.group(4));
                    len = toMm(d2.group(2), k);
                    wid = toMm(d2.group(3), k);
                    snippets.add(spec.substring(d2.start(), d2.end()).trim());
                    break;
                }
            }
```

ВНИМАНИЕ на нумерацию групп: в `AXIS` группа 1 — ось, 2 — зазор, 3 — число (`NUM` — одна группа), 4 — единица; в `TWO_D`: 1 — зазор, 2 и 3 — числа, 4 — единица. Триплет `TRIPLE` не менять. Двумерный НЕ должен матчить триплет-строки: `TWO_D` проверяется только когда триплет и оси не нашлись, а `X`-разделитель в `TRIPLE` жаднее (3 числа) — конфликт исключён порядком веток.

- [ ] **Step 4: Прогнать — зелёные все** (старые 11 + новые 5)

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.SpecConstraintExtractorTest" --tests "com.vladoose.nir.service.EquipmentScoringSpecDerivedTest" --tests "com.vladoose.nir.service.TechSpecServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/util/SpecConstraintExtractor.java \
  src/test/java/com/vladoose/nir/util/SpecConstraintExtractorTest.java
git commit -m "feat(match): габариты-v2 — поосевые «не более X мм» и двумерные «55*80 мм» из ТЗ

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Финальный гейт + live + CLAUDE.md + мерж

**Files:**
- Modify: `CLAUDE.md` (§8, §11, §16)

- [ ] **Step 1: Полный бэкенд-прогон**

```bash
lsof -ti :8080 | xargs kill -9 || true
./gradlew test
```
Expected: только 2 известных `ApplyAutoFillServiceTest`.

- [ ] **Step 2: Фронт-smoke**

Run: `cd frontend && npm run build` → успех (фронт не менялся).

- [ ] **Step 3: Live (Playwright, живой токен)**

Бэк `GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" ./gradlew bootRun` (фон), фронт :4200, KZ:
1. `/tenders?openId=852` → «Реестр» у лота «Устройство оцифровки рентген снимков» → в панели оцифровщик/сканеры рентген-пластин (VistaScan/REGIUS-класс), не пусто.
2. Электрод-лот (тендер 17279420-1, ТЗ уже разобрано) → «Реестр» → кандидаты про электрофорез/ЭЛЭСКУЛАП.
3. Электрод: повторно «ТЗ» → тост теперь «габариты ✓» (двумерный 55*80) → в таблице «Габариты (макс.)» = 55x80x—.
Скриншоты.

- [ ] **Step 4: CLAUDE.md**

§8, в пункт про разбор техспеки добавить хвост:

```markdown
  - **Умный реестр-матч по лоту:** `candidatesForLot` — бренд задан → бренд-путь `findCandidates`; иначе `LotQueryTokenizer` (стоп-слова канцелярита/служебных, ≤5 токенов имени с весами 1.0…0.3 + ≤5 из характеристик разобранного ТЗ ×0.5) → `searchByTokens` (пословный `<%` GIN, ранг = взвешенное покрытие всех токенов, отсечка 0.2). Порог `word_similarity_threshold` глобально НЕ меняется. FTS дисквалифицирован (рус. стеммер не склеивает оцифровщик/оцифровки, рентген/рентгеновских). Габариты-v2: триплет → поосевые (длина|глубина/ширина/высота «не более X») → двумерный «(размер|габарит) 55*80 мм».
```

§11 дополнить строкой: «Лот-матч (канцелярские имена) — токенный путь `searchByTokens`, см. §8».
§16: вычеркнуть/заменить пункт «Реестр-матч по разобранному ТЗ…» на «✔ сделано (токенный матч); осталось: транслит брендов».

- [ ] **Step 5: Commit + merge**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md — умный реестр-матч по лоту + габариты-v2

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Затем whole-branch review (Fable) → `git checkout main && git merge --ff-only feat/smart-registry-lot-match && git branch -d …` → тур.

---

## Порядок и зависимости

```
T1 (tokenizer) ─→ T2 (searchByTokens+golden) ─→ T4 (гейт+live)
T3 (габариты-v2) — независим, перед T4
```
