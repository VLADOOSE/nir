# Описание кандидата в панели «Реестр» — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Разворот строки кандидата в панели «Реестр» показывает официальное описание изделия из карточки НЦЭЛС (назначение, область применения, класс риска, краткие тех. характеристики, вид МИ) рядом с ТЗ лота; карточка тянется live при первом просмотре и кешируется в `med_registry`.

**Architecture:** Новый HTTP-клиент `integration/ndda` (публичный API oldregister.ndda.kz, без auth: POST list-фильтр по № РУ → внутренний id, GET MtMainGetById → карточка) → `RegistryDetailService` (сеть вне транзакции) + `RegistryDetailWriter` (@Transactional отдельный бин, паттерн TechSpecWriter) пишут кеш в новые nullable-колонки `med_registry` (Flyway V5) → `GET /api/registry/detail?regNumber=…` → разворот-аккордеон в панели «Реестр» (`tenders.component.ts`). Бонус: `adoptForLot` кладёт закешированные характеристики в `spec` новой позиции каталога.

**Tech Stack:** Java 17 / Spring Boot 3.5.6, `java.net.http.HttpClient`, Jackson, Flyway (V5), JUnit 5 + AssertJ + JDK HttpServer-стаб (паттерн `GoszakupHttpClientTest`) + `@MockitoBean`, Angular 21 (инлайн-шаблон `tenders.component.ts`).

**Spec:** `docs/superpowers/specs/2026-07-05-registry-candidate-description-design.md`

## Global Constraints

- Все `./gradlew`-команды — `dangerouslyDisableSandbox: true` (sandbox блокирует localhost:5432); запускать из корня репо (`cd /Users/vlad/IdeaProjects/AIS && …`).
- Перед `./gradlew test` глушить лишний bootRun: `lsof -ti :8080 | xargs kill -9` (может никого не убить — это ок).
- Гейт «зелёного» для `./gradlew test`: падают ТОЛЬКО 2 пред-существующих `ApplyAutoFillServiceTest`.
- Каждый commit заканчивать trailer-строкой: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Работа на ветке `feat/registry-candidate-detail` (создаётся в Task 1, Step 0).
- После крупных Edit в `RegistryMatchService.java` / `tenders.component.ts` — проверka на дубли методов (`grep -c "имяМетода"`) + компиляция (§14 CLAUDE.md).
- Схему менять ТОЛЬКО новой миграцией V5 (V1–V4 не трогать).
- `med_registry` — общая сущность (без market): рыночные фильтры/штампы в этой фиче не участвуют.

---

### Task 1: Flyway V5 + поля кеша в entity `MedRegistry`

**Files:**
- Create: `src/main/resources/db/migration/V5__registry_detail_cache.sql`
- Modify: `src/main/java/com/vladoose/nir/entity/MedRegistry.java` (добавить 8 полей после `importedAt`)

**Interfaces:**
- Produces: колонки `med_registry.ndda_id/risk_class/purpose/use_area/tech_chars/mi_kind/mi_kind_def/detail_fetched_at`; геттеры/сеттеры Lombok `getNddaId()/getRiskClass()/getPurpose()/getUseArea()/getTechChars()/getMiKind()/getMiKindDef()/getDetailFetchedAt()` (+set-парные) — на них опираются Task 3 и Task 4.
- Consumes: —

- [ ] **Step 0: Ветка**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout -b feat/registry-candidate-detail
```

- [ ] **Step 1: Миграция V5**

Создать `src/main/resources/db/migration/V5__registry_detail_cache.sql`:

```sql
-- Кеш карточки НЦЭЛС (детали РУ). Наполняется on-demand при первом просмотре описания
-- кандидата в панели «Реестр»; detail_fetched_at — маркер «карточку уже тянули»
-- (ставится и при «РУ на портале не найден», чтобы не долбить портал повторно).
-- Реимпорт дампа (RegistryImportService, ON CONFLICT DO UPDATE) эти колонки не трогает.
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS ndda_id           BIGINT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS risk_class        TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS purpose           TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS use_area          TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS tech_chars        TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS mi_kind           TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS mi_kind_def       TEXT;
ALTER TABLE med_registry ADD COLUMN IF NOT EXISTS detail_fetched_at TIMESTAMPTZ;
```

- [ ] **Step 2: Поля entity**

В `src/main/java/com/vladoose/nir/entity/MedRegistry.java` после поля `importedAt` добавить (импорты `OffsetDateTime`/`LocalDate` уже есть):

```java
    // --- Кеш карточки НЦЭЛС (on-demand, см. RegistryDetailService) ---

    @Column(name = "ndda_id")
    private Long nddaId;

    @Column(name = "risk_class", columnDefinition = "TEXT")
    private String riskClass;

    @Column(columnDefinition = "TEXT")
    private String purpose;

    @Column(name = "use_area", columnDefinition = "TEXT")
    private String useArea;

    @Column(name = "tech_chars", columnDefinition = "TEXT")
    private String techChars;

    @Column(name = "mi_kind", columnDefinition = "TEXT")
    private String miKind;

    @Column(name = "mi_kind_def", columnDefinition = "TEXT")
    private String miKindDef;

    @Column(name = "detail_fetched_at")
    private OffsetDateTime detailFetchedAt;
