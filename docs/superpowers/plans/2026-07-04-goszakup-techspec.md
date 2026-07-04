# Разбор техспецификации лота goszakup (PDF → структура лота) — план имплементации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Кнопка «ТЗ» на импортном лоте скачивает PDF «Техническая спецификация» через goszakup API, извлекает русскую ТТХ-секцию в `requiredSpec` и найденные габариты/вес — в структурные поля лота.

**Architecture:** Синхронный on-demand разбор: `TenderLotController.parse-techspec` → `TechSpecService` (сеть вне транзакции: v3 GraphQL `Lots.Files` → скачать PDF → PDFBox-текст → русская секция → `SpecConstraintExtractor`) → `TechSpecWriter` (`@Transactional`, отдельный бин) пишет в лот. Без миграций, без очередей, без LLM.

**Tech Stack:** Java 17 / Spring Boot 3.5.6, **Apache PDFBox 3.0.5 (новая зависимость)**, существующие `GoszakupHttpClient` (v3 GraphQL уже освоен), `SpecConstraintExtractor`, Angular 21.

**Spec:** `docs/superpowers/specs/2026-07-04-goszakup-techspec-design.md` (одобрена).

## Global Constraints

- Ветка: `feat/goszakup-techspec` (уже активна, спека на ней).
- **Sandbox:** ЛЮБЫЕ `./gradlew` и psql — с `dangerouslyDisableSandbox: true`; перед полным прогоном `lsof -ti :8080 | xargs kill -9 || true`.
- Гейт: полный `./gradlew test` — только 2 известных падения `ApplyAutoFillServiceTest`; фронт — `cd frontend && npm run build`.
- **Миграций НЕТ** (пишем в существующие поля `tender_lot`).
- `@FilterDef` не трогать; правка полей лота через `save(lot)` безопасна (не orphanRemoval-путь).
- `findById` = `em.find` обходит рыночный фильтр → явный market-гард в сервисе (паттерн из `PriceRequestSendService.requireCurrentMarket`).
- Сеть вне транзакции (§6): `TechSpecService` НЕ `@Transactional`, запись — `TechSpecWriter` (`@Transactional`, отдельный бин).
- Токен: `goszakup.api.token` / env `GOSZAKUP_TOKEN`; не эхо-печатать (только `$(cat /tmp/goszakup.token)`).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Тестовые данные — префикс `ZZ`; в тестах с рыночными сущностями `MarketContext.set(...)` + `@AfterEach clear()`.

**Живые формы API (сняты токеном 2026-07-04, закрепить в коде/тестах):**
- v3 GraphQL (POST `https://ows.goszakup.gov.kz/v3/graphql`), запрос с variables подтверждён:
  `query($anno:String,$l:Int){ Lots(filter:{trdBuyNumberAnno:$anno}, limit:$l){ lotNumber nameRu Files{ nameRu originalName filePath } } }`
  → `data.Lots[].Files[]` = `{nameRu:"Техническая спецификация", originalName:"techspec_*.pdf", filePath:"https://ows.goszakup.gov.kz/download/trd_buy_lots_list/<hash>"}`.
- Скачивание: GET `filePath` c `Authorization: Bearer <token>` → 200, `%PDF`, ~38–48 КБ.
- PDF двуязычный: казахская секция, затем русская («Приложение 2 … Техническая спецификация закупаемых товаров…»).

---

### Task 1: PDFBox + `PdfTextExtractor` + реальный PDF-фикстур

**Files:**
- Modify: `build.gradle` (dependencies)
- Create: `src/test/resources/goszakup/techspec-pulse.pdf` (реальная техспека пульсоксиметра, ~38 КБ)
- Create: `src/main/java/com/vladoose/nir/util/PdfTextExtractor.java`
- Test: `src/test/java/com/vladoose/nir/util/PdfTextExtractorTest.java`

**Interfaces:**
- Produces: `PdfTextExtractor.extract(byte[] pdf): String|null` (null — pdf null/пустой/нечитаемый); тестовый ресурс `goszakup/techspec-pulse.pdf` (переиспользуется в Task 4).
- Consumes: —

- [ ] **Step 1: Зависимость PDFBox**

В `build.gradle` после строки `implementation 'org.apache.poi:poi-ooxml:5.2.5'` добавить:

```groovy
    implementation 'org.apache.pdfbox:pdfbox:3.0.5'
```

- [ ] **Step 2: Положить фикстур**

```bash
mkdir -p src/test/resources/goszakup
cp /tmp/pulse.pdf src/test/resources/goszakup/techspec-pulse.pdf
ls -la src/test/resources/goszakup/
```
Если `/tmp/pulse.pdf` пропал — перескачать (не печатать токен):
```bash
curl -s -H "Authorization: Bearer $(cat /tmp/goszakup.token)" \
  "https://ows.goszakup.gov.kz/download/trd_buy_lots_list/a8174cac2a67e770261139cb6fbd327b" \
  -o src/test/resources/goszakup/techspec-pulse.pdf
```
Expected: файл ~38698 байт, magic `%PDF`.

- [ ] **Step 3: Падающий тест**

`src/test/java/com/vladoose/nir/util/PdfTextExtractorTest.java`:

