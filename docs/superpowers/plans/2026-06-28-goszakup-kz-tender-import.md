# Автоимпорт KZ-тендеров (goszakup.gov.kz) + фильтр по региону — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** На странице «Тендеры» при рынке KZ список наполняется релевантными (медоборудование) госзакупками с goszakup.gov.kz через официальное REST API; добавляется фильтр по региону.

**Architecture:** Новый слой импорта по паттернам проекта — `GoszakupHttpClient` (java.net.http) тянет `trd-buy`/`lots`/`subject`, `GoszakupImportService` (@Transactional, отдельный бин) фильтрует по ключевым словам, резолвит регион и идемпотентно upsert'ит в `Tender`+`TenderLot` (`market=KZ`, `source=PUBLIC_TENDER`, `facility=null`). Запуск — `GoszakupImportScheduler` (@Scheduled, дисциплина MarketContext §6) и ручной `POST /api/tenders/import-kz`. Фронт — кнопка «Обновить» + клиентский фильтр по региону.

**Tech Stack:** Java 17 (`java.net.http.HttpClient`), Spring Boot 3.5.6, Spring Data JPA/Hibernate 6, Jackson, Flyway, Angular 21. Новых Gradle-зависимостей НЕТ.

## Global Constraints

- **БД-команды и `./gradlew` — только с `dangerouslyDisableSandbox: true`** (sandbox блокирует localhost:5432).
- **Тесты:** `@SpringBootTest @Transactional` на реальном Postgres `nirdb`; внешняя сеть в тестах ЗАПРЕЩЕНА — `GoszakupClient` подменяется фейком. Гейт «зелёного»: единственные допустимые падения — пред-существующие `ApplyAutoFillServiceTest` (2 шт).
- **Многорыночность:** новые рыночные записи стампятся `market=KZ` ЯВНО в импорт-сервисе; `@FilterDef` НЕ добавлять (он только на `Tender`). Фоновый поток ставит `MarketContext.set(KZ)` до вызова `@Transactional`-метода и `clear()` в finally (§6).
- **JPA orphanRemoval:** лотами управлять ТОЛЬКО через коллекцию `tender.getLots()` (clear/add), НЕ через `repository.delete` (§7/§14).
- **Flyway:** менять схему только новой миграцией (следующая — `V3`), V1/V2 не трогать.
- **Секреты:** токен только из env (`GOSZAKUP_TOKEN`), не коммитить, не эхо-печатать.
- **Коммиты:** заканчивать `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Ветка: `feat/goszakup-kz-tender-import` (уже создана, спека уже закоммичена).
- **Резолв региона должен возвращать строку, идентичную одной из `REGIONS`** (фронт-константа, Task 8) — иначе клиентский фильтр не совпадёт.

---

### Task 1: Миграция V3 + поля `Tender` + `TenderResponse` + finder

**Files:**
- Create: `src/main/resources/db/migration/V3__goszakup_tender_fields.sql`
- Modify: `src/main/java/com/vladoose/nir/entity/Tender.java` (после поля `market`, ~стр. 84)
- Modify: `src/main/java/com/vladoose/nir/dto/response/TenderResponse.java`
- Modify: `src/main/java/com/vladoose/nir/repository/TenderRepository.java`
- Test: `src/test/java/com/vladoose/nir/repository/TenderRepositoryGoszakupTest.java`

**Interfaces:**
- Produces: `Tender` getters/setters `getSourceExtId/setSourceExtId`, `getRegion/setRegion`, `getRegionKato/setRegionKato`, `getCustomerName/setCustomerName`, `getCustomerBin/setCustomerBin` (String). `TenderRepository.findBySourceExtId(String): Optional<Tender>`. `TenderResponse` поля `region`, `regionKato`, `customerName`, `customerBin` (String).

- [ ] **Step 1: Failing test** — `TenderRepositoryGoszakupTest.java`

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenderRepositoryGoszakupTest {

    @Autowired
    TenderRepository repository;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void findBySourceExtId_scopedByMarket() {
        MarketContext.set(Market.KZ);
        Tender t = Tender.builder()
                .tenderNumber("415500-1").sourceExtId("415500-1")
                .status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.KZ).currency("KZT")
                .region("г. Алматы").regionKato("750000000")
                .customerName("ГКП Поликлиника №5").customerBin("123456789012")
                .build();
        repository.save(t);

        Optional<Tender> kz = repository.findBySourceExtId("415500-1");
        assertThat(kz).isPresent();
        assertThat(kz.get().getRegion()).isEqualTo("г. Алматы");
        assertThat(kz.get().getCustomerName()).isEqualTo("ГКП Поликлиника №5");

        MarketContext.set(Market.RF);
        assertThat(repository.findBySourceExtId("415500-1")).isEmpty(); // изоляция рынка
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew test --tests "com.vladoose.nir.repository.TenderRepositoryGoszakupTest" -q` (с `dangerouslyDisableSandbox: true`)
Expected: компиляция падает — `sourceExtId`/`region`/... и `findBySourceExtId` не существуют.

- [ ] **Step 3: Миграция V3**

`src/main/resources/db/migration/V3__goszakup_tender_fields.sql`:
```sql
-- Поля для импортных KZ-тендеров с goszakup.gov.kz
ALTER TABLE tender ADD COLUMN IF NOT EXISTS source_ext_id VARCHAR(64);
ALTER TABLE tender ADD COLUMN IF NOT EXISTS region        VARCHAR(100);
ALTER TABLE tender ADD COLUMN IF NOT EXISTS region_kato   VARCHAR(20);
ALTER TABLE tender ADD COLUMN IF NOT EXISTS customer_name VARCHAR(500);
ALTER TABLE tender ADD COLUMN IF NOT EXISTS customer_bin  VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_tender_region ON tender(market, region);
-- ключ идемпотентности импорта (только для импортных строк)
CREATE UNIQUE INDEX IF NOT EXISTS uq_tender_market_extid
    ON tender(market, source_ext_id) WHERE source_ext_id IS NOT NULL;
```

- [ ] **Step 4: Поля в `Tender.java`** — добавить ПОСЛЕ поля `market` (перед `lots`):

```java
    @Column(name = "source_ext_id", length = 64)
    private String sourceExtId;

    @Column(length = 100)
    private String region;

    @Column(name = "region_kato", length = 20)
    private String regionKato;

    @Column(name = "customer_name", length = 500)
    private String customerName;

    @Column(name = "customer_bin", length = 20)
    private String customerBin;
```

- [ ] **Step 5: Поля в `TenderResponse.java`** — добавить после `deliveryAddress` (MapStruct замапит автоматически — имена совпадают):

```java
    private String region;
    private String regionKato;
    private String customerName;
    private String customerBin;
```

- [ ] **Step 6: Finder в `TenderRepository.java`** — добавить импорт `java.util.Optional` и метод:

```java
    java.util.Optional<Tender> findBySourceExtId(String sourceExtId);
```