```

- [ ] **Step 3: Компиляция + миграция накатывается + реимпорт не затирает**

```bash
cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9; ./gradlew test --tests "com.vladoose.nir.service.RegistryImportServiceTest" 2>&1 | tail -15
```
(с `dangerouslyDisableSandbox: true`)

Expected: `BUILD SUCCESSFUL`, тесты импорта зелёные (Flyway накатил V5 на nirdb при старте контекста; UPSERT в `RegistryImportService` перечисляет только slim-колонки — кеш не затрагивается, ничего менять в нём не нужно).

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V5__registry_detail_cache.sql src/main/java/com/vladoose/nir/entity/MedRegistry.java && git commit -m "feat(registry): V5 — колонки кеша карточки НЦЭЛС в med_registry

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: HTTP-клиент НЦЭЛС (`integration/ndda`)

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/ndda/NddaClient.java`
- Create: `src/main/java/com/vladoose/nir/integration/ndda/NddaHttpClient.java`
- Create: `src/main/java/com/vladoose/nir/integration/ndda/dto/NddaListItemDto.java`
- Create: `src/main/java/com/vladoose/nir/integration/ndda/dto/NddaDetailDto.java`
- Modify: `src/main/resources/application.yaml` (секция `ndda` после секции `goszakup`, строки ~65–73)
- Modify: `src/main/java/com/vladoose/nir/exception/UpstreamException.java` (добавить одноаргументный конструктор — сейчас есть только `(String, Throwable)`)
- Test: `src/test/java/com/vladoose/nir/integration/ndda/NddaHttpClientTest.java`

**Interfaces:**
- Consumes: `com.vladoose.nir.exception.UpstreamException` (существующий, маппится GlobalExceptionHandler в 502).
- Produces: `NddaClient` — `Long resolveId(String regNumber)` (null = РУ на портале не найден), `NddaDetailDto fetchDetail(long id)`; `NddaDetailDto` — геттеры `getPurpose()/getUseArea()/getDegreeRiskName()/getShortTechnicalCharacteristicsRu()/getTermNameRus()/getTermDefinition()`. На них опирается Task 3.

Живые формы ответов сняты 2026-07-05 c `https://oldregister.ndda.kz/register-backend` (curl, без auth); фикстуры — inline в тесте (паттерн проекта `GoszakupHttpClientTest`, НЕ файлы в resources — уточнение спеки).

- [ ] **Step 1: Написать failing-тест**

Создать `src/test/java/com/vladoose/nir/integration/ndda/NddaHttpClientTest.java`:

```java
package com.vladoose.nir.integration.ndda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Живые формы ответов oldregister.ndda.kz (сняты 2026-07-05; стаб на JDK HttpServer, без сети). */
class NddaHttpClientTest {

    static HttpServer server;
    static volatile String lastPath;
    static volatile String lastQuery;
    static volatile String lastRequestBody;
    static volatile String nextBody = "{}";
    static volatile int nextStatus = 200;
    static NddaHttpClient client;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            lastPath = ex.getRequestURI().getPath();
            lastQuery = ex.getRequestURI().getQuery();
            lastRequestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] b = nextBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(nextStatus, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        client = new NddaHttpClient(new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/register-backend");
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @BeforeEach
    void resetStatus() { nextStatus = 200; }

    @Test
    void resolveId_postsListFilteredByRegNumber_andReturnsId() {
        nextBody = """
            [{"id":182621,"reg_number":"РК МИ (ИМН)-0№031074",
              "name":"Раствор офтальмологический вискоэластичный Адгевиск",
              "producerNameRu":"Гротекс","countryNameRu":"РОССИЯ",
              "purpose":null,"shortTechnicalCharacteristicsRu":null}]
            """;
        Long id = client.resolveId("РК МИ (ИМН)-0№031074");
        assertThat(lastPath).isEqualTo("/register-backend/RegisterService/list");
        assertThat(lastRequestBody).contains("\"regNumber\":\"РК МИ (ИМН)-0№031074\"");
        assertThat(lastRequestBody).contains("\"regTypeId\":2");
        assertThat(id).isEqualTo(182621L);
    }

    @Test
    void resolveId_unknownRegNumber_returns200EmptyArray_treatedAsNull() {
        nextBody = "[]"; // живой API на неизвестный № РУ отвечает 200 и [], не 404
        assertThat(client.resolveId("РК МИ (ИМН)-0№999999")).isNull();
    }

    @Test
    void fetchDetail_getsMtMainGetById_andParsesDescriptionFields() {
        // живая форма карточки: ключевой набор полей (термин вида МИ приходит в termName_rus)
        nextBody = """
            {"id":182621,"regNumber":"РК МИ (ИМН)-0№031074",
             "tradeName":"Раствор офтальмологический вискоэластичный Адгевиск",
             "purpose":"Адгевиск поддерживает глубину передней камеры и улучшает визуализацию",
             "useArea":"Область применения – офтальмологическая хирургия",
             "degreeRiskName":"Класс 2 б – с повышенной степенью риска",
             "shortTechnicalCharacteristicsRu":"Прозрачный вязкий раствор. рН – 7,0 – 7,5; вязкость – 40 000 мПа·с",
             "shortTechnicalCharacteristicsKz":"Мөлдір тұтқыр ерітінді",
             "comments":"Хранить при температуре от + 2 °С до + 8 °С",
             "termName_rus":"Материал для замещения водянистой влаги, интраоперационное",
             "termDefinition":"Неимплантируемое искусственное вискоэластичное вещество",
             "nmirkCode":259124,"sterilitySign":true}
            """;
        NddaDetailDto d = client.fetchDetail(182621L);
        assertThat(lastPath).isEqualTo("/register-backend/RegisterService/MtMainGetById");
        assertThat(lastQuery).isEqualTo("Id=182621");
        assertThat(d.getPurpose()).startsWith("Адгевиск поддерживает");
        assertThat(d.getUseArea()).contains("офтальмологическая хирургия");
        assertThat(d.getDegreeRiskName()).isEqualTo("Класс 2 б – с повышенной степенью риска");
        assertThat(d.getShortTechnicalCharacteristicsRu()).contains("40 000 мПа·с");
        assertThat(d.getTermNameRus()).startsWith("Материал для замещения");
        assertThat(d.getTermDefinition()).startsWith("Неимплантируемое");
    }

    @Test
    void non200_throwsUpstreamException() {
        nextStatus = 500;
        nextBody = "oops";
        assertThatThrownBy(() -> client.fetchDetail(1L)).isInstanceOf(UpstreamException.class);
    }
}
```