```java
package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTextExtractorTest {

    private byte[] fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/goszakup/techspec-pulse.pdf")) {
            return in.readAllBytes();
        }
    }

    @Test
    void extractsTextFromRealTechSpec() throws Exception {
        String text = PdfTextExtractor.extract(fixture());
        assertThat(text)
                .contains("Пульсоксиметр")
                .contains("Техническая спецификация")
                .contains("Диапазон измерения");
    }

    @Test
    void garbageBytesGiveNull() {
        assertThat(PdfTextExtractor.extract("не pdf".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    void nullAndEmptyGiveNull() {
        assertThat(PdfTextExtractor.extract(null)).isNull();
        assertThat(PdfTextExtractor.extract(new byte[0])).isNull();
    }
}
```

- [ ] **Step 4: Прогнать — падает (компиляция)**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.PdfTextExtractorTest"`
Expected: FAIL — `PdfTextExtractor` не существует.

- [ ] **Step 5: Реализация**

`src/main/java/com/vladoose/nir/util/PdfTextExtractor.java`:

```java
package com.vladoose.nir.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/** Извлечение полного текста из PDF (PDFBox). Нечитаемый/пустой PDF → null, не исключение. */
public final class PdfTextExtractor {

    private PdfTextExtractor() {}

    public static String extract(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return null;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            return (text == null || text.isBlank()) ? null : text;
        } catch (IOException e) {
            return null;
        }
    }
}
```

- [ ] **Step 6: Прогнать — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.PdfTextExtractorTest"`
Expected: PASS (3/3).

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/main/java/com/vladoose/nir/util/PdfTextExtractor.java \
  src/test/java/com/vladoose/nir/util/PdfTextExtractorTest.java \
  src/test/resources/goszakup/techspec-pulse.pdf
git commit -m "feat(techspec): PDFBox + PdfTextExtractor (текст из PDF, null на мусор) + реальный фикстур

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `TechSpecExtractor` — русская секция из двуязычного текста

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/TechSpecExtractor.java`
- Test: `src/test/java/com/vladoose/nir/util/TechSpecExtractorTest.java`

**Interfaces:**
- Produces: `TechSpecExtractor.russianSection(String fullText): String|null` — от первого маркера («приложение» / «техническая спецификация», case-insensitive) до конца; маркеров нет → весь текст (trim); null/blank → null.
- Consumes: —

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/util/TechSpecExtractorTest.java`:

```java
package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechSpecExtractorTest {

    private static final String BILINGUAL = """
            Конкурстық құжаттамаға
            2-қосымша
            Конкурстық құжаттамаға сатып алынаты тауарлардың техникалық ерекшелігі
            Лоттың атауы : Пульсоксиметр
            Лоттың сипаттауы: медициналық
            Приложение 2
            к конкурсной документации
            Техническая спецификация закупаемых товаров к конкурсной документации
            Наименование лота: Пульсоксиметр
            Описание и требуемые функциональные, технические, качественные и
            эксплуатационные характеристики закупаемых товаров:
            Портативное устройство. Диапазон измерения сатурации: 0-100
            """;

    @Test
    void cutsRussianSectionFromBilingual() {
        String ru = TechSpecExtractor.russianSection(BILINGUAL);
        assertThat(ru)
                .startsWith("Приложение 2")
                .contains("Диапазон измерения сатурации: 0-100")
                .doesNotContain("Лоттың");
    }

    @Test
    void markerTechSpecWithoutPrilozhenie() {
        String text = "Кандай да бир мәтін\nТЕХНИЧЕСКАЯ СПЕЦИФИКАЦИЯ\nВес не более 45 кг";
        assertThat(TechSpecExtractor.russianSection(text))
                .startsWith("ТЕХНИЧЕСКАЯ СПЕЦИФИКАЦИЯ")
                .contains("45 кг");
    }

    @Test
    void noMarkersReturnsWholeTextTrimmed() {
        assertThat(TechSpecExtractor.russianSection("  просто текст ТЗ  "))
                .isEqualTo("просто текст ТЗ");
    }

    @Test
    void nullAndBlankGiveNull() {
        assertThat(TechSpecExtractor.russianSection(null)).isNull();
        assertThat(TechSpecExtractor.russianSection("   ")).isNull();
    }
}
```

- [ ] **Step 2: Прогнать — падает (компиляция).**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.TechSpecExtractorTest"`
Expected: FAIL (compilation).

- [ ] **Step 3: Реализация**

`src/main/java/com/vladoose/nir/util/TechSpecExtractor.java`:

```java
package com.vladoose.nir.util;

/**
 * Вырезает русскую секцию из двуязычной техспеки goszakup (сначала казахская, затем русская).
 * Маркеры русской шапки: «Приложение …» / «Техническая спецификация» (в казахской части их нет —
 * там «қосымша» и «техникалық ерекшелігі»). Маркеров нет → весь текст как есть.
 */
public final class TechSpecExtractor {

    private static final String[] MARKERS = {"приложение", "техническая спецификация"};

    private TechSpecExtractor() {}

    public static String russianSection(String fullText) {
        if (fullText == null || fullText.isBlank()) return null;
        String lower = fullText.toLowerCase();
        int best = -1;
        for (String m : MARKERS) {
            int i = lower.indexOf(m);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        String section = best >= 0 ? fullText.substring(best) : fullText;
        return section.strip();
    }
}
```