- [ ] **Step 7: Run, verify PASS**

Run: `./gradlew test --tests "com.vladoose.nir.repository.TenderRepositoryGoszakupTest" -q` (sandbox off)
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V3__goszakup_tender_fields.sql \
        src/main/java/com/vladoose/nir/entity/Tender.java \
        src/main/java/com/vladoose/nir/dto/response/TenderResponse.java \
        src/main/java/com/vladoose/nir/repository/TenderRepository.java \
        src/test/java/com/vladoose/nir/repository/TenderRepositoryGoszakupTest.java
git commit -m "feat(tender): поля импорта goszakup (V3) + finder по source_ext_id

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Client-DTO + `GoszakupClient` + `GoszakupHttpClient` + конфиг

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/dto/TrdBuyDto.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/dto/TrdBuyPageDto.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/dto/LotDto.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/dto/SubjectDto.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupClient.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupDtoJsonTest.java`

**Interfaces:**
- Produces:
  - `TrdBuyDto` (Lombok `@Data`): `Long id; String numberAnno; String nameRu; java.math.BigDecimal totalSum; Integer countLots; Integer refBuyStatusId; String customerBin; String orgBin; String publishDate; String startDate; String endDate;`
  - `TrdBuyPageDto`: `java.util.List<TrdBuyDto> items; String nextPage; Integer total;`
  - `LotDto`: `String lotNumber; String nameRu; java.math.BigDecimal amount; Integer count; String trdBuyNumberAnno;`
  - `SubjectDto`: `String bin; String nameRu; String katoId; String address;`
  - `GoszakupClient` (interface): `TrdBuyPageDto fetchTrdBuyPage(String cursor)`, `java.util.List<LotDto> fetchLots(String numberAnno)`, `SubjectDto fetchSubject(String bin)`, `boolean isConfigured()`.
- Consumes: ничего из прошлых задач.

- [ ] **Step 1: Failing test** — `GoszakupDtoJsonTest.java` (проверяет @JsonProperty-маппинг snake_case → DTO, без сети)

```java
package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoszakupDtoJsonTest {

    final ObjectMapper om = new ObjectMapper();

    @Test
    void parsesTrdBuyPage() throws Exception {
        String json = """
            {"total":171946,"next_page":"/v2/trd-buy?page=next&search_after=414621",
             "items":[{"id":414621,"number_anno":"415500-1","name_ru":"Аппарат УЗИ",
                       "total_sum":12000000.50,"count_lots":2,"ref_buy_status_id":230,
                       "customer_bin":"123456789012","org_bin":"987654321098",
                       "publish_date":"2026-06-01T00:00:00","start_date":"2026-06-02T00:00:00",
                       "end_date":"2026-06-20T00:00:00"}]}
            """;
        TrdBuyPageDto page = om.readValue(json, TrdBuyPageDto.class);
        assertThat(page.getNextPage()).contains("search_after=414621");
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getNumberAnno()).isEqualTo("415500-1");
        assertThat(page.getItems().get(0).getNameRu()).isEqualTo("Аппарат УЗИ");
        assertThat(page.getItems().get(0).getRefBuyStatusId()).isEqualTo(230);
        assertThat(page.getItems().get(0).getTotalSum()).isEqualByComparingTo("12000000.50");
    }

    @Test
    void parsesLot() throws Exception {
        String json = """
            {"lot_number":"1","name_ru":"Аппарат УЗИ портативный","amount":6000000,
             "count":2,"trd_buy_number_anno":"415500-1"}
            """;
        LotDto lot = om.readValue(json, LotDto.class);
        assertThat(lot.getLotNumber()).isEqualTo("1");
        assertThat(lot.getNameRu()).isEqualTo("Аппарат УЗИ портативный");
        assertThat(lot.getCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupDtoJsonTest" -q` (sandbox off)
Expected: компиляция падает — DTO нет.

- [ ] **Step 3: DTO** — 4 файла:

`dto/TrdBuyDto.java`:
```java
package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrdBuyDto {
    private Long id;
    @JsonProperty("number_anno") private String numberAnno;
    @JsonProperty("name_ru") private String nameRu;
    @JsonProperty("total_sum") private BigDecimal totalSum;
    @JsonProperty("count_lots") private Integer countLots;
    @JsonProperty("ref_buy_status_id") private Integer refBuyStatusId;
    @JsonProperty("customer_bin") private String customerBin;
    @JsonProperty("org_bin") private String orgBin;
    @JsonProperty("publish_date") private String publishDate;
    @JsonProperty("start_date") private String startDate;
    @JsonProperty("end_date") private String endDate;
}
```

`dto/TrdBuyPageDto.java`:
```java
package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrdBuyPageDto {
    private List<TrdBuyDto> items;
    @JsonProperty("next_page") private String nextPage;
    private Integer total;
}
```

`dto/LotDto.java`:
```java
package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LotDto {
    @JsonProperty("lot_number") private String lotNumber;
    @JsonProperty("name_ru") private String nameRu;
    private BigDecimal amount;
    private Integer count;
    @JsonProperty("trd_buy_number_anno") private String trdBuyNumberAnno;
}
```

`dto/SubjectDto.java` (поля региона неточны до пробоя токеном — захватываем имя + best-guess адрес/КАТО; `@JsonIgnoreProperties` гасит остальное):
```java
package com.vladoose.nir.integration.goszakup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubjectDto {
    private String bin;
    @JsonProperty("name_ru") private String nameRu;
    // имена полей региона уточняются на Task 9 (пробой схемы токеном):
    @JsonProperty("ref_kato_id") private String katoId;
    @JsonProperty("full_delivery_address") private String address;
}
```

- [ ] **Step 4: Интерфейс** — `GoszakupClient.java`:
```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import java.util.List;

public interface GoszakupClient {
    /** cursor == null → первая страница; иначе значение next_page из прошлого ответа. */
    TrdBuyPageDto fetchTrdBuyPage(String cursor);
    List<LotDto> fetchLots(String numberAnno);
    /** null, если организация не найдена. */
    SubjectDto fetchSubject(String bin);
    /** true, если задан токен (иначе импорт выключен). */
    boolean isConfigured();
}
```

- [ ] **Step 5: HTTP-реализация** — `GoszakupHttpClient.java`:
```java
package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class GoszakupHttpClient implements GoszakupClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String token;
    private final int pageSize;

    public GoszakupHttpClient(ObjectMapper objectMapper,
                              @Value("${goszakup.api.base-url:https://ows.goszakup.gov.kz/v2}") String baseUrl,
                              @Value("${goszakup.api.token:}") String token,
                              @Value("${goszakup.api.page-size:50}") int pageSize) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.pageSize = pageSize;
    }

    @Override public boolean isConfigured() { return token != null && !token.isBlank(); }

    @Override
    public TrdBuyPageDto fetchTrdBuyPage(String cursor) {
        // cursor — это путь next_page ("/v2/trd-buy?page=next&search_after=...") либо null
        String url = (cursor != null && !cursor.isBlank())
                ? origin() + cursor
                : baseUrl + "/trd-buy?limit=" + pageSize;
        return get(url, TrdBuyPageDto.class);
    }

    @Override
    public List<LotDto> fetchLots(String numberAnno) {
        // лоты приходят в обёртке-странице {items:[...]}
        TypeRefPage<LotDto> page = get(baseUrl + "/lots/number-anno/" + enc(numberAnno),
                new TypeReference<TypeRefPage<LotDto>>() {});
        return page != null && page.items != null ? page.items : List.of();
    }

    @Override
    public SubjectDto fetchSubject(String bin) {
        if (bin == null || bin.isBlank()) return null;
        try { return get(baseUrl + "/subject/" + enc(bin), SubjectDto.class); }
        catch (RuntimeException e) { return null; }
    }

    // --- helpers ---
    /** Тонкая обёртка-страница для эндпоинтов, отдающих {items:[...]}. */
    static class TypeRefPage<T> { public List<T> items; }

    private String origin() {
        // baseUrl="https://ows.goszakup.gov.kz/v2" → origin="https://ows.goszakup.gov.kz"
        int i = baseUrl.indexOf("/", baseUrl.indexOf("://") + 3);
        return i > 0 ? baseUrl.substring(0, i) : baseUrl;
    }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

    private <T> T get(String url, Class<T> type) {
        return parse(rawGet(url), b -> objectMapper.readValue(b, type));
    }
    private <T> T get(String url, TypeReference<T> type) {
        return parse(rawGet(url), b -> objectMapper.readValue(b, type));
    }
    private byte[] rawGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("goszakup API " + resp.statusCode() + " на " + url);
            }
            return resp.body();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("goszakup API недоступно: " + e.getMessage(), e);
        }
    }
    private interface Parser<T> { T apply(byte[] b) throws java.io.IOException; }
    private <T> T parse(byte[] body, Parser<T> p) {
        try { return p.apply(body); }
        catch (java.io.IOException e) { throw new IllegalStateException("goszakup: разбор JSON: " + e.getMessage(), e); }
    }
}
```

- [ ] **Step 6: Конфиг** — в `application.yaml` добавить В КОНЕЦ блок (на одном уровне с `mail:`):
```yaml
goszakup:
  api:
    base-url: ${GOSZAKUP_BASE_URL:https://ows.goszakup.gov.kz/v2}
    token: ${GOSZAKUP_TOKEN:}
    page-size: ${GOSZAKUP_PAGE_SIZE:50}
  import:
    enabled: ${GOSZAKUP_IMPORT_ENABLED:false}
    poll-ms: ${GOSZAKUP_POLL_MS:21600000}   # 6 часов
    since-days: ${GOSZAKUP_SINCE_DAYS:30}
    max-pages: ${GOSZAKUP_MAX_PAGES:20}      # предохранитель от бесконечной пагинации
    # пустой statuses = брать все статусы (точные id активных статусов уточняются токеном, Task 9)
    statuses: ${GOSZAKUP_STATUSES:}
    keywords: ${GOSZAKUP_KEYWORDS:аппарат,узи,ультразвук,томограф,рентген,монитор пациента,дефибриллятор,ивл,наркозн,анализатор,центрифуг,стерилизатор,автоклав,эндоскоп,маммограф,флюорограф,электрокардиограф,кардиомонитор,инкубатор,облучатель,спирометр,хирургическ,реанимац,медицинск}
```

- [ ] **Step 7: Run, verify PASS**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupDtoJsonTest" -q` (sandbox off)
Expected: PASS (2 теста).

- [ ] **Step 8: Commit**
```bash
git add src/main/java/com/vladoose/nir/integration/ src/main/resources/application.yaml \
        src/test/java/com/vladoose/nir/integration/goszakup/GoszakupDtoJsonTest.java
git commit -m "feat(goszakup): HTTP-клиент API + DTO + конфиг

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `RegionResolver` (текст-словарь 20 регионов)

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/RegionResolver.java`
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/RegionResolverTest.java`

**Interfaces:**
- Produces: `RegionResolver` (Spring `@Component`, без зависимостей) — `String resolve(String... textCandidates)`: сканирует переданные строки и возвращает каноническое имя региона (из списка `REGIONS`, см. Task 8) или `null`. Регистронезависимо, наиболее специфичное совпадение (область раньше города-омонима тут не конфликтует — «Алматинская» не содержит «Алматы»).

- [ ] **Step 1: Failing test** — `RegionResolverTest.java`:
```java
package com.vladoose.nir.integration.goszakup;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RegionResolverTest {

    final RegionResolver r = new RegionResolver();

    @Test void detectsCityFromCustomerName() {
        assertThat(r.resolve("ГКП на ПХВ «Городская поликлиника №5 г. Алматы»")).isEqualTo("г. Алматы");
    }
    @Test void detectsOblast() {
        assertThat(r.resolve("ГУ Управление здравоохранения Акмолинской области")).isEqualTo("Акмолинская область");
    }
    @Test void oblastNotConfusedWithCity() {
        assertThat(r.resolve("Больница Алматинской области, г. Талдыкорган")).isEqualTo("Алматинская область");
    }
    @Test void abbreviationVKO() {
        assertThat(r.resolve("Поликлиника ВКО")).isEqualTo("Восточно-Казахстанская область");
    }
    @Test void astanaVariants() {
        assertThat(r.resolve("Больница г. Нур-Султан")).isEqualTo("г. Астана");
    }
    @Test void scansMultipleCandidatesAndReturnsNullWhenNone() {
        assertThat(r.resolve(null, "", "что-то без региона")).isNull();
        assertThat(r.resolve(null, "г. Шымкент")).isEqualTo("г. Шымкент");
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.RegionResolverTest" -q` (sandbox off)
Expected: FAIL — класса нет.

- [ ] **Step 3: Реализация** — `RegionResolver.java` (порядок записей: сначала области и аббревиатуры, города — последними; «Нур-Султан»→Астана):
```java
package com.vladoose.nir.integration.goszakup;

import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class RegionResolver {

    /** Паттерн (нижний регистр, подстрока) → каноническое имя региона (== одной из REGIONS на фронте). */
    private static final Map<String, String> PATTERNS = new LinkedHashMap<>();
    static {
        // --- области (специфичные подстроки идут раньше городов) ---
        PATTERNS.put("акмолинск", "Акмолинская область");
        PATTERNS.put("актюбинск", "Актюбинская область");
        PATTERNS.put("алматинск", "Алматинская область");
        PATTERNS.put("атырауск", "Атырауская область");
        PATTERNS.put("восточно-казахстанск", "Восточно-Казахстанская область");
        PATTERNS.put("вко", "Восточно-Казахстанская область");
        PATTERNS.put("жамбылск", "Жамбылская область");
        PATTERNS.put("западно-казахстанск", "Западно-Казахстанская область");
        PATTERNS.put("зко", "Западно-Казахстанская область");
        PATTERNS.put("карагандинск", "Карагандинская область");
        PATTERNS.put("костанайск", "Костанайская область");
        PATTERNS.put("кызылординск", "Кызылординская область");
        PATTERNS.put("мангистауск", "Мангистауская область");
        PATTERNS.put("мангыстауск", "Мангистауская область");
        PATTERNS.put("павлодарск", "Павлодарская область");
        PATTERNS.put("северо-казахстанск", "Северо-Казахстанская область");
        PATTERNS.put("ско", "Северо-Казахстанская область");
        PATTERNS.put("туркестанск", "Туркестанская область");
        PATTERNS.put("абайск", "Абайская область");
        PATTERNS.put("область абай", "Абайская область");
        PATTERNS.put("жетысуск", "Жетысуская область");
        PATTERNS.put("жетісу", "Жетысуская область");
        PATTERNS.put("улытауск", "Улытауская область");
        PATTERNS.put("ұлытау", "Улытауская область");
        // --- города республиканского значения ---
        PATTERNS.put("нур-султан", "г. Астана");
        PATTERNS.put("нұр-сұлтан", "г. Астана");
        PATTERNS.put("астана", "г. Астана");
        PATTERNS.put("шымкент", "г. Шымкент");
        PATTERNS.put("алматы", "г. Алматы");  // последним: омоним «алматинск» уже отработал выше
    }

    public String resolve(String... textCandidates) {
        if (textCandidates == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String c : textCandidates) {
            if (c != null && !c.isBlank()) sb.append(c.toLowerCase(Locale.ROOT)).append(" | ");
        }
        String hay = sb.toString();
        if (hay.isBlank()) return null;
        for (Map.Entry<String, String> e : PATTERNS.entrySet()) {
            if (hay.contains(e.getKey())) return e.getValue();
        }
        return null;
    }
}
```

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.RegionResolverTest" -q` (sandbox off)
Expected: PASS (6 тестов).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/vladoose/nir/integration/goszakup/RegionResolver.java \
        src/test/java/com/vladoose/nir/integration/goszakup/RegionResolverTest.java
git commit -m "feat(goszakup): резолвер региона по тексту (20 регионов РК)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `ImportSummary` + `GoszakupImportService` (ядро: 1 страница, фильтр, create)

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/ImportSummary.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java`
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/FakeGoszakupClient.java`
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java`

**Interfaces:**
- Consumes: `GoszakupClient`, `RegionResolver` (Task 2-3), `TenderRepository.findBySourceExtId` (Task 1).
- Produces:
  - `ImportSummary` (`@Data`): `boolean enabled; int fetched; int matched; int created; int updated; int skipped; String message;`
  - `GoszakupImportService.importMedicalTenders(): ImportSummary` (`@Transactional`).
  - Тестовый `FakeGoszakupClient implements GoszakupClient` с сеттерами для канона.

- [ ] **Step 1: Failing test** — сначала фейк-клиент `FakeGoszakupClient.java` (в test-исходниках):
```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Управляемый фейк: страницы по cursor, лоты и subject по ключу. Без сети. */
public class FakeGoszakupClient implements GoszakupClient {
    public boolean configured = true;
    /** cursor (null=первая) → страница. nextPage внутри dto задаёт следующий cursor. */
    public final Map<String, TrdBuyPageDto> pages = new HashMap<>();
    public final Map<String, List<LotDto>> lotsByAnno = new HashMap<>();
    public final Map<String, SubjectDto> subjectsByBin = new HashMap<>();

    @Override public boolean isConfigured() { return configured; }
    @Override public TrdBuyPageDto fetchTrdBuyPage(String cursor) {
        TrdBuyPageDto p = pages.get(cursor);
        if (p != null) return p;
        TrdBuyPageDto empty = new TrdBuyPageDto(); empty.setItems(new ArrayList<>()); return empty;
    }
    @Override public List<LotDto> fetchLots(String numberAnno) {
        return lotsByAnno.getOrDefault(numberAnno, List.of());
    }
    @Override public SubjectDto fetchSubject(String bin) { return subjectsByBin.get(bin); }

    // --- builders для тестов ---
    public static TrdBuyDto buy(String anno, String name, int status, String customerBin, String publishIso, String endIso) {
        TrdBuyDto d = new TrdBuyDto();
        d.setId((long) anno.hashCode()); d.setNumberAnno(anno); d.setNameRu(name);
        d.setRefBuyStatusId(status); d.setCustomerBin(customerBin);
        d.setPublishDate(publishIso); d.setEndDate(endIso);
        d.setTotalSum(new java.math.BigDecimal("1000000")); return d;
    }
    public TrdBuyPageDto page(String cursor, String nextPage, TrdBuyDto... items) {
        TrdBuyPageDto p = new TrdBuyPageDto();
        p.setItems(new ArrayList<>(List.of(items))); p.setNextPage(nextPage);
        pages.put(cursor, p); return p;
    }
}
```

Затем `GoszakupImportServiceTest.java` (Task 4-часть — фильтр, маппинг, create, рынок):
```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class GoszakupImportServiceTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired RegionResolver regionResolver;

    FakeGoszakupClient fake;
    GoszakupImportService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGoszakupClient();
        // keywords=аппарат,узи ; statuses пусто ; since-days большой ; max-pages 20
        service = new GoszakupImportService(fake, regionResolver, tenderRepository,
                "аппарат,узи", "", 3650, 20);
    }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void filtersByKeyword_andCreatesKzPublicTender() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ экспертного класса", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("200-1", "Канцелярские товары для офиса", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));

        ImportSummary s = service.importMedicalTenders();

        assertThat(s.getFetched()).isEqualTo(2);
        assertThat(s.getMatched()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);

        Optional<Tender> t = tenderRepository.findBySourceExtId("100-1");
        assertThat(t).isPresent();
        assertThat(t.get().getMarket()).isEqualTo(Market.KZ);
        assertThat(t.get().getSource()).isEqualTo(Source.PUBLIC_TENDER);
        assertThat(t.get().getCurrency()).isEqualTo("KZT");
        assertThat(t.get().getFacility()).isNull();
        assertThat(t.get().getDescription()).isEqualTo("Аппарат УЗИ экспертного класса");
        assertThat(tenderRepository.findBySourceExtId("200-1")).isEmpty(); // нерелевантный отброшен
    }

    @Test
    void importedTenderNotVisibleOnRf() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат ИВЛ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        service = new GoszakupImportService(fake, regionResolver, tenderRepository, "аппарат", "", 3650, 20);
        service.importMedicalTenders();

        MarketContext.set(Market.RF);
        assertThat(tenderRepository.findBySourceExtId("100-1")).isEmpty();
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest" -q` (sandbox off)
Expected: компиляция падает — `ImportSummary`/`GoszakupImportService` нет.

- [ ] **Step 3: `ImportSummary.java`**:
```java
package com.vladoose.nir.integration.goszakup;

import lombok.Data;

@Data
public class ImportSummary {
    private boolean enabled = true;
    private int fetched;
    private int matched;
    private int created;
    private int updated;
    private int skipped;
    private String message;
}
```

- [ ] **Step 4: `GoszakupImportService.java`** (ядро — одна страница, фильтр, create; лоты/регион/пагинация/идемпотентность — Task 5). Конструктор принимает конфиг через `@Value`, но в тесте создаётся вручную:
```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class GoszakupImportService {

    private final GoszakupClient client;
    private final RegionResolver regionResolver;
    private final TenderRepository tenderRepository;
    private final List<String> keywords;
    private final java.util.Set<Integer> statuses;
    private final int sinceDays;
    private final int maxPages;

    public GoszakupImportService(GoszakupClient client,
                                 RegionResolver regionResolver,
                                 TenderRepository tenderRepository,
                                 @Value("${goszakup.import.keywords:}") String keywordsCsv,
                                 @Value("${goszakup.import.statuses:}") String statusesCsv,
                                 @Value("${goszakup.import.since-days:30}") int sinceDays,
                                 @Value("${goszakup.import.max-pages:20}") int maxPages) {
        this.client = client;
        this.regionResolver = regionResolver;
        this.tenderRepository = tenderRepository;
        this.keywords = csv(keywordsCsv).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.statuses = csv(statusesCsv).stream().map(Integer::valueOf).collect(java.util.stream.Collectors.toSet());
        this.sinceDays = sinceDays;
        this.maxPages = maxPages;
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isBlank()).toList();
    }

    @Transactional
    public ImportSummary importMedicalTenders() {
        ImportSummary sum = new ImportSummary();
        if (!client.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return sum;
        }
        // Task 4: одна страница. Task 5 заменит на цикл по cursor + since-days + max-pages.
        TrdBuyPageDto page = client.fetchTrdBuyPage(null);
        List<TrdBuyDto> items = page.getItems() != null ? page.getItems() : List.of();
        sum.setFetched(items.size());
        for (TrdBuyDto d : items) {
            if (!statusOk(d) || !keywordOk(d)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
            sum.setMatched(sum.getMatched() + 1);
            upsert(d, sum);
        }
        sum.setMessage(String.format("Получено %d, релевантных %d, создано %d, обновлено %d",
                sum.getFetched(), sum.getMatched(), sum.getCreated(), sum.getUpdated()));
        return sum;
    }

    private boolean statusOk(TrdBuyDto d) {
        return statuses.isEmpty() || (d.getRefBuyStatusId() != null && statuses.contains(d.getRefBuyStatusId()));
    }
    private boolean keywordOk(TrdBuyDto d) {
        String name = d.getNameRu() == null ? "" : d.getNameRu().toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(name::contains);
    }

    /** Task 4: только create. Task 5 добавит ветку update + лоты + регион. */
    private void upsert(TrdBuyDto d, ImportSummary sum) {
        Tender t = new Tender();
        t.setSourceExtId(d.getNumberAnno());
        applyFields(t, d);
        tenderRepository.save(t);
        sum.setCreated(sum.getCreated() + 1);
    }

    private void applyFields(Tender t, TrdBuyDto d) {
        t.setTenderNumber(d.getNumberAnno());
        t.setSource(Source.PUBLIC_TENDER);
        t.setMarket(Market.KZ);
        t.setCurrency("KZT");
        t.setFacility(null);
        t.setStatus(mapStatus(d.getRefBuyStatusId()));
        t.setDescription(d.getNameRu());
        t.setTotalCost(d.getTotalSum());
        t.setPublishDate(parseDate(d.getPublishDate()));
        t.setDeadline(parseDate(d.getEndDate()));
        t.setCustomerBin(d.getCustomerBin());
    }

    static String mapStatus(Integer refBuyStatusId) {
        // дефолт: импортные тендеры считаем активными (точный маппинг id уточняется на Task 9)
        return "ACTIVE";
    }

    static LocalDate parseDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try { return LocalDate.parse(iso.substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}
```

- [ ] **Step 5: Run, verify PASS**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest" -q` (sandbox off)
Expected: PASS (2 теста).

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/vladoose/nir/integration/goszakup/ImportSummary.java \
        src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java \
        src/test/java/com/vladoose/nir/integration/goszakup/FakeGoszakupClient.java \
        src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java
git commit -m "feat(goszakup): импорт-сервис — фильтр по ключевым словам + создание KZ-тендеров

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Импорт — лоты + регион + пагинация + since-days + идемпотентность

**Files:**
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java`
- Modify: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java` (добавить тесты)

**Interfaces:**
- Consumes: `GoszakupClient.fetchLots`, `GoszakupClient.fetchSubject`, `RegionResolver.resolve`, `Tender.getLots()`.
- Produces: тот же `importMedicalTenders()`, теперь с полной семантикой (update/лоты/регион/пагинация).

- [ ] **Step 1: Failing tests** — добавить в `GoszakupImportServiceTest`:
```java
    @Test
    void idempotent_secondRunUpdatesNotDuplicates() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        com.vladoose.nir.integration.goszakup.dto.LotDto lot = new com.vladoose.nir.integration.goszakup.dto.LotDto();
        lot.setLotNumber("1"); lot.setNameRu("Аппарат УЗИ портативный");
        lot.setAmount(new java.math.BigDecimal("6000000")); lot.setCount(2);
        fake.lotsByAnno.put("100-1", java.util.List.of(lot));

        service = new GoszakupImportService(fake, regionResolver, tenderRepository, "аппарат", "", 3650, 20);
        ImportSummary first = service.importMedicalTenders();
        assertThat(first.getCreated()).isEqualTo(1);

        // меняем сумму и лот → второй прогон обновляет
        fake.pages.get(null).getItems().get(0).setTotalSum(new java.math.BigDecimal("9999999"));
        ImportSummary second = service.importMedicalTenders();
        assertThat(second.getCreated()).isEqualTo(0);
        assertThat(second.getUpdated()).isEqualTo(1);

        var t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(tenderRepository.findAll().stream()
                .filter(x -> "100-1".equals(x.getSourceExtId())).count()).isEqualTo(1); // нет дублей
        assertThat(t.getTotalCost()).isEqualByComparingTo("9999999");
        assertThat(t.getLots()).hasSize(1);
        assertThat(t.getLots().get(0).getEquipName()).isEqualTo("Аппарат УЗИ портативный");
        assertThat(t.getLots().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void resolvesRegionFromSubject() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        com.vladoose.nir.integration.goszakup.dto.SubjectDto subj = new com.vladoose.nir.integration.goszakup.dto.SubjectDto();
        subj.setBin("BIN1"); subj.setNameRu("Городская поликлиника №5 г. Алматы");
        fake.subjectsByBin.put("BIN1", subj);

        service = new GoszakupImportService(fake, regionResolver, tenderRepository, "аппарат", "", 3650, 20);
        service.importMedicalTenders();

        var t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(t.getRegion()).isEqualTo("г. Алматы");
        assertThat(t.getCustomerName()).isEqualTo("Городская поликлиника №5 г. Алматы");
    }

    @Test
    void paginatesAcrossPages_andSkipsOldBySinceDays() {
        // since-days=30; даты считаем от now() — тест стабилен в любой день прогона
        String recentIso = java.time.LocalDate.now().minusDays(5) + "T00:00:00";   // в пределах 30 дней
        String oldIso = java.time.LocalDate.now().minusDays(400) + "T00:00:00";    // старше 30 дней
        fake.page(null, "/v2/trd-buy?page=next&search_after=1",
                FakeGoszakupClient.buy("NEW-1", "Аппарат УЗИ", 230, "BIN1", recentIso, recentIso));
        fake.page("/v2/trd-buy?page=next&search_after=1", null,
                FakeGoszakupClient.buy("OLD-1", "Аппарат УЗИ", 230, "BIN2", oldIso, oldIso));

        service = new GoszakupImportService(fake, regionResolver, tenderRepository, "аппарат", "", 30, 20);
        ImportSummary s = service.importMedicalTenders();

        assertThat(s.getFetched()).isEqualTo(2);
        assertThat(tenderRepository.findBySourceExtId("NEW-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("OLD-1")).isEmpty(); // старше since-days
    }
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest" -q` (sandbox off)
Expected: FAIL — пагинации/лотов/региона/update ещё нет (старое поведение: одна страница, только create, без лотов).

- [ ] **Step 3: Заменить тело сервиса** — обновить `importMedicalTenders()` + `upsert()` в `GoszakupImportService.java`:

Заменить метод `importMedicalTenders()`:
```java
    @Transactional
    public ImportSummary importMedicalTenders() {
        ImportSummary sum = new ImportSummary();
        if (!client.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return sum;
        }
        LocalDate cutoff = LocalDate.now().minusDays(sinceDays);
        String cursor = null;
        int pagesRead = 0;
        do {
            TrdBuyPageDto page = client.fetchTrdBuyPage(cursor);
            List<TrdBuyDto> items = page.getItems() != null ? page.getItems() : List.of();
            for (TrdBuyDto d : items) {
                sum.setFetched(sum.getFetched() + 1);
                LocalDate pub = parseDate(d.getPublishDate());
                if (pub != null && pub.isBefore(cutoff)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
                if (!statusOk(d) || !keywordOk(d)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
                sum.setMatched(sum.getMatched() + 1);
                upsert(d, sum);
            }
            cursor = page.getNextPage();
            pagesRead++;
        } while (cursor != null && !cursor.isBlank() && pagesRead < maxPages);

        sum.setMessage(String.format("Получено %d, релевантных %d, создано %d, обновлено %d",
                sum.getFetched(), sum.getMatched(), sum.getCreated(), sum.getUpdated()));
        return sum;
    }
```

Заменить метод `upsert()` (ветка update + лоты + регион через коллекцию):
```java
    private void upsert(TrdBuyDto d, ImportSummary sum) {
        Tender t = tenderRepository.findBySourceExtId(d.getNumberAnno()).orElse(null);
        boolean isNew = (t == null);
        if (isNew) { t = new Tender(); t.setSourceExtId(d.getNumberAnno()); }
        applyFields(t, d);
        resolveRegion(t, d);
        rebuildLots(t, d);
        tenderRepository.save(t);
        if (isNew) sum.setCreated(sum.getCreated() + 1);
        else sum.setUpdated(sum.getUpdated() + 1);
    }

    private void resolveRegion(Tender t, TrdBuyDto d) {
        com.vladoose.nir.integration.goszakup.dto.SubjectDto subj = client.fetchSubject(d.getCustomerBin());
        String customerName = subj != null ? subj.getNameRu() : null;
        String address = subj != null ? subj.getAddress() : null;
        String kato = subj != null ? subj.getKatoId() : null;
        t.setCustomerName(customerName);
        t.setRegionKato(kato);
        if (address != null) t.setDeliveryAddress(address);
        t.setRegion(regionResolver.resolve(customerName, address)); // null допустим
    }

    private void rebuildLots(Tender t, TrdBuyDto d) {
        // §7/§14: управлять лотами ТОЛЬКО через коллекцию (orphanRemoval), не через repository.delete
        t.getLots().clear();
        List<com.vladoose.nir.integration.goszakup.dto.LotDto> lots = client.fetchLots(d.getNumberAnno());
        for (com.vladoose.nir.integration.goszakup.dto.LotDto l : lots) {
            com.vladoose.nir.entity.TenderLot lot = new com.vladoose.nir.entity.TenderLot();
            lot.setTender(t);
            lot.setLotNumber(parseInt(l.getLotNumber()));
            lot.setEquipName(l.getNameRu());
            lot.setQuantity(l.getCount());
            lot.setMaxCost(l.getAmount());
            t.getLots().add(lot);
        }
    }

    static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }
```

(Старый `applyFields` оставить как есть; `customerBin` он уже ставит. `mapStatus`/`parseDate` без изменений.)

- [ ] **Step 4: Run, verify PASS**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest" -q` (sandbox off)
Expected: PASS (5 тестов: 2 из Task 4 + 3 новых).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java \
        src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java
git commit -m "feat(goszakup): лоты + регион + пагинация + since-days + идемпотентность

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Планировщик + REST-эндпоинт

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportScheduler.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderController.java`
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportSchedulerTest.java`

**Interfaces:**
- Consumes: `GoszakupImportService.importMedicalTenders()`.
- Produces: `GoszakupImportScheduler.run(): ImportSummary` (ставит `MarketContext.set(KZ)` → вызывает сервис → `clear()`); `tick()` под `@Scheduled` + флаг. REST `POST /api/tenders/import-kz` → `ImportSummary`.

- [ ] **Step 1: Failing test** — `GoszakupImportSchedulerTest.java` (Mockito; проверяет дисциплину MarketContext и гейт флага):
```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoszakupImportSchedulerTest {

    @Test
    void run_setsKzMarketContext_aroundServiceCall() {
        GoszakupImportService service = mock(GoszakupImportService.class);
        AtomicReference<Market> seen = new AtomicReference<>();
        when(service.importMedicalTenders()).thenAnswer(inv -> { seen.set(MarketContext.get()); return new ImportSummary(); });

        GoszakupImportScheduler scheduler = new GoszakupImportScheduler(service, true);
        scheduler.run();

        assertThat(seen.get()).isEqualTo(Market.KZ);      // KZ во время вызова
        assertThat(MarketContext.get()).isEqualTo(Market.RF); // очищено после (дефолт RF)
    }

    @Test
    void tick_disabled_doesNotCallService() {
        GoszakupImportService service = mock(GoszakupImportService.class);
        new GoszakupImportScheduler(service, false).tick();
        verifyNoInteractions(service);
    }

    @Test
    void tick_enabled_callsService() {
        GoszakupImportService service = mock(GoszakupImportService.class);
        when(service.importMedicalTenders()).thenReturn(new ImportSummary());
        new GoszakupImportScheduler(service, true).tick();
        verify(service, times(1)).importMedicalTenders();
        MarketContext.clear();
    }
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportSchedulerTest" -q` (sandbox off)
Expected: FAIL — класса `GoszakupImportScheduler` нет.

- [ ] **Step 3: `GoszakupImportScheduler.java`** (зеркало `MailPollScheduler`):
```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GoszakupImportScheduler {

    private final GoszakupImportService importService;
    private final boolean enabled;

    public GoszakupImportScheduler(GoszakupImportService importService,
                                   @Value("${goszakup.import.enabled:false}") boolean enabled) {
        this.importService = importService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${goszakup.import.poll-ms:21600000}")
    public void tick() {
        if (enabled) run();
    }

    /** §6: ставит рынок KZ вокруг @Transactional-сервиса (отдельный бин → аспект/прокси работают), чистит. */
    public ImportSummary run() {
        MarketContext.set(Market.KZ);
        try {
            return importService.importMedicalTenders();
        } finally {
            MarketContext.clear();
        }
    }
}
```

- [ ] **Step 4: REST-эндпоинт** — в `TenderController.java` добавить импорты и поле + метод. В конструктор добавить `GoszakupImportScheduler`:

Импорты (вверх): `import com.vladoose.nir.integration.goszakup.GoszakupImportScheduler;` и `import com.vladoose.nir.integration.goszakup.ImportSummary;` и `import org.springframework.security.access.prepost.PreAuthorize;`

Поле + конструктор: добавить `private final GoszakupImportScheduler goszakupScheduler;` и параметр в конструкторе с присваиванием.

Метод (после `getApplies`):
```java
    @PostMapping("/import-kz")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportSummary importKz() {
        // рынок KZ ставится явно внутри scheduler.run() (§6), независимо от X-Market
        return goszakupScheduler.run();
    }
```

- [ ] **Step 5: Run, verify PASS**

Run: `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportSchedulerTest" -q` (sandbox off)
Expected: PASS (3 теста).

- [ ] **Step 6: Полный прогон бэка** (убедиться, что ничего не сломали)

Run: `lsof -ti :8080 | xargs kill -9; ./gradlew test -q` (sandbox off)
Expected: зелено, КРОМЕ пред-существующих `ApplyAutoFillServiceTest` (2). Других падений быть не должно.

- [ ] **Step 7: Commit**
```bash
git add src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportScheduler.java \
        src/main/java/com/vladoose/nir/controller/TenderController.java \
        src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportSchedulerTest.java
git commit -m "feat(goszakup): планировщик (§6) + POST /api/tenders/import-kz

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Фронт — кнопка «Обновить тендеры» (KZ)

**Files:**
- Modify: `frontend/src/app/services/api.service.ts` (после `getTenderApplies`, ~стр. 77)
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts` (toolbar ~стр. 42-45; класс ~стр. 615)

**Interfaces:**
- Produces: `ApiService.importKzTenders(): Observable<any>` → `POST /api/tenders/import-kz`. Компонент: поле `importing = false`, метод `onImportKz()`.

- [ ] **Step 1: `ApiService.importKzTenders`** — в `api.service.ts` в секции `// === Tenders ===`:
```typescript
  importKzTenders(): Observable<any> {
    return this.http.post<any>(`${this.base}/tenders/import-kz`, {});
  }
```

- [ ] **Step 2: Поле + метод в компоненте** — в `TendersComponent` (рядом с `loadTenders`):
```typescript
  importing = false;

  isKz(): boolean { return this.market.value === 'KZ'; }

  onImportKz() {
    this.importing = true;
    this.api.importKzTenders().subscribe({
      next: (s: any) => {
        this.importing = false;
        if (s && s.enabled === false) {
          this.notify.error(s.message || 'Импорт выключен: не настроен токен goszakup');
        } else {
          this.notify.success(`Импорт завершён: создано ${s?.created ?? 0}, обновлено ${s?.updated ?? 0}`);
          this.loadTenders();
        }
        this.cdr.detectChanges();
      },
      error: err => {
        this.importing = false;
        this.notify.error('Ошибка импорта: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }
```

- [ ] **Step 3: Кнопка в toolbar** — в шаблоне (блок `.toolbar`, после кнопки «Добавить тендер»):
```html
        <button class="btn btn-add" *ngIf="isKz() && !showTenderForm" (click)="onImportKz()" [disabled]="importing">
          {{ importing ? 'Обновление…' : 'Обновить тендеры' }}
        </button>
```

- [ ] **Step 4: Build-гейт**

Run: `cd frontend && npm run build` (sandbox off если нужно)
Expected: BUILD SUCCESS, без ошибок типов.

- [ ] **Step 5: Commit**
```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(tenders-ui): кнопка «Обновить тендеры» для рынка KZ

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Фронт — фильтр по региону + показ региона/заказчика

**Files:**
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts` (фильтры ~стр. 25-40; класс ~стр. 420, 622, 640; карточка ~стр. 117-129)

**Interfaces:**
- Consumes: `t.region`, `t.customerName` из `TenderResponse` (Task 1).
- Produces: `REGIONS: string[]` (20 регионов — СТРОГО как в `RegionResolver`, Task 3), поле `filterRegion = ''`, фильтрация в `applyTendersFilter`.

- [ ] **Step 1: Константа REGIONS + поле** — в `TendersComponent` (вверху класса, рядом с `filterStatus`):
```typescript
  filterRegion = '';
  readonly REGIONS: string[] = [
    'г. Астана', 'г. Алматы', 'г. Шымкент',
    'Абайская область', 'Акмолинская область', 'Актюбинская область', 'Алматинская область',
    'Атырауская область', 'Восточно-Казахстанская область', 'Жамбылская область',
    'Жетысуская область', 'Западно-Казахстанская область', 'Карагандинская область',
    'Костанайская область', 'Кызылординская область', 'Мангистауская область',
    'Павлодарская область', 'Северо-Казахстанская область', 'Туркестанская область',
    'Улытауская область'
  ];
```

- [ ] **Step 2: Select в фильтрах** — в шаблоне `.filters`, перед кнопкой «Сбросить» (виден только на KZ):
```html
        <select *ngIf="isKz()" [(ngModel)]="filterRegion" (change)="applyTendersFilter()" class="filter-select" title="Регион">
          <option value="">Все регионы</option>
          <option value="__none__">Регион не указан</option>
          <option *ngFor="let r of REGIONS" [value]="r">{{ r }}</option>
        </select>
```
(`isKz()` уже добавлен в Task 7. Если Task 7 не выполнен — добавить `isKz()` здесь.)

- [ ] **Step 3: Фильтрация по региону** — в `applyTendersFilter()`, ДОБАВИТЬ перед `return true;`:
```typescript
      if (this.filterRegion === '__none__') { if (t.region) return false; }
      else if (this.filterRegion && t.region !== this.filterRegion) return false;
```

- [ ] **Step 4: Сброс региона** — в `resetTendersFilter()` добавить:
```typescript
    this.filterRegion = '';
```

- [ ] **Step 5: Показ региона/заказчика на карточке** — в шаблоне карточки, в блоке `.tender-card-details` добавить строку (после строки с «Заказчик»/«Способ закупки»):
```html
          <div class="detail-row" *ngIf="t.region || t.customerName">
            <div class="detail"><span class="detail-label">Регион</span><span>{{ t.region || '—' }}</span></div>
            <div class="detail"><span class="detail-label">Госзаказчик</span><span>{{ t.customerName || '—' }}</span></div>
          </div>
```

- [ ] **Step 6: Build-гейт**

Run: `cd frontend && npm run build`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**
```bash
git add frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(tenders-ui): фильтр по региону (KZ) + регион/заказчик на карточке

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Живая проверка токеном (token-gated) + финальное связывание

> **Требует `GOSZAKUP_TOKEN`** (положить в `/tmp/goszakup.token`, не коммитить). Выполняется, когда токен получен. Закрывает 2 точки верификации из спеки.

**Hardening-гейты из финального whole-branch ревью (must-do на T9 — валидируются живым трафиком):**
1. **Вынести сетевой I/O из транзакции + per-item устойчивость.** Сейчас весь `importMedicalTenders()` — одна `@Transactional`, внутри блокирующие HTTP (страницы + per-item `fetchSubject`/`fetchLots`): до 1000×2 вызовов по 30с держат DB-коннект минутами, а одна ошибка в середине роллбэчит весь прогон. Перестроить: сетевые fetch — ВНЕ транзакции; апсерт одного тендера — `@Transactional` на ОТДЕЛЬНОМ бине (per-item граница); цикл ловит ошибку элемента в try/catch → инкремент счётчика, продолжает. **Вернуть `ImportSummary.errors`** (спека §3.2 его закладывала, реализация потеряла) и показывать в сводке/нотификации.
2. **Сузить `GoszakupHttpClient.fetchSubject`** с `catch(RuntimeException)→null` до настоящего not-found (404) — остальные ошибки (500/таймаут) пробрасывать/считать как ошибку элемента (см. гейт 1), а не молча терять регион. Сейчас (полиш-волна) уже логируется `log.warn`; на T9 — довести до корректного разделения 404 vs сбой.

**Files:**
- Возможные правки: `src/main/java/com/vladoose/nir/integration/goszakup/dto/SubjectDto.java` (реальные имена полей региона/КАТО), `GoszakupImportService.mapStatus` / `resolveRegion`, `application.yaml` (`goszakup.import.statuses` — реальные id).

- [ ] **Step 1: Пробой схемы** — выяснить реальные поля и значения:
```bash
TOKEN="$(cat /tmp/goszakup.token)"
# объявления (структура + статусы)
curl -s -H "Authorization: Bearer $TOKEN" "https://ows.goszakup.gov.kz/v2/trd-buy?limit=2" | python3 -m json.tool | head -80
# subject заказчика (где КАТО/регион/адрес) — bin взять из объявления выше
curl -s -H "Authorization: Bearer $TOKEN" "https://ows.goszakup.gov.kz/v2/subject/<BIN>" | python3 -m json.tool
# лоты
curl -s -H "Authorization: Bearer $TOKEN" "https://ows.goszakup.gov.kz/v2/lots/number-anno/<ANNO>" | python3 -m json.tool
```
Зафиксировать: (а) точное поле КАТО/региона/адреса в `subject` → поправить `@JsonProperty` в `SubjectDto`; (б) `ref_buy_status_id` активных статусов приёма заявок → вписать в `goszakup.import.statuses`; (в) маппинг статусов → уточнить `mapStatus()` при необходимости. Если КАТО приходит кодом — на этом шаге можно добавить числовой маппер КАТО→регион (опционально; текст-резолвер уже работает по имени заказчика).

- [ ] **Step 2: Догнать тесты** — если менялись поля `SubjectDto`/`mapStatus`, обновить канон в `GoszakupDtoJsonTest`/`GoszakupImportServiceTest`, прогнать:
```bash
./gradlew test --tests "com.vladoose.nir.integration.goszakup.*" -q   # sandbox off
```

- [ ] **Step 3: Живой импорт** — поднять бэк с токеном и дёрнуть импорт:
```bash
lsof -ti :8080 | xargs kill -9
GOSZAKUP_IMPORT_ENABLED=true GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" ./gradlew bootRun   # sandbox off, фоном
# затем (после старта) ручной запуск под admin через UI-кнопку, либо проверить лог планировщика
```

- [ ] **Step 4: Браузерная проверка (Playwright, §5 CLAUDE.md)** — `http://localhost:4200`, логин admin/admin, `localStorage['ais.market']='KZ'`, страница «Тендеры»:
  - кнопка «Обновить тендеры» → нотификация со сводкой; список наполнился;
  - фильтр по региону → отсев работает;
  - карточка показывает регион + госзаказчика; ссылка «Госзакуп» ведёт на goszakup.gov.kz;
  - переключить на РФ → импортных KZ-тендеров не видно (изоляция).

- [ ] **Step 5: Commit** (если были правки кода/конфига)
```bash
git add -A
git commit -m "fix(goszakup): реальные поля subject/статусы по живому API + проверка

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Самопроверка плана (выполнена)

- **Покрытие спеки:** §2 источник/токен → Task 2,9; §3.1 клиент → Task 2; §3.2 импорт-сервис (фильтр/upsert/идемпотентность) → Task 4,5; §3.3 планировщик → Task 6; §3.4 REST → Task 6; §4 модель V3 → Task 1; §5 резолв региона → Task 3,5,9; §6 фронт (кнопка+фильтр+карточка) → Task 7,8; §7 конфиг → Task 2; §8 тесты → Task 1-6; §5-точки-верификации токеном → Task 9. Пробелов нет.
- **Плейсхолдеры:** код приведён целиком в каждом шаге. Намеренно отложенные под токен значения (точные id статусов, имена полей региона в `SubjectDto`) изолированы в Task 9 с дефолтами, которые работают (statuses пусто = все; регион по имени заказчика).
- **Согласованность типов:** `findBySourceExtId` (Task 1) ↔ used (Task 4,5); `GoszakupClient` методы (Task 2) ↔ `FakeGoszakupClient` (Task 4) ↔ вызовы (Task 5); `ImportSummary` поля ↔ фронт `onImportKz` (Task 7); `REGIONS` (Task 8) ↔ `RegionResolver` выходы (Task 3) — список регионов идентичен.

## Финал блока (после Task 9)

Whole-branch ревью (Opus) по §3 CLAUDE.md → мерж `feat/goszakup-kz-tender-import` в `main` через `--ff-only` → удалить ветку.