- [ ] **Step 2: Убедиться, что тест красный (не компилируется — классов нет)**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava 2>&1 | tail -5
```
(с `dangerouslyDisableSandbox: true`)

Expected: `BUILD FAILED` — `package com.vladoose.nir.integration.ndda does not exist`.

- [ ] **Step 3: Реализация — DTO**

Создать `src/main/java/com/vladoose/nir/integration/ndda/dto/NddaListItemDto.java`:

```java
package com.vladoose.nir.integration.ndda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Элемент ответа RegisterService/list (используем только id; живая форма — NddaHttpClientTest). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NddaListItemDto {
    private Long id;
    @JsonProperty("reg_number")
    private String regNumber;
}
```

Создать `src/main/java/com/vladoose/nir/integration/ndda/dto/NddaDetailDto.java`:

```java
package com.vladoose.nir.integration.ndda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Карточка РУ (RegisterService/MtMainGetById) — только поля описания; живая форма — NddaHttpClientTest. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NddaDetailDto {
    private Long id;
    private String regNumber;
    private String purpose;
    private String useArea;
    private String degreeRiskName;
    private String shortTechnicalCharacteristicsRu;
    @JsonProperty("termName_rus")
    private String termNameRus;
    private String termDefinition;
}
```

- [ ] **Step 4: Реализация — интерфейс и HTTP-клиент**

В `src/main/java/com/vladoose/nir/exception/UpstreamException.java` добавить одноаргументный конструктор (класс станет):

```java
package com.vladoose.nir.exception;

/** 502: внешний сервис (goszakup, НЦЭЛС и т.п.) недоступен или ответил ошибкой. */
public class UpstreamException extends RuntimeException {
    public UpstreamException(String message) { super(message); }
    public UpstreamException(String message, Throwable cause) { super(message, cause); }
}
```

Создать `src/main/java/com/vladoose/nir/integration/ndda/NddaClient.java`:

```java
package com.vladoose.nir.integration.ndda;

import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;

/** Реестр МИ НЦЭЛС РК (oldregister.ndda.kz) — публичный API без auth. */
public interface NddaClient {

    /** Внутренний id портала по точному № РУ (list-фильтр); null — на портале не найден. */
    Long resolveId(String regNumber);

    /** Карточка РУ по внутреннему id (описание: назначение/область/класс риска/характеристики/вид МИ). */
    NddaDetailDto fetchDetail(long id);
}
```

Создать `src/main/java/com/vladoose/nir/integration/ndda/NddaHttpClient.java`:

```java
package com.vladoose.nir.integration.ndda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.integration.ndda.dto.NddaListItemDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP-клиент реестра НЦЭЛС. В отличие от goszakup-клиента сразу кидает UpstreamException
 * (ошибки сети/не-200/кривой JSON) — вызывающему сервису оборачивать нечего.
 */