- [ ] **Step 4: Прогнать — зелёный.**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.util.TechSpecExtractorTest"`
Expected: PASS (4/4).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/util/TechSpecExtractor.java \
  src/test/java/com/vladoose/nir/util/TechSpecExtractorTest.java
git commit -m "feat(techspec): TechSpecExtractor — русская секция из двуязычного текста ТЗ

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `GoszakupClient.fetchLotTechSpec` + `downloadFile`

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/dto/LotTechSpecRef.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupClient.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java`
- Modify: `src/test/java/com/vladoose/nir/integration/goszakup/FakeGoszakupClient.java`
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/LotTechSpecParseTest.java`

**Interfaces:**
- Consumes: существующие хелперы `GoszakupHttpClient` (`rawPost`, `graphqlUrl()`, `raw(...)`, `objectMapper`), `GoszakupNotFoundException`.
- Produces:
  - `record LotTechSpecRef(String filePath, String originalName, boolean ambiguous)`;
  - `GoszakupClient.fetchLotTechSpec(String numberAnno, String lotNameRu): LotTechSpecRef|null` (null — лот/файл не найден);
  - `GoszakupClient.downloadFile(String url): byte[]`;
  - статический `GoszakupHttpClient.parseLotTechSpec(JsonNode root, String lotNameRu): LotTechSpecRef|null` (чистая функция для юнит-теста);
  - `FakeGoszakupClient`: `Map<String, LotTechSpecRef> techSpecByKey` (ключ `numberAnno + "|" + lotNameRu`), `Map<String, byte[]> filesByUrl`.

- [ ] **Step 1: Падающий тест (парсинг GraphQL-ответа, канонический JSON из живой разведки)**

`src/test/java/com/vladoose/nir/integration/goszakup/LotTechSpecParseTest.java`:

```java
package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LotTechSpecParseTest {

    private final ObjectMapper om = new ObjectMapper();

    /** Живой формат v3 Lots.Files (снят токеном 2026-07-04, тендер 17280874-1). */
    private static final String LIVE = """
        {"data":{"Lots":[
          {"lotNumber":"87273742-ОИ2","nameRu":"Пульсоксиметр","Files":[
            {"nameRu":"Техническая спецификация",
             "filePath":"https://ows.goszakup.gov.kz/download/trd_buy_lots_list/a8174cac",
             "originalName":"techspec_17280874_.pdf"}]}
        ]}}""";

    private static final String TWO_SAME_NAME = """
        {"data":{"Lots":[
          {"nameRu":"Сумка","Files":[{"nameRu":"Техническая спецификация","filePath":"u1","originalName":"f1.pdf"}]},
          {"nameRu":"Сумка","Files":[{"nameRu":"Техническая спецификация","filePath":"u2","originalName":"f2.pdf"}]},
          {"nameRu":"Другое","Files":[]}
        ]}}""";

    @Test
    void findsTechSpecByLotName() throws Exception {
        JsonNode root = om.readTree(LIVE);
        LotTechSpecRef ref = GoszakupHttpClient.parseLotTechSpec(root, "Пульсоксиметр");
        assertThat(ref).isNotNull();
        assertThat(ref.filePath()).contains("/download/trd_buy_lots_list/");
        assertThat(ref.originalName()).isEqualTo("techspec_17280874_.pdf");
        assertThat(ref.ambiguous()).isFalse();
    }

    @Test
    void nameMatchIsCaseInsensitiveAndTrimmed() throws Exception {
        JsonNode root = om.readTree(LIVE);
        assertThat(GoszakupHttpClient.parseLotTechSpec(root, "  пульсоксиметр ")).isNotNull();
    }

    @Test
    void duplicateNamesReturnFirstWithAmbiguousFlag() throws Exception {
        JsonNode root = om.readTree(TWO_SAME_NAME);
        LotTechSpecRef ref = GoszakupHttpClient.parseLotTechSpec(root, "Сумка");
        assertThat(ref).isNotNull();
        assertThat(ref.filePath()).isEqualTo("u1");
        assertThat(ref.ambiguous()).isTrue();
    }

    @Test
    void unknownLotOrNoTechSpecFileGivesNull() throws Exception {
        JsonNode root = om.readTree(LIVE);
        assertThat(GoszakupHttpClient.parseLotTechSpec(root, "Томограф")).isNull();
        assertThat(GoszakupHttpClient.parseLotTechSpec(om.readTree(TWO_SAME_NAME), "Другое")).isNull();
    }
}
```

- [ ] **Step 2: Прогнать — падает (компиляция).**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.integration.goszakup.LotTechSpecParseTest"`
Expected: FAIL (compilation).

- [ ] **Step 3: DTO + интерфейс**

`src/main/java/com/vladoose/nir/integration/goszakup/dto/LotTechSpecRef.java`:

```java
package com.vladoose.nir.integration.goszakup.dto;

/** Ссылка на файл «Техническая спецификация» лота на goszakup. ambiguous — имя лота в тендере не уникально. */
public record LotTechSpecRef(String filePath, String originalName, boolean ambiguous) {}
```

В `GoszakupClient.java` добавить (импорт `com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef`):

```java
    /** Файл «Техническая спецификация» лота (матч по name_ru); null — лот/файл не найден. */
    LotTechSpecRef fetchLotTechSpec(String numberAnno, String lotNameRu);
    /** Скачать файл по прямому URL площадки (Bearer-токен); null — файл не найден (404). */
    byte[] downloadFile(String url);
```

- [ ] **Step 4: Реализация в `GoszakupHttpClient`**

Добавить методы (импорт `com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef`):

```java
    @Override
    public LotTechSpecRef fetchLotTechSpec(String numberAnno, String lotNameRu) {
        // живой формат подтверждён 2026-07-04: Files лежат на уровне лота, техспека — nameRu="Техническая спецификация"
        String query = "query($anno:String,$l:Int){ Lots(filter:{trdBuyNumberAnno:$anno}, limit:$l){ "
                + "lotNumber nameRu Files{ nameRu originalName filePath } } }";
        try {
            ObjectNode vars = objectMapper.createObjectNode();
            vars.put("anno", numberAnno);
            vars.put("l", 100); // многолотовые тендеры — до ~50 лотов
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            body.set("variables", vars);
            JsonNode root = objectMapper.readTree(rawPost(graphqlUrl(), objectMapper.writeValueAsBytes(body)));
            if (root.path("errors").size() > 0) {
                throw new IllegalStateException("goszakup v3 GraphQL: " + root.get("errors"));
            }
            return parseLotTechSpec(root, lotNameRu);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("goszakup v3: разбор JSON: " + e.getMessage(), e);
        }
    }

    /** Чистая функция: из ответа v3 Lots достать файл техспеки лота по name_ru (trim, case-insensitive). */
    static LotTechSpecRef parseLotTechSpec(JsonNode root, String lotNameRu) {
        String wanted = lotNameRu == null ? "" : lotNameRu.trim();
        LotTechSpecRef first = null;
        int matched = 0;
        for (JsonNode lot : root.path("data").path("Lots")) {
            if (!wanted.equalsIgnoreCase(lot.path("nameRu").asText("").trim())) continue;
            for (JsonNode f : lot.path("Files")) {
                if (!"Техническая спецификация".equalsIgnoreCase(f.path("nameRu").asText("").trim())) continue;
                matched++;
                if (first == null) {
                    first = new LotTechSpecRef(f.path("filePath").asText(null),
                            f.path("originalName").asText(null), false);
                }
                break; // один файл техспеки на лот
            }
        }
        if (first == null) return null;
        return matched > 1 ? new LotTechSpecRef(first.filePath(), first.originalName(), true) : first;
    }

    @Override
    public byte[] downloadFile(String url) {
        // без Accept: application/json — отдаётся бинарник (octet-stream)
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(60)).build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) return null; // файл удалили/протух hash — вызывающий решает (404 к лоту)
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("goszakup download " + resp.statusCode() + " на " + url);
            }
            return resp.body();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("goszakup download недоступен: " + e.getMessage(), e);
        }
    }
```

- [ ] **Step 5: Фейк**

В `FakeGoszakupClient.java` добавить (импорт `LotTechSpecRef`):

```java
    /** ключ: numberAnno + "|" + lotNameRu → ссылка на техспеку (null-ключей нет — отсутствие = null). */
    public final Map<String, LotTechSpecRef> techSpecByKey = new HashMap<>();
    /** filePath → байты файла. */
    public final Map<String, byte[]> filesByUrl = new HashMap<>();

    @Override public LotTechSpecRef fetchLotTechSpec(String numberAnno, String lotNameRu) {
        return techSpecByKey.get(numberAnno + "|" + lotNameRu);
    }
    @Override public byte[] downloadFile(String url) {
        byte[] b = filesByUrl.get(url);
        if (b == null) throw new IllegalStateException("fake: нет файла " + url);
        return b;
    }
```

- [ ] **Step 6: Прогнать — зелёный (+ компиляция всего теста)**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.integration.goszakup.LotTechSpecParseTest" --tests "com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest"`
Expected: PASS (наш 4/4 + импорт-тесты не сломаны фейком).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/integration/goszakup/dto/LotTechSpecRef.java \
  src/main/java/com/vladoose/nir/integration/goszakup/GoszakupClient.java \
  src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java \
  src/test/java/com/vladoose/nir/integration/goszakup/FakeGoszakupClient.java \
  src/test/java/com/vladoose/nir/integration/goszakup/LotTechSpecParseTest.java
git commit -m "feat(goszakup): fetchLotTechSpec (v3 Lots.Files) + downloadFile — ссылка и скачивание техспеки лота

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `TechSpecService` + `TechSpecWriter` + исключения 422/502 + эндпоинт

**Files:**
- Create: `src/main/java/com/vladoose/nir/exception/UnprocessableException.java`
- Create: `src/main/java/com/vladoose/nir/exception/UpstreamException.java`
- Modify: `src/main/java/com/vladoose/nir/exception/GlobalExceptionHandler.java`
- Create: `src/main/java/com/vladoose/nir/service/TechSpecWriter.java`
- Create: `src/main/java/com/vladoose/nir/service/TechSpecService.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/ParseTechSpecResponse.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderLotController.java`
- Test: `src/test/java/com/vladoose/nir/service/TechSpecServiceTest.java`