@Component
public class NddaHttpClient implements NddaClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public NddaHttpClient(ObjectMapper objectMapper,
                          @Value("${ndda.api.base-url:https://oldregister.ndda.kz/register-backend}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public Long resolveId(String regNumber) {
        // list с фильтром по точному № РУ → массив из 0/1 элемента (живая форма 2026-07-05)
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("regTypeId", 2);   // тип «МИ»
        filter.put("regPeriod", 1);   // действующие
        filter.put("regNumber", regNumber);
        String body;
        try {
            body = objectMapper.writeValueAsString(filter);
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС: не собрался запрос list: " + e.getMessage(), e);
        }
        String json = send(HttpRequest.newBuilder(URI.create(baseUrl + "/RegisterService/list"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30)).build());
        try {
            List<NddaListItemDto> items = objectMapper.readValue(json, new TypeReference<List<NddaListItemDto>>() {});
            return items.isEmpty() ? null : items.get(0).getId();
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС: неожиданный ответ list: " + e.getMessage(), e);
        }
    }

    @Override
    public NddaDetailDto fetchDetail(long id) {
        String json = send(HttpRequest.newBuilder(URI.create(baseUrl + "/RegisterService/MtMainGetById?Id=" + id))
                .GET().timeout(Duration.ofSeconds(30)).build());
        try {
            return objectMapper.readValue(json, NddaDetailDto.class);
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС: неожиданный ответ карточки: " + e.getMessage(), e);
        }
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new UpstreamException("НЦЭЛС ответил " + resp.statusCode());
            }
            return resp.body();
        } catch (UpstreamException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("НЦЭЛС: запрос прерван", e);
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС недоступен: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Конфиг**

В `src/main/resources/application.yaml` после секции `goszakup` (после строки `since-days: ${GOSZAKUP_SINCE_DAYS:30}`) добавить:

```yaml
ndda:
  api:
    base-url: ${NDDA_BASE_URL:https://oldregister.ndda.kz/register-backend}
```

- [ ] **Step 6: Тест зелёный**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests "com.vladoose.nir.integration.ndda.NddaHttpClientTest" 2>&1 | tail -10
```
(с `dangerouslyDisableSandbox: true`)

Expected: `BUILD SUCCESSFUL`, 4 теста зелёные.

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/ndda src/test/java/com/vladoose/nir/integration/ndda src/main/resources/application.yaml src/main/java/com/vladoose/nir/exception/UpstreamException.java && git commit -m "feat(ndda): HTTP-клиент карточки РУ НЦЭЛС (resolveId по № РУ + MtMainGetById)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `RegistryDetailService` + writer + endpoint `GET /api/registry/detail`

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/RegistryDetailWriter.java`
- Create: `src/main/java/com/vladoose/nir/service/RegistryDetailService.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/RegistryDetailResponse.java`
- Modify: `src/main/java/com/vladoose/nir/controller/RegistryController.java` (инжект + новый GET-метод)
- Test: `src/test/java/com/vladoose/nir/service/RegistryDetailServiceTest.java`

**Interfaces:**
- Consumes: Task 1 (поля кеша `MedRegistry`), Task 2 (`NddaClient.resolveId/fetchDetail`, `NddaDetailDto`), существующие `MedRegistryRepository.findByRegNumber`, `NotFoundException`, `UpstreamException`.
- Produces: `RegistryDetailService.detail(String regNumber) : RegistryDetailResponse`; REST `GET /api/registry/detail?regNumber=…` → JSON `{regNumber, riskClass, purpose, useArea, techChars, miKind, miKindDef, fetchedAt}` — на него опирается Task 5 (фронт).

- [ ] **Step 1: Написать failing-тест**

Создать `src/test/java/com/vladoose/nir/service/RegistryDetailServiceTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.RegistryDetailResponse;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class RegistryDetailServiceTest {

    @Autowired RegistryDetailService service;
    @Autowired MedRegistryRepository registryRepository;
    @MockitoBean NddaClient nddaClient;

    MedRegistry reg;

    @BeforeEach
    void setUp() {
        reg = registryRepository.saveAndFlush(MedRegistry.builder()
                .regNumber("ZZ-РУ-DETAIL-1")
                .name("ZZ Тестовое изделие для детали")
                .build());
    }

    private NddaDetailDto dto() {
        NddaDetailDto d = new NddaDetailDto();
        d.setPurpose("Поддерживает глубину передней камеры");
        d.setUseArea("Офтальмологическая хирургия");
        d.setDegreeRiskName("Класс 2 б");
        d.setShortTechnicalCharacteristicsRu("Вязкость 40 000 мПа·с");
        d.setTermNameRus("Материал для замещения");
        d.setTermDefinition("Неимплантируемое вещество");
        return d;
    }

    @Test
    void firstCall_fetchesFromNdda_andCaches() {
        when(nddaClient.resolveId("ZZ-РУ-DETAIL-1")).thenReturn(182621L);
        when(nddaClient.fetchDetail(182621L)).thenReturn(dto());

        RegistryDetailResponse r = service.detail("ZZ-РУ-DETAIL-1");

        assertThat(r.getRiskClass()).isEqualTo("Класс 2 б");
        assertThat(r.getPurpose()).contains("глубину передней камеры");
        assertThat(r.getTechChars()).contains("40 000");
        assertThat(r.getMiKind()).isEqualTo("Материал для замещения");
        assertThat(r.getFetchedAt()).isNotNull();

        MedRegistry cached = registryRepository.findByRegNumber("ZZ-РУ-DETAIL-1").orElseThrow();
        assertThat(cached.getTechChars()).contains("40 000");
        assertThat(cached.getNddaId()).isEqualTo(182621L);
        assertThat(cached.getDetailFetchedAt()).isNotNull();
    }

    @Test
    void secondCall_servedFromCache_withoutNetwork() {
        when(nddaClient.resolveId(anyString())).thenReturn(182621L);
        when(nddaClient.fetchDetail(anyLong())).thenReturn(dto());

        service.detail("ZZ-РУ-DETAIL-1");
        RegistryDetailResponse r2 = service.detail("ZZ-РУ-DETAIL-1");

        assertThat(r2.getTechChars()).contains("40 000");
        verify(nddaClient, times(1)).resolveId(anyString());
        verify(nddaClient, times(1)).fetchDetail(anyLong());
    }

    @Test
    void notFoundOnPortal_cachesEmptyMarker_andDoesNotRetry() {
        when(nddaClient.resolveId("ZZ-РУ-DETAIL-1")).thenReturn(null);

        RegistryDetailResponse r = service.detail("ZZ-РУ-DETAIL-1");
        assertThat(r.getPurpose()).isNull();
        assertThat(r.getTechChars()).isNull();
        assertThat(r.getFetchedAt()).isNotNull();

        service.detail("ZZ-РУ-DETAIL-1"); // второй вызов — из кеша
        verify(nddaClient, times(1)).resolveId(anyString());
        verify(nddaClient, never()).fetchDetail(anyLong());
    }

    @Test
    void unknownRegNumber_throws404() {
        assertThatThrownBy(() -> service.detail("НЕТ-ТАКОГО-РУ"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void upstreamError_propagates_andCacheStaysEmpty() {
        when(nddaClient.resolveId("ZZ-РУ-DETAIL-1")).thenThrow(new UpstreamException("НЦЭЛС недоступен"));

        assertThatThrownBy(() -> service.detail("ZZ-РУ-DETAIL-1"))
                .isInstanceOf(UpstreamException.class);

        MedRegistry after = registryRepository.findByRegNumber("ZZ-РУ-DETAIL-1").orElseThrow();
        assertThat(after.getDetailFetchedAt()).isNull(); // следующий клик = retry
    }
}
```

- [ ] **Step 2: Убедиться, что тест красный**

```bash
cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileTestJava 2>&1 | tail -5
```
(с `dangerouslyDisableSandbox: true`)

Expected: `BUILD FAILED` — `RegistryDetailService`/`RegistryDetailResponse` не существуют.

- [ ] **Step 3: Реализация — DTO ответа**

Создать `src/main/java/com/vladoose/nir/dto/response/RegistryDetailResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/** Описание изделия из карточки НЦЭЛС (кеш в med_registry); null-поля — «в карточке не заполнено». */
@Data
@Builder
public class RegistryDetailResponse {
    private String regNumber;
    private String riskClass;
    private String purpose;
    private String useArea;
    private String techChars;
    private String miKind;
    private String miKindDef;
    private OffsetDateTime fetchedAt;
}
```

- [ ] **Step 4: Реализация — writer (транзакция только на запись)**

Создать `src/main/java/com/vladoose/nir/service/RegistryDetailWriter.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/** Запись кеша карточки НЦЭЛС отдельным транзакционным бином (сеть — вне транзакции, §6 CLAUDE.md). */
@Service
public class RegistryDetailWriter {

    private final MedRegistryRepository registryRepository;

    public RegistryDetailWriter(MedRegistryRepository registryRepository) {
        this.registryRepository = registryRepository;
    }

    /** detail == null — РУ на портале не найден: ставим только маркер, чтобы не долбить портал повторно. */
    @Transactional
    public MedRegistry save(String regNumber, Long nddaId, NddaDetailDto detail) {
        MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ не найдено в реестре: " + regNumber));
        reg.setNddaId(nddaId);
        if (detail != null) {
            reg.setRiskClass(detail.getDegreeRiskName());
            reg.setPurpose(detail.getPurpose());
            reg.setUseArea(detail.getUseArea());
            reg.setTechChars(detail.getShortTechnicalCharacteristicsRu());
            reg.setMiKind(detail.getTermNameRus());
            reg.setMiKindDef(detail.getTermDefinition());
        }
        reg.setDetailFetchedAt(OffsetDateTime.now());
        return registryRepository.save(reg);
    }
}
```

- [ ] **Step 5: Реализация — сервис**

Создать `src/main/java/com/vladoose/nir/service/RegistryDetailService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.RegistryDetailResponse;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.springframework.stereotype.Service;

/**
 * Описание изделия из карточки НЦЭЛС: on-demand fetch при первом просмотре + кеш в med_registry.
 * Сеть — вне транзакции; запись кеша — RegistryDetailWriter (паттерн TechSpecService/TechSpecWriter).
 */
@Service
public class RegistryDetailService {

    private final MedRegistryRepository registryRepository;
    private final NddaClient nddaClient;
    private final RegistryDetailWriter writer;

    public RegistryDetailService(MedRegistryRepository registryRepository,
                                 NddaClient nddaClient,
                                 RegistryDetailWriter writer) {
        this.registryRepository = registryRepository;
        this.nddaClient = nddaClient;
        this.writer = writer;
    }

    public RegistryDetailResponse detail(String regNumber) {
        MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ не найдено в реестре: " + regNumber));
        if (reg.getDetailFetchedAt() == null) {
            Long nddaId = nddaClient.resolveId(regNumber);                      // сеть вне транзакции
            NddaDetailDto detail = nddaId != null ? nddaClient.fetchDetail(nddaId) : null;
            reg = writer.save(regNumber, nddaId, detail);
        }
        return toResponse(reg);
    }

    private static RegistryDetailResponse toResponse(MedRegistry r) {
        return RegistryDetailResponse.builder()
                .regNumber(r.getRegNumber())
                .riskClass(r.getRiskClass())
                .purpose(r.getPurpose())
                .useArea(r.getUseArea())
                .techChars(r.getTechChars())
                .miKind(r.getMiKind())
                .miKindDef(r.getMiKindDef())
                .fetchedAt(r.getDetailFetchedAt())
                .build();
    }
}
```

- [ ] **Step 6: Реализация — endpoint**

В `src/main/java/com/vladoose/nir/controller/RegistryController.java`:

1. Импорты добавить:
```java
import com.vladoose.nir.dto.response.RegistryDetailResponse;
import com.vladoose.nir.service.RegistryDetailService;
```
2. Поле + параметр конструктора:
```java
    private final RegistryDetailService detailService;

    public RegistryController(RegistryMatchService matchService, RegistryImportService importService,
                              RegistryDetailService detailService) {
        this.matchService = matchService;
        this.importService = importService;
        this.detailService = detailService;
    }
```
(заменить существующий конструктор)

3. Метод после `search(...)`:
```java
    /** Описание изделия из карточки НЦЭЛС (live при первом просмотре + кеш). Чтение — без ADMIN. */
    @GetMapping("/detail")
    public RegistryDetailResponse detail(@RequestParam String regNumber) {
        return detailService.detail(regNumber);
    }
```

- [ ] **Step 7: Тест зелёный**

```bash
cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9; ./gradlew test --tests "com.vladoose.nir.service.RegistryDetailServiceTest" 2>&1 | tail -10
```
(с `dangerouslyDisableSandbox: true`)

Expected: `BUILD SUCCESSFUL`, 5 тестов зелёные.

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/RegistryDetailService.java src/main/java/com/vladoose/nir/service/RegistryDetailWriter.java src/main/java/com/vladoose/nir/dto/response/RegistryDetailResponse.java src/main/java/com/vladoose/nir/controller/RegistryController.java src/test/java/com/vladoose/nir/service/RegistryDetailServiceTest.java && git commit -m "feat(registry): GET /api/registry/detail — описание РУ из НЦЭЛС с кешем в med_registry

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: adopt обогащает spec новой позиции каталога

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/RegistryMatchService.java` (метод `adoptForLot`, ~строка 129 — лямбда `orElseGet`)
- Test: `src/test/java/com/vladoose/nir/service/RegistryAdoptTest.java` (добавить тест)

**Interfaces:**
- Consumes: Task 1 (`MedRegistry.getTechChars()`); существующие `adoptForLot`, `MedEquipment.setSpec(String)` (колонка `spec` — TEXT).
- Produces: поведение — новая `MedEquipment` при adopt получает `spec` из кеша характеристик (если кеш есть). Сигнатуры не меняются.

- [ ] **Step 1: Написать failing-тест**

В `src/test/java/com/vladoose/nir/service/RegistryAdoptTest.java` добавить два теста (после существующих, внутри класса):

```java
    @Test
    void adoptCopiesCachedTechCharsIntoNewEquipmentSpec() {
        reg.setTechChars("Вязкость 40 000 мПа·с; осмоляльность 325 мОсм/кг");
        medRegistryRepository.saveAndFlush(reg);

        service.adoptForLot(lot.getId(), reg.getRegNumber());

        MedEquipment eq = medEquipmentRepository.findFirstByRegistrationRegNumber(reg.getRegNumber()).orElseThrow();
        assertThat(eq.getSpec()).contains("40 000 мПа·с");
    }

    @Test
    void adoptWithoutCachedDetail_leavesSpecNull() {
        service.adoptForLot(lot.getId(), reg.getRegNumber());

        MedEquipment eq = medEquipmentRepository.findFirstByRegistrationRegNumber(reg.getRegNumber()).orElseThrow();
        assertThat(eq.getSpec()).isNull();
    }
```

- [ ] **Step 2: Убедиться, что первый тест красный**

```bash
cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9; ./gradlew test --tests "com.vladoose.nir.service.RegistryAdoptTest" 2>&1 | tail -10
```
(с `dangerouslyDisableSandbox: true`)

Expected: `adoptCopiesCachedTechCharsIntoNewEquipmentSpec` FAILED (`eq.getSpec()` null), `adoptWithoutCachedDetail_leavesSpecNull` и старые тесты — зелёные.

- [ ] **Step 3: Реализация**

В `src/main/java/com/vladoose/nir/service/RegistryMatchService.java`, в `adoptForLot`, внутри `orElseGet(() -> { ... })` после строки `e.setManufact(...)` добавить:

```java
                    if (reg.getTechChars() != null && !reg.getTechChars().isBlank()) {
                        e.setSpec(reg.getTechChars()); // из кеша карточки НЦЭЛС; внешку при adopt не зовём
                    }
```

- [ ] **Step 4: Тест зелёный + гард от дублей после Edit**

```bash
cd /Users/vlad/IdeaProjects/AIS && grep -c "adoptForLot" src/main/java/com/vladoose/nir/service/RegistryMatchService.java && ./gradlew test --tests "com.vladoose.nir.service.RegistryAdoptTest" 2>&1 | tail -8
```
(с `dangerouslyDisableSandbox: true`)

Expected: `grep -c` = 1 (метод не задублирован фоновым автоформатом), `BUILD SUCCESSFUL`, все тесты класса зелёные.

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/RegistryMatchService.java src/test/java/com/vladoose/nir/service/RegistryAdoptTest.java && git commit -m "feat(registry): adopt переносит кеш характеристик НЦЭЛС в spec новой позиции каталога

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Фронт — разворот описания в панели «Реестр»

**Files:**
- Modify: `frontend/src/app/services/api.service.ts` (метод после `getLotRegistryCandidates`, ~строка 91)
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`:
  - шаблон панели «Реестр» (~строки 315–331: tbody таблицы кандидатов),
  - стили (рядом с `.registry-*`, ~строки 534–544),
  - код компонента (`registryPanel` ~строка 970, методы рядом с `closeRegistryPanel` ~строка 993).

**Interfaces:**
- Consumes: Task 3 — `GET /api/registry/detail?regNumber=…` → `{regNumber, riskClass, purpose, useArea, techChars, miKind, miKindDef, fetchedAt}`.
- Produces: UI-разворот; новых контрактов нет.

- [ ] **Step 1: ApiService**

В `frontend/src/app/services/api.service.ts` после метода `getLotRegistryCandidates` добавить:

```typescript
  getRegistryDetail(regNumber: string): Observable<any> {
    return this.http.get<any>(`${this.base}/registry/detail`, { params: { regNumber } });
  }
```

- [ ] **Step 2: Состояние и методы компонента**

В `frontend/src/app/pages/tenders/tenders.component.ts` заменить строку объявления `registryPanel` (~970):

```typescript
  registryPanel: { lot: any; loading: boolean; items: any[]; distinctive?: boolean; techSpecParsed?: boolean;
                   openReg?: string | null; detail?: any; detailLoading?: boolean; detailError?: string | null } | null = null;
```

После метода `closeRegistryPanel()` (~993) добавить:

```typescript
  toggleRegistryDetail(c: any) {
    const p = this.registryPanel;
    if (!p) return;
    if (p.openReg === c.regNumber) { p.openReg = null; this.cdr.detectChanges(); return; }
    p.openReg = c.regNumber;
    p.detail = c._detail || null;
    p.detailError = null;
    p.detailLoading = !p.detail;
    if (!p.detail) {
      this.api.getRegistryDetail(c.regNumber).subscribe({
        next: d => {
          c._detail = d; // фронтовый кеш на объекте кандидата — повторный разворот без запроса
          if (p.openReg === c.regNumber) { p.detail = d; p.detailLoading = false; }
          this.cdr.detectChanges();
        },
        error: err => {
          // ошибка живёт в развороте (панель не закрываем, тост не нужен);
          // detail остаётся null и в c._detail не кешируется → повторное открытие = retry
          if (p.openReg === c.regNumber) {
            p.detailLoading = false;
            p.detailError = err.error?.message || 'Не удалось получить карточку НЦЭЛС';
          }
          this.cdr.detectChanges();
        }
      });
    }
    this.cdr.detectChanges();
  }

  registryDetailEmpty(d: any): boolean {
    return !!d && !d.riskClass && !d.purpose && !d.useArea && !d.techChars && !d.miKind;
  }
```

- [ ] **Step 3: Шаблон — tbody с разворотом**

В шаблоне панели «Реестр» заменить текущий tbody (строки вида `<tr *ngFor="let c of registryPanel.items">…</tr>`) на:

```html
          <tbody>
            <ng-container *ngFor="let c of registryPanel.items">
              <tr class="registry-row" (click)="toggleRegistryDetail(c)"
                  [title]="registryPanel.openReg === c.regNumber ? 'Свернуть описание' : 'Показать описание из карточки НЦЭЛС'">
                <td>
                  <span *ngIf="registryPanel.distinctive" class="score-badge" [class.score-good]="c.score >= 0.35">{{ scorePct(c) }}%</span>
                  <span *ngIf="!registryPanel.distinctive" class="score-badge score-name" title="Совпало наименование; для различения моделей разберите ТЗ">✓ по названию</span>
                </td>
                <td>{{ c.regNumber }}</td>
                <td>{{ c.name }} <span class="registry-desc-chip">{{ registryPanel.openReg === c.regNumber ? '▴ свернуть' : '▾ описание' }}</span></td>
                <td>{{ c.producer || '—' }}</td>
                <td>{{ c.country || '—' }}</td>
                <td>{{ c.unlimited ? 'бессрочно' : (c.expirationDate ? formatDate(c.expirationDate) : '—') }}</td>
                <td><button class="btn btn-adopt" [disabled]="adoptBusy" (click)="$event.stopPropagation(); adoptFromRegistry(c)" title="Создать модель каталога из этого РУ и предложить лоту">Взять в работу</button></td>
              </tr>
              <tr *ngIf="registryPanel.openReg === c.regNumber" class="registry-detail-row">
                <td colspan="7">
                  <div *ngIf="registryPanel.detailLoading" class="registry-loading">Загружаем карточку НЦЭЛС…</div>
                  <div *ngIf="registryPanel.detailError && !registryPanel.detailLoading" class="registry-detail-error">
                    {{ registryPanel.detailError }} — сверните и разверните строку, чтобы повторить.
                  </div>
                  <div *ngIf="registryPanel.detail && !registryPanel.detailLoading" class="registry-detail-cols">
                    <div *ngIf="registryPanel.lot?.requiredSpec" class="registry-detail-col">
                      <div class="registry-detail-h">ТЗ лота</div>
                      <pre class="registry-detail-pre">{{ registryPanel.lot.requiredSpec }}</pre>
                    </div>
                    <div class="registry-detail-col">
                      <div class="registry-detail-h">Из реестра НЦЭЛС</div>
                      <div *ngIf="registryDetailEmpty(registryPanel.detail)" class="empty">В карточке НЦЭЛС описание не заполнено</div>
                      <div *ngIf="registryPanel.detail.riskClass || registryPanel.detail.miKind" class="registry-detail-meta">
                        <span *ngIf="registryPanel.detail.riskClass">{{ registryPanel.detail.riskClass }}</span>
                        <span *ngIf="registryPanel.detail.riskClass && registryPanel.detail.miKind"> · </span>
                        <span *ngIf="registryPanel.detail.miKind">{{ registryPanel.detail.miKind }}</span>
                        <div *ngIf="registryPanel.detail.miKindDef" class="registry-detail-def">{{ registryPanel.detail.miKindDef }}</div>
                      </div>
                      <div *ngIf="registryPanel.detail.purpose" class="registry-detail-block"><b>Назначение:</b> {{ registryPanel.detail.purpose }}</div>
                      <div *ngIf="registryPanel.detail.useArea" class="registry-detail-block"><b>Область применения:</b> {{ registryPanel.detail.useArea }}</div>
                      <div *ngIf="registryPanel.detail.techChars" class="registry-detail-block"><b>Краткие тех. характеристики:</b>
                        <pre class="registry-detail-pre">{{ registryPanel.detail.techChars }}</pre>
                      </div>
                    </div>
                  </div>
                </td>
              </tr>
            </ng-container>
          </tbody>
```

(таблица остаётся на 7 колонках — чип «▾ описание» живёт в ячейке наименования; thead не меняется)

- [ ] **Step 4: Стили**

В `styles` компонента рядом с существующими `.registry-*` (после `.registry-hint`) добавить:

```scss
    .registry-row { cursor: pointer; }
    .registry-row:hover td { background: #f5f3ff; }
    .registry-desc-chip { font-size: 11px; color: #7c3aed; white-space: nowrap; margin-left: 6px; }
    .registry-detail-row td { background: #f5f3ff; padding: 10px 14px; }
    .registry-detail-cols { display: flex; gap: 16px; align-items: flex-start; }
    .registry-detail-col { flex: 1; min-width: 0; }
    .registry-detail-h { font-size: 11px; font-weight: 600; color: #6b7280; margin-bottom: 6px; text-transform: uppercase; letter-spacing: .04em; }
    .registry-detail-pre { white-space: pre-wrap; max-height: 300px; overflow-y: auto; background: #fff; border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px 10px; font: inherit; margin: 4px 0 0; }
    .registry-detail-meta { margin-bottom: 8px; font-weight: 600; }
    .registry-detail-def { font-size: 12px; color: #6b7280; font-weight: 400; margin-top: 2px; }
    .registry-detail-block { margin-bottom: 6px; }
    .registry-detail-error { color: #b91c1c; padding: 4px 0; }
```

- [ ] **Step 5: Сборка фронта (гейт)**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -8
```

Expected: сборка успешна, без превышения бюджета `anyComponentStyle` (16 кБ).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts && git commit -m "feat(ui): панель «Реестр» — разворот с описанием НЦЭЛС рядом с ТЗ лота

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Полный гейт, живая проверка, CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (§8 — строка про фичу; §16 — вычеркнуть/дополнить, если применимо)

**Interfaces:**
- Consumes: всё выше.
- Produces: подтверждённая работающая фича + обновлённая документация.

- [ ] **Step 1: Полный тестовый прогон**

```bash
cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9; ./gradlew test 2>&1 | tail -25
```
(с `dangerouslyDisableSandbox: true`)

Expected: падают ТОЛЬКО 2 пред-существующих `ApplyAutoFillServiceTest`.

- [ ] **Step 2: Запуск для живой проверки**

```bash
cd /Users/vlad/IdeaProjects/AIS && GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" ./gradlew bootRun
```
(фоном, с `dangerouslyDisableSandbox: true`; фронт уже крутится на :4200 — если нет: `cd frontend && npm start`)

- [ ] **Step 3: Живой клик-тест (Playwright MCP)**

1. `http://localhost:4200` → логин `admin`/`admin`.
2. `localStorage.setItem('ais.market','KZ')` → на `/tenders` открыть импортный тендер (карточка с лотами).
3. У лота нажать «Реестр» → в панели кликнуть строку кандидата.
4. Проверить: лоадер «Загружаем карточку НЦЭЛС…» → разворот с колонками («ТЗ лота» — если у лота есть разобранное ТЗ; «Из реестра НЦЭЛС» — класс риска/вид МИ/назначение/область/характеристики или «В карточке НЦЭЛС описание не заполнено»).
5. Свернуть/развернуть повторно — мгновенно (без сети). Screenshot.
6. Проверить в БД кеш: `PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "SELECT reg_number, left(tech_chars,60), detail_fetched_at FROM med_registry WHERE detail_fetched_at IS NOT NULL LIMIT 5;"`
7. «Взять в работу» по кандидату с загруженным описанием → карточка позиции в `/equipment` содержит спеку.

- [ ] **Step 4: CLAUDE.md**

В §8 (блок «Умный реестр-матч по лоту», после пункта про «Взять из реестра в работу») добавить строку:

```markdown
  - **Описание кандидата (карточка НЦЭЛС):** клик по строке кандидата в панели «Реестр» → разворот «ТЗ лота ↔ Из реестра НЦЭЛС» (назначение/область/класс риска/краткие тех. характеристики/вид МИ). `GET /api/registry/detail?regNumber=…` → `RegistryDetailService`: live-fetch с oldregister.ndda.kz при первом просмотре (`integration/ndda`: list-фильтр по № РУ → id → `MtMainGetById`, без auth, `ndda.api.base-url`), кеш в `med_registry` (V5: `tech_chars`/`purpose`/`use_area`/`risk_class`/`mi_kind`/`mi_kind_def`/`ndda_id`/`detail_fetched_at`; маркер ставится и при «не найдено» — не долбим портал). Сеть вне tx (`RegistryDetailWriter`). Adopt переносит `tech_chars` в `spec` новой позиции каталога.
```

В §15 добавить `/api/registry/detail` в перечень `/api/registry-*`.

- [ ] **Step 5: Финальный commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md && git commit -m "docs: CLAUDE.md — описание кандидата реестра (карточка НЦЭЛС, кеш V5)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

(мерж ветки в main — после whole-branch review по процессу SDD/finishing-a-development-branch)