**Interfaces:**
- Consumes: `PdfTextExtractor.extract` (T1), `TechSpecExtractor.russianSection` (T2), `GoszakupClient.fetchLotTechSpec/downloadFile` + `FakeGoszakupClient.techSpecByKey/filesByUrl` (T3), `SpecConstraintExtractor.extract`, `TenderLotService.findById`, `TenderLotRepository`, `TenderLotMapper.toResponse`.
- Produces:
  - `TechSpecWriter.apply(Long lotId, String text, SpecConstraintExtractor.SpecConstraints c): TenderLot` — `requiredSpec=text` всегда; `max*` — только найденные значения (не найденное НЕ затирает);
  - `TechSpecService.parse(Long lotId): ParseResult`; `record ParseResult(TenderLot lot, boolean dimsFound, boolean weightFound, boolean ambiguous, String source)`;
  - `POST /api/lots/{id}/parse-techspec` (ADMIN) → `ParseTechSpecResponse {lot: TenderLotResponse, specFound: true, dimsFound, weightFound, ambiguous, source}`;
  - исключения: `UnprocessableException` → 422, `UpstreamException` → 502 (ApiError-формат).

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/service/TechSpecServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UnprocessableException;
import com.vladoose.nir.integration.goszakup.FakeGoszakupClient;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.util.SpecConstraintExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class TechSpecServiceTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired TenderLotService tenderLotService;
    @Autowired TechSpecWriter writer;

    FakeGoszakupClient fake;
    TechSpecService service;
    Tender tender;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGoszakupClient();
        service = new TechSpecService(tenderLotService, fake, writer);

        tender = new Tender();
        tender.setTenderNumber("17280874-1");
        tender.setStatus("ACTIVE");
        tender.setSourceExtId("17280874-1");
        tenderRepository.save(tender);

        lot = new TenderLot();
        lot.setTender(tender);
        lot.setEquipName("Пульсоксиметр");
        lot.setQuantity(5);
        lot.setRequiredSpec("медицинский");
        tenderLotRepository.save(lot);
    }

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    private byte[] fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/goszakup/techspec-pulse.pdf")) {
            return in.readAllBytes();
        }
    }

    @Test
    void parsesRealPdf_writesRussianSectionToLot() throws Exception {
        fake.techSpecByKey.put("17280874-1|Пульсоксиметр",
                new LotTechSpecRef("http://f/1", "techspec_17280874_.pdf", false));
        fake.filesByUrl.put("http://f/1", fixture());

        TechSpecService.ParseResult r = service.parse(lot.getId());

        assertThat(r.lot().getRequiredSpec())
                .contains("Диапазон измерения сатурации")
                .doesNotContain("Лоттың"); // казахская секция отрезана
        assertThat(r.dimsFound()).isFalse();   // у пульсоксиметра габаритов в ТЗ нет
        assertThat(r.weightFound()).isFalse();
        assertThat(r.ambiguous()).isFalse();
        assertThat(r.source()).isEqualTo("techspec_17280874_.pdf");
        assertThat(tenderLotRepository.findById(lot.getId()).orElseThrow().getRequiredSpec())
                .contains("Диапазон измерения сатурации");
    }

    @Test
    void writerSemantics_dimsWrittenAndNotErasedByNextParse() {
        var c1 = SpecConstraintExtractor.extract("Габариты не более 1200х800х1300 мм, вес не более 45 кг");
        writer.apply(lot.getId(), "ТЗ раз", c1);
        TenderLot l1 = tenderLotRepository.findById(lot.getId()).orElseThrow();
        assertThat(l1.getMaxLengthMm()).isEqualTo(1200);
        assertThat(l1.getMaxWeightKg()).isEqualByComparingTo(new BigDecimal("45"));
        assertThat(l1.getRequiredSpec()).isEqualTo("ТЗ раз");

        var empty = SpecConstraintExtractor.extract("никаких чисел");
        writer.apply(lot.getId(), "ТЗ два", empty);
        TenderLot l2 = tenderLotRepository.findById(lot.getId()).orElseThrow();
        assertThat(l2.getRequiredSpec()).isEqualTo("ТЗ два"); // текст перезаписан
        assertThat(l2.getMaxLengthMm()).isEqualTo(1200);       // габариты НЕ затёрты
        assertThat(l2.getMaxWeightKg()).isEqualByComparingTo(new BigDecimal("45"));
    }

    @Test
    void manualTenderRejected() {
        tender.setSourceExtId(null);
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("импортирован");
    }

    @Test
    void tokenNotConfiguredRejected() {
        fake.configured = false;
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("GOSZAKUP_TOKEN");
    }

    @Test
    void foreignMarketLotHidden() {
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void techSpecFileNotFound_lotUntouched() {
        // фейк ничего не знает про лот → ref == null
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Техническая спецификация");
        assertThat(tenderLotRepository.findById(lot.getId()).orElseThrow().getRequiredSpec())
                .isEqualTo("медицинский");
    }

    @Test
    void unreadablePdf_lotUntouched() {
        fake.techSpecByKey.put("17280874-1|Пульсоксиметр", new LotTechSpecRef("http://f/2", "x.pdf", false));
        fake.filesByUrl.put("http://f/2", "не pdf".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(UnprocessableException.class);
        assertThat(tenderLotRepository.findById(lot.getId()).orElseThrow().getRequiredSpec())
                .isEqualTo("медицинский");
    }
}
```

- [ ] **Step 2: Прогнать — падает (компиляция).**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.TechSpecServiceTest"`
Expected: FAIL (compilation).

- [ ] **Step 3: Исключения + хендлеры**

`src/main/java/com/vladoose/nir/exception/UnprocessableException.java`:

```java
package com.vladoose.nir.exception;

/** 422: вход получен, но обработать содержимое невозможно (напр., нечитаемый PDF). */
public class UnprocessableException extends RuntimeException {
    public UnprocessableException(String message) { super(message); }
}
```

`src/main/java/com/vladoose/nir/exception/UpstreamException.java`:

```java
package com.vladoose.nir.exception;

/** 502: внешний сервис (goszakup и т.п.) недоступен или ответил ошибкой. */
public class UpstreamException extends RuntimeException {
    public UpstreamException(String message, Throwable cause) { super(message, cause); }
}
```

В `GlobalExceptionHandler.java` добавить два хендлера (рядом с `handleBadRequest`, тот же ApiError-стиль):

```java
    @ExceptionHandler(UnprocessableException.class)
    public ResponseEntity<ApiError> handleUnprocessable(UnprocessableException ex) {
        ApiError error = ApiError.builder()
                .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                .message(ex.getMessage())
                .errors(null)
                .build();
        return ResponseEntity.unprocessableEntity().body(error);
    }

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<ApiError> handleUpstream(UpstreamException ex) {
        ApiError error = ApiError.builder()
                .status(HttpStatus.BAD_GATEWAY.value())
                .message(ex.getMessage())
                .errors(null)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
    }
```

- [ ] **Step 4: Writer**

`src/main/java/com/vladoose/nir/service/TechSpecWriter.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.util.SpecConstraintExtractor.SpecConstraints;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Запись разобранного ТЗ в лот отдельным транзакционным бином (сеть — вне транзакции, §6).
 * Текст пишется всегда; габариты/вес — только найденные (не найденное не затирает ручное/прежнее).
 */
@Service
public class TechSpecWriter {

    private final TenderLotRepository lotRepository;

    public TechSpecWriter(TenderLotRepository lotRepository) {
        this.lotRepository = lotRepository;
    }

    @Transactional
    public TenderLot apply(Long lotId, String text, SpecConstraints c) {
        TenderLot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        lot.setRequiredSpec(text);
        if (c != null) {
            if (c.maxLengthMm() != null) lot.setMaxLengthMm(c.maxLengthMm());
            if (c.maxWidthMm() != null) lot.setMaxWidthMm(c.maxWidthMm());
            if (c.maxHeightMm() != null) lot.setMaxHeightMm(c.maxHeightMm());
            if (c.maxWeightKg() != null) lot.setMaxWeightKg(c.maxWeightKg());
        }
        return lotRepository.save(lot);
    }
}
```

- [ ] **Step 5: Сервис**

`src/main/java/com/vladoose/nir/service/TechSpecService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UnprocessableException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import com.vladoose.nir.util.PdfTextExtractor;
import com.vladoose.nir.util.SpecConstraintExtractor;
import com.vladoose.nir.util.TechSpecExtractor;
import org.springframework.stereotype.Service;

/**
 * On-demand разбор «Технической спецификации» импортного лота: v3 Lots.Files → PDF → текст →
 * русская секция → габариты/вес → запись в лот (TechSpecWriter, транзакция только на запись).
 */
@Service
public class TechSpecService {

    public record ParseResult(TenderLot lot, boolean dimsFound, boolean weightFound,
                              boolean ambiguous, String source) {}

    private final TenderLotService tenderLotService;
    private final GoszakupClient client;
    private final TechSpecWriter writer;

    public TechSpecService(TenderLotService tenderLotService, GoszakupClient client, TechSpecWriter writer) {
        this.tenderLotService = tenderLotService;
        this.client = client;
        this.writer = writer;
    }

    public ParseResult parse(Long lotId) {
        TenderLot lot = tenderLotService.findById(lotId);
        // findById = em.find обходит фильтр рынка → явный гард
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }
        String anno = lot.getTender().getSourceExtId();
        if (anno == null || anno.isBlank()) {
            throw new BadRequestException("ТЗ доступно только у импортированных с goszakup тендеров");
        }
        if (!client.isConfigured()) {
            throw new BadRequestException("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
        }

        LotTechSpecRef ref;
        byte[] pdf;
        try {
            ref = client.fetchLotTechSpec(anno, lot.getEquipName());
            if (ref == null || ref.filePath() == null) {
                throw new NotFoundException("Файл «Техническая спецификация» не найден у лота на goszakup");
            }
            pdf = client.downloadFile(ref.filePath());
        } catch (IllegalStateException e) {
            throw new UpstreamException("goszakup недоступен: " + e.getMessage(), e);
        }
        if (pdf == null) { // 404 при скачивании (hash протух/файл удалён)
            throw new NotFoundException("Файл «Техническая спецификация» недоступен для скачивания на goszakup");
        }

        String fullText = PdfTextExtractor.extract(pdf);
        String text = TechSpecExtractor.russianSection(fullText);
        if (text == null || text.isBlank()) {
            throw new UnprocessableException("Не удалось извлечь текст из PDF техспецификации");
        }

        SpecConstraintExtractor.SpecConstraints c = SpecConstraintExtractor.extract(text);
        TenderLot saved = writer.apply(lotId, text, c);
        return new ParseResult(saved, c.maxLengthMm() != null, c.maxWeightKg() != null,
                ref.ambiguous(), ref.originalName());
    }
}
```

- [ ] **Step 6: DTO + эндпоинт**

`src/main/java/com/vladoose/nir/dto/response/ParseTechSpecResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class ParseTechSpecResponse {
    private TenderLotResponse lot;
    private boolean specFound;   // true на 200 (ошибки идут кодами 4xx/5xx)
    private boolean dimsFound;
    private boolean weightFound;
    private boolean ambiguous;   // имя лота в тендере не уникально — взят первый с файлом
    private String source;       // originalName файла техспеки
}
```

В `TenderLotController.java`: зависимость `TechSpecService techSpecService` (поле + параметр конструктора + присваивание), импорты `com.vladoose.nir.dto.response.ParseTechSpecResponse`, `com.vladoose.nir.service.TechSpecService`, и метод:

```java
    /** Скачать и разобрать «Техническую спецификацию» импортного лота с goszakup в поля лота. */
    @PostMapping("/{id}/parse-techspec")
    @PreAuthorize("hasRole('ADMIN')")
    public ParseTechSpecResponse parseTechSpec(@PathVariable Long id) {
        TechSpecService.ParseResult r = techSpecService.parse(id);
        ParseTechSpecResponse resp = new ParseTechSpecResponse();
        resp.setLot(mapper.toResponse(r.lot()));
        resp.setSpecFound(true);
        resp.setDimsFound(r.dimsFound());
        resp.setWeightFound(r.weightFound());
        resp.setAmbiguous(r.ambiguous());
        resp.setSource(r.source());
        return resp;
    }
```

- [ ] **Step 7: Прогнать — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.TechSpecServiceTest" --tests "com.vladoose.nir.tender.LotProposedEquipmentTest"`
Expected: PASS (7/7 + контроллер-сосед не сломан 6-м параметром конструктора — проверит Spring-контекст).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/exception/UnprocessableException.java \
  src/main/java/com/vladoose/nir/exception/UpstreamException.java \
  src/main/java/com/vladoose/nir/exception/GlobalExceptionHandler.java \
  src/main/java/com/vladoose/nir/service/TechSpecWriter.java \
  src/main/java/com/vladoose/nir/service/TechSpecService.java \
  src/main/java/com/vladoose/nir/dto/response/ParseTechSpecResponse.java \
  src/main/java/com/vladoose/nir/controller/TenderLotController.java \
  src/test/java/com/vladoose/nir/service/TechSpecServiceTest.java
git commit -m "feat(techspec): TechSpecService/Writer + POST /api/lots/{id}/parse-techspec (+422/502 в ApiError)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Фронт — кнопка «ТЗ» + дополнение noCriteria-баннера

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`
- Modify: `frontend/src/app/components/smart-match/smart-match.component.ts`

**Interfaces:**
- Consumes: `POST /api/lots/{id}/parse-techspec` (T4) → `{lot, specFound, dimsFound, weightFound, ambiguous, source}`.
- Produces: `ApiService.parseLotTechSpec(lotId): Observable<any>`; кнопка «ТЗ» в строке лота (только импортные тендеры).

- [ ] **Step 1: ApiService**

В `api.service.ts` после `clearProposedEquipment` добавить:

```ts
  /** Скачать и разобрать «Техническую спецификацию» импортного лота с goszakup. */
  parseLotTechSpec(lotId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/lots/${lotId}/parse-techspec`, {});
  }
```

- [ ] **Step 2: Кнопка в строке лота**

В `tenders.component.ts` в ячейке actions строки лота перед кнопкой «КП» добавить:

```html
              <button class="btn btn-tz" *ngIf="isImportedTender()" [disabled]="tzBusy.has(l.id)"
                      (click)="parseTechSpec(l)" title="Скачать и разобрать техспецификацию с goszakup">
                {{ tzBusy.has(l.id) ? '…' : 'ТЗ' }}
              </button>
```

В styles рядом с `.btn-kp`:

```css
    .btn-tz { background: #6366f1; color: #fff; margin-right: 4px; }
    .btn-tz:disabled { opacity: 0.6; cursor: wait; }
```

В класс (рядом с `lotSel`):

```ts
  tzBusy = new Set<number>();
```

Методы (рядом с `openKpPanelFor`):

```ts
  isImportedTender(): boolean {
    return this.isKz() && /^\d+-\d+$/.test(this.selectedTender?.tenderNumber || '');
  }

  parseTechSpec(l: any) {
    this.tzBusy.add(l.id);
    this.cdr.detectChanges();
    this.api.parseLotTechSpec(l.id).subscribe({
      next: (r) => {
        this.tzBusy.delete(l.id);
        const dims = r.dimsFound ? 'габариты ✓' : 'габариты —';
        const weight = r.weightFound ? 'вес ✓' : 'вес —';
        const amb = r.ambiguous ? ' (неоднозначный матч лота — проверьте вручную)' : '';
        const specLen = (r.lot?.requiredSpec || '').length;
        this.notify.success(`ТЗ разобрано: спека ${specLen} симв., ${dims}, ${weight}${amb}`);
        this.loadLots();
      },
      error: (e) => {
        this.tzBusy.delete(l.id);
        this.notify.error(e.error?.message || e.message || 'Не удалось разобрать ТЗ');
        this.cdr.detectChanges();
      }
    });
  }
```

- [ ] **Step 3: Дополнить noCriteria-баннер smart-match**

В `smart-match.component.ts` заменить текст баннера:

```html
      <div class="sm-nocriteria" *ngIf="result?.noCriteria">
        🚫 Недостаточно данных для подбора: у лота нет типа оборудования и габаритов/веса, а в спецификации их распознать не удалось.
        Нажмите «ТЗ» у лота — система скачает и разберёт техспецификацию с goszakup, — либо задайте тип/габариты вручную («Редактировать»).
      </div>
```

- [ ] **Step 4: Сборка фронта**

Run: `cd frontend && npm run build` (из корня репо; при sandbox-ошибке — sandbox off)
Expected: успех, без ошибок TS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/api.service.ts \
  frontend/src/app/pages/tenders/tenders.component.ts \
  frontend/src/app/components/smart-match/smart-match.component.ts
git commit -m "feat(ui): кнопка «ТЗ» у импортного лота — разбор техспеки с goszakup + подсказка в noCriteria-баннере

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Финальный гейт — полный прогон, live-проверка, CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (§8 «Реализованные блоки», §15 API, §16 бэклог)

- [ ] **Step 1: Полный бэкенд-прогон**

```bash
lsof -ti :8080 | xargs kill -9 || true
./gradlew test
```
(sandbox off). Expected: только 2 известных падения `ApplyAutoFillServiceTest` (сейчас в сумме 130+ тестов). Иное — чинить.

- [ ] **Step 2: Сборка фронта**

Run: `cd frontend && npm run build` → успех.

- [ ] **Step 3: Live-проверка (Playwright, живой токен)**

Бэк: `GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" ./gradlew bootRun` (фон); фронт :4200; логин admin/admin; рынок KZ.
1. `/tenders?openId=800` (тендер 17280874-1) → в строке лота «Пульсоксиметр» есть кнопка **«ТЗ»**.
2. Клик «ТЗ» → кнопка «…» → тост «ТЗ разобрано: спека N симв., габариты —, вес —» → в ячейке «Спецификация» русская ТТХ («Портативное устройство… Диапазон измерения сатурации: 0-100…»), казахского текста нет.
3. «Подобрать» → честно остаётся `noCriteria` (у пульсоксиметра габаритов в ТЗ нет — корректно), баннер теперь упоминает кнопку «ТЗ».
4. РФ-рынок → карточка тендера → кнопки «ТЗ» НЕТ; ручной KZ-тендер (номер вида KZ-2026-…) → кнопки «ТЗ» НЕТ.
5. Поискать в ленте лот габаритной техники (открыть 2–3 тендера с «кровать»/«УЗИ»/«рентген» в названии лота, нажать «ТЗ») — если в ТЗ есть «не более …х…х…» → габариты появились в колонке «Габариты (макс.)» и «Подобрать» оживает. Не нашли такой лот — не блокер (семантика покрыта `TechSpecServiceTest.writerSemantics…`).
Скриншоты ключевых состояний.

- [ ] **Step 4: CLAUDE.md**

В §8 добавить пункт (после пункта про лотовый запрос КП):

```markdown
- **Разбор техспеки лота goszakup (кнопка «ТЗ», только импортные KZ-тендеры):** `POST /api/lots/{id}/parse-techspec` → `TechSpecService`: v3 GraphQL `Lots(filter:{trdBuyNumberAnno}){ Files }` (файл `nameRu="Техническая спецификация"`, матч лота по `equipName==nameRu` case-insens; дубли имён → первый + `ambiguous`) → скачивание `filePath` Bearer-токеном (`downloadFile`, без Accept-заголовка) → PDFBox (`PdfTextExtractor`, мусор → null) → `TechSpecExtractor.russianSection` (PDF двуязычный: казахская секция, потом русская «Приложение 2…»; маркеры «приложение»/«техническая спецификация») → `SpecConstraintExtractor` → `TechSpecWriter` (@Transactional отдельный бин, сеть вне tx §6): `requiredSpec` перезаписывается всегда, `max*`-поля — только найденными значениями (не затирает ручное). Ошибки: ручной тендер/нет токена → 400, файла нет → 404, PDF не читается → 422 (`UnprocessableException`), сеть → 502 (`UpstreamException`) — лот не трогается. Фикстур реальной техспеки: `src/test/resources/goszakup/techspec-pulse.pdf`. У простых товаров (пульсоксиметр) габаритов в ТЗ нет — после разбора подбор честно остаётся `noCriteria`.
```

В §15 добавить: `` `/api/lots/{id}/parse-techspec` (POST) ``.

В §16 добавить: «Авто-разбор ТЗ при импорте / фоновая очередь (сейчас — только по кнопке)», «LLM-фолбэк разбора сложных/табличных ТЗ (опт-ин)», «Хранить goszakup lot_number при импорте — точный матч лота вместо имени (уберёт ambiguous)».

- [ ] **Step 5: Commit + отчёт**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md — разбор техспеки лота goszakup (кнопка «ТЗ»)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Далее: whole-branch review (Opus) → мерж `--ff-only` в main → удалить ветку (superpowers:finishing-a-development-branch). Тур «куда смотреть» пользователю.

---

## Порядок и зависимости

```
T1 (PDFBox+extract) ─┬─→ T4 (service+writer+endpoint) ─→ T5 (фронт) ─→ T6 (гейт+live)
T2 (russianSection) ─┤
T3 (client+fake) ────┘
```

T1–T3 независимы друг от друга, T4 нужны все три. Последовательное исполнение T1→T6 корректно.
