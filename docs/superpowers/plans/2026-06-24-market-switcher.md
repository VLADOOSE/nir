# Переключатель рынков (Блок A) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Сделать АИС двух-рыночной (Регион-Мед РФ / West-Med KZ): глобальный переключатель в шапке, разделение данных по колонке `market`, валюта и идентичность компании по активному рынку.

**Architecture:** Общая БД + колонка `market` (RF/KZ) на 6 «вселенных» таблицах. Чтение скоупится Hibernate `@Filter`, включаемым per-request из заголовка `X-Market` (через `HandlerInterceptor.preHandle` — после того как OSIV привязал EntityManager). Запись штампуется активным рынком из `MarketContext` (ThreadLocal). Фронт: `MarketService` + интерсептор заголовка + селектор в шапке + валюта по рынку. Спека: `docs/superpowers/specs/2026-06-24-market-switcher-design.md`.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA / Hibernate 6, MapStruct, Lombok, PostgreSQL (+pg_trgm); Angular 21 (standalone, SCSS).

## Global Constraints

- Java **17**, Spring Boot **3.5.6**, Gradle wrapper **8.14** — не менять.
- Backend пакет-корень `com.vladoose.nir`. Entity на Lombok (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`); enum-поля — `@Enumerated(EnumType.STRING)` + `@Column(..., nullable=false)` + `@Builder.Default` (паттерн из `MedEquipment.registrationStatus`). Сервисы — `@Service`, constructor injection без `@Autowired`, `@Transactional` на записи. Контроллеры `@RestController @RequestMapping("/api/...")`.
- БД PostgreSQL `localhost:5432/nirdb` (UTF-8 локаль). `ddl-auto: none`, `spring.sql.init.mode: always` — `schema.sql` пересоздаёт таблицы на старте (кроме живучей `med_registry`), сид — `data.sql`. **Колонка `market` — `VARCHAR(2) NOT NULL DEFAULT 'RF'`**, чтобы существующий `data.sql` (RF) грузился без правок.
- **Рынки:** ровно два — `RF` (Регион-Мед, валюта RUB, символ ₽), `KZ` (West-Med, валюта KZT, символ ₸).
- **OSIV включён** (`spring.jpa.open-in-view=true`, дефолт) — Hibernate `@Filter` включать в `HandlerInterceptor.preHandle` (после привязки EntityManager интерсептором OSIV), НЕ в servlet-фильтре (тот выполнится раньше OSIV).
- **КРИТИЧНО для среды:** Bash-sandbox блокирует localhost:5432 → запускать ЛЮБЫЕ `./gradlew` и DB-команды с флагом `dangerouslyDisableSandbox: true`. `psql` — по пути `/Library/PostgreSQL/17/bin/psql`.
- **Известные PRE-EXISTING падения, НЕ наши:** `ApplyAutoFillServiceTest.autoFill_picksCheapestResponsePerLot` и `autoFill_reportsLotsWithoutResponse` (устаревшие против фичи наценки). `./gradlew test` из-за них = BUILD FAILED с этими 2 падениями — это норма; гейт задач: «компилируется + только эти 2 падения, никаких новых».
- Фронт: Angular standalone, инлайн-шаблон + `styles: []` (SCSS), `ApiService` (база `/api`, прокси :8080), `NotificationService.success/error`. Фронт-тестов нет → гейт = `npm run build` без ошибок + ручная проверка. Сборка: `cd frontend && npm run build` (если падает по sandbox — повторить с `dangerouslyDisableSandbox: true`).
- Каждый git-commit заканчивать: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Ветка уже создана: `feat/market-switcher` (содержит фикс иконки + спеку). Работаем в ней. Рабочее дерево содержит несвязанные изменения пользователя (WIP) — НЕ трогать и НЕ коммитить их; каждый commit стейджит только файлы своей задачи.

---

### Task 1: Реестр-полиш — `word_similarity` в матчинге кандидатов (TDD)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java`
- Test: `src/test/java/com/vladoose/nir/repository/MedRegistryRepositoryTest.java` (дополнить)

**Interfaces:**
- Produces: `findCandidates(name, manufact, limit)` теперь матчит короткий бренд против длинного юр-имени (Mindray → «Shenzhen Mindray Bio-Medical Electronics»).

Независимая полировка реестра (из обещанного). Делаем первой — короткая и развязанная.

- [ ] **Step 1: Дополнить тест — короткий бренд находит длинное имя**

В `MedRegistryRepositoryTest.java` добавить тест:
```java
    @Test
    void findCandidates_shortBrandMatchesLongProducerName_viaWordSimilarity() {
        repository.save(row("ZZWS-001", "Электрокардиограф BeneHeart R12",
                "Shenzhen ZZBrandUniq Bio-Medical Electronics Co., Ltd."));
        repository.flush();
        // короткий бренд как слово внутри длинного производителя
        List<RegistryCandidateRow> result =
                repository.findCandidates("Электрокардиограф", "ZZBrandUniq", 5);
        assertThat(result).extracting(RegistryCandidateRow::getRegNumber).contains("ZZWS-001");
    }
```
(`row(...)` — существующий хелпер в этом тест-классе.)

- [ ] **Step 2: Запустить — убедиться, что падает**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.repository.MedRegistryRepositoryTest"`
Expected: новый тест FAIL — текущий `%` (similarity) не ловит короткий бренд в длинном имени (score < 0.3).

- [ ] **Step 3: Добавить word_similarity в запрос**

В `MedRegistryRepository.findCandidates` заменить запрос на (учитываем И триграммную, И пословную похожесть; `<%` = word_similarity-оператор: левый аргумент — слово/набор, ищется внутри правого):
```java
    @Query(nativeQuery = true, value =
            "SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, " +
            "m.country AS country, m.reg_date AS regDate, m.expiration_date AS expirationDate, " +
            "m.unlimited AS unlimited, " +
            "(0.6 * GREATEST(similarity(m.producer, :manufact), word_similarity(:manufact, m.producer)) + " +
            " 0.4 * GREATEST(similarity(m.name, :name),         word_similarity(:name, m.name))) AS score " +
            "FROM med_registry m " +
            "WHERE m.producer % :manufact OR m.name % :name " +
            "   OR :manufact <% m.producer OR :name <% m.name " +
            "ORDER BY score DESC " +
            "LIMIT :limit")
```
(Скор берёт максимум из similarity/word_similarity по каждому полю; WHERE добавляет `<%` к существующему `%`.)

- [ ] **Step 4: Запустить — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.repository.MedRegistryRepositoryTest"`
Expected: PASS (все тесты класса, включая прежние и новый).

- [ ] **Step 5: Проверить на реальных данных**

Run (sandbox off):
```bash
/Library/PostgreSQL/17/bin/psql -h localhost -U postgres -d nirdb -c "
SELECT round((0.6*GREATEST(similarity(m.producer,'Mindray'),word_similarity('Mindray',m.producer)))::numeric,3) sc,
       m.reg_number, left(m.producer,40) producer
FROM med_registry m
WHERE m.producer % 'Mindray' OR 'Mindray' <% m.producer
ORDER BY sc DESC LIMIT 3;" 2>&1 | head
```
(`PGPASSWORD=admin` перед командой.) Expected: «Shenzhen Mindray Bio-Medical Electronics» в выдаче со score > 0.5.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java src/test/java/com/vladoose/nir/repository/MedRegistryRepositoryTest.java
git commit -m "$(cat <<'EOF'
feat(registry): word_similarity в матчинге — короткий бренд находит длинное юр-имя

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `Market` enum, `MarketContext`, схема + поле `market` на 6 entity (+ Hibernate @Filter аннотации)

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/Market.java`
- Create: `src/main/java/com/vladoose/nir/context/MarketContext.java`
- Modify: `src/main/resources/schema.sql`
- Modify entities: `Tender.java`, `Facility.java`, `Distributor.java`, `MedEquipment.java`, `ActivityApply.java`, `PriceRequest.java`

**Interfaces:**
- Produces:
  - `Market { RF, KZ }` с `currencyCode()` (RUB/KZT), `currencySymbol()` (₽/₸), `companyShortName()`; `Market.fromHeader(String) : Market` (дефолт RF).
  - `MarketContext` (ThreadLocal): `static Market get()` (дефолт RF), `static void set(Market)`, `static void clear()`.
  - На каждой из 6 entity: поле `market` (`Market`, `@Enumerated(STRING)`, `@Builder.Default = Market.RF`) + класс помечен `@Filter(name="marketFilter", condition="market = :market")`. `@FilterDef` — один раз (в `Market.java` через package… нет — на `Tender.java`).
  - Колонка `market VARCHAR(2) NOT NULL DEFAULT 'RF'` + индекс на 6 таблицах.

- [ ] **Step 1: Создать enum `Market`**

Create `src/main/java/com/vladoose/nir/entity/Market.java`:
```java
package com.vladoose.nir.entity;

public enum Market {
    RF("RUB", "₽", "ООО «РЕГИОН-МЕД»"),
    KZ("KZT", "₸", "ТОО «West-Med»");

    private final String currencyCode;
    private final String currencySymbol;
    private final String companyShortName;

    Market(String currencyCode, String currencySymbol, String companyShortName) {
        this.currencyCode = currencyCode;
        this.currencySymbol = currencySymbol;
        this.companyShortName = companyShortName;
    }

    public String currencyCode()     { return currencyCode; }
    public String currencySymbol()   { return currencySymbol; }
    public String companyShortName() { return companyShortName; }

    /** Парсит заголовок X-Market; неизвестное/пустое → RF. */
    public static Market fromHeader(String raw) {
        if (raw == null) return RF;
        try { return Market.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return RF; }
    }
}
```

- [ ] **Step 2: Создать `MarketContext`**

Create `src/main/java/com/vladoose/nir/context/MarketContext.java`:
```java
package com.vladoose.nir.context;

import com.vladoose.nir.entity.Market;

/** Активный рынок текущего HTTP-запроса (ThreadLocal). */
public final class MarketContext {
    private static final ThreadLocal<Market> CURRENT = new ThreadLocal<>();

    private MarketContext() {}

    public static Market get() {
        Market m = CURRENT.get();
        return m != null ? m : Market.RF;
    }
    public static void set(Market market) { CURRENT.set(market); }
    public static void clear() { CURRENT.remove(); }
}
```

- [ ] **Step 3: Добавить колонку `market` в `schema.sql` для 6 таблиц**

В `src/main/resources/schema.sql` в каждом `CREATE TABLE` для `tender`, `facility`, `distributor`, `med_equipment`, `activity_apply`, `price_request` добавить колонку (например, последней колонкой перед закрывающей `)` или CONSTRAINT-блоком):
```sql
    market VARCHAR(2) NOT NULL DEFAULT 'RF',
```
И после каждого `CREATE TABLE` добавить индекс, например:
```sql
CREATE INDEX IF NOT EXISTS idx_tender_market         ON tender(market);
CREATE INDEX IF NOT EXISTS idx_facility_market       ON facility(market);
CREATE INDEX IF NOT EXISTS idx_distributor_market    ON distributor(market);
CREATE INDEX IF NOT EXISTS idx_med_equipment_market  ON med_equipment(market);
CREATE INDEX IF NOT EXISTS idx_activity_apply_market ON activity_apply(market);
CREATE INDEX IF NOT EXISTS idx_price_request_market  ON price_request(market);
```
(`facility`/`distributor` имеют UNIQUE на `name`/`inn` — оставить как есть; для MVP имена уникальны глобально. Это осознанное ограничение, не трогаем.)

- [ ] **Step 4: Добавить `@FilterDef` + поле `market` + `@Filter` на `Tender`**

В `src/main/java/com/vladoose/nir/entity/Tender.java`:
1. Импорты: `import com.vladoose.nir.entity.Market;` (тот же пакет — не нужен), `import jakarta.persistence.*;`, `import org.hibernate.annotations.*;`
2. Над классом добавить (FilterDef — один на всё приложение, объявляем здесь):
```java
@FilterDef(name = "marketFilter", parameters = @ParamDef(name = "market", type = String.class))
@Filter(name = "marketFilter", condition = "market = :market")
```
3. Поле (рядом с остальными):
```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    @Builder.Default
    private Market market = Market.RF;
```

- [ ] **Step 5: Добавить поле `market` + `@Filter` на остальные 5 entity**

В `Facility.java`, `Distributor.java`, `MedEquipment.java`, `ActivityApply.java`, `PriceRequest.java`:
1. Над классом: `@Filter(name = "marketFilter", condition = "market = :market")` (импорт `org.hibernate.annotations.Filter`). (`@FilterDef` НЕ дублировать — он один, на `Tender`.)
2. То же поле `market`:
```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    @Builder.Default
    private Market market = Market.RF;
```
(Импорт `Market` не нужен — тот же пакет `entity`.)

- [ ] **Step 6: Проверить, что контекст поднимается и схема применяется**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.Nir2ApplicationTests"`
Expected: PASS — приложение стартует, `schema.sql` с колонками `market` применяется, `data.sql` грузится (все записи получают `market='RF'` через DEFAULT).

- [ ] **Step 7: Проверить структуру**

Run (sandbox off):
```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -h localhost -U postgres -d nirdb -c "
SELECT table_name FROM information_schema.columns WHERE column_name='market' ORDER BY table_name;" -c "
SELECT market, count(*) FROM tender GROUP BY market;"
```
Expected: колонка `market` на 6 таблицах; все существующие тендеры — `RF`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/entity/Market.java src/main/java/com/vladoose/nir/context/MarketContext.java src/main/resources/schema.sql src/main/java/com/vladoose/nir/entity/Tender.java src/main/java/com/vladoose/nir/entity/Facility.java src/main/java/com/vladoose/nir/entity/Distributor.java src/main/java/com/vladoose/nir/entity/MedEquipment.java src/main/java/com/vladoose/nir/entity/ActivityApply.java src/main/java/com/vladoose/nir/entity/PriceRequest.java
git commit -m "$(cat <<'EOF'
feat(market): enum Market + MarketContext + колонка market на 6 entity (@Filter)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Скоупинг чтения — интерсептор включает Hibernate-фильтр из X-Market (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/config/MarketInterceptor.java`
- Create: `src/main/java/com/vladoose/nir/config/WebMvcMarketConfig.java`
- Test: `src/test/java/com/vladoose/nir/market/MarketScopingTest.java`

**Interfaces:**
- Consumes: `Market`, `MarketContext` (Task 2), Hibernate `@Filter` на entity (Task 2).
- Produces: per-request — заголовок `X-Market` → `MarketContext` + включённый Hibernate-фильтр `marketFilter` → все JPQL/criteria-чтения скоупятся по рынку.

- [ ] **Step 1: Написать интеграционный тест изоляции чтения**

Create `src/test/java/com/vladoose/nir/market/MarketScopingTest.java`:
```java
package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.repository.FacilityRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MarketScopingTest {

    @Autowired FacilityRepository facilityRepository;
    @Autowired EntityManager entityManager;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private Facility facility(String name, Market m) {
        return Facility.builder().name(name).market(m).build();
    }

    private void enableFilter(Market m) {
        entityManager.unwrap(Session.class)
                .enableFilter("marketFilter").setParameter("market", m.name());
    }

    @Test
    void readsAreScopedByMarket() {
        facilityRepository.save(facility("ZZMK-RF учреждение", Market.RF));
        facilityRepository.save(facility("ZZMK-KZ учреждение", Market.KZ));
        facilityRepository.flush();
        entityManager.clear();

        enableFilter(Market.KZ);
        var kz = facilityRepository.findAll().stream()
                .filter(f -> f.getName().startsWith("ZZMK-")).toList();
        assertThat(kz).extracting(Facility::getName).containsExactly("ZZMK-KZ учреждение");

        entityManager.unwrap(Session.class).disableFilter("marketFilter");
        entityManager.clear();
        enableFilter(Market.RF);
        var rf = facilityRepository.findAll().stream()
                .filter(f -> f.getName().startsWith("ZZMK-")).toList();
        assertThat(rf).extracting(Facility::getName).containsExactly("ZZMK-RF учреждение");
    }
}
```
(Тест проверяет сам механизм Hibernate-фильтра напрямую — это сердце скоупинга.)

- [ ] **Step 2: Запустить — убедиться, что компилируется и проходит механика фильтра**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.market.MarketScopingTest"`
Expected: PASS — Hibernate-фильтр уже объявлен (Task 2), тест проверяет его работу. (Если FAIL — значит `@Filter`/`@FilterDef` из Task 2 некорректны; чинить там.)

- [ ] **Step 3: Создать `MarketInterceptor`**

Create `src/main/java/com/vladoose/nir/config/MarketInterceptor.java`:
```java
package com.vladoose.nir.config;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Читает X-Market, кладёт активный рынок в MarketContext и включает Hibernate-фильтр
 * marketFilter на привязанной OSIV-сессии (preHandle выполняется ПОСЛЕ привязки EntityManager).
 */
@Component
public class MarketInterceptor implements HandlerInterceptor {

    private final EntityManager entityManager;

    public MarketInterceptor(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Market market = Market.fromHeader(request.getHeader("X-Market"));
        MarketContext.set(market);
        entityManager.unwrap(Session.class)
                .enableFilter("marketFilter").setParameter("market", market.name());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        MarketContext.clear();
    }
}
```
> Примечание: `entityManager` здесь — общий прокси (`@PersistenceContext`-семантика через конструктор инжекта Spring), на OSIV-запросе резолвится в привязанную к потоку сессию, поэтому `enableFilter` применяется к сессии текущего запроса.

- [ ] **Step 4: Зарегистрировать интерсептор**

Create `src/main/java/com/vladoose/nir/config/WebMvcMarketConfig.java`:
```java
package com.vladoose.nir.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcMarketConfig implements WebMvcConfigurer {

    private final MarketInterceptor marketInterceptor;

    public WebMvcMarketConfig(MarketInterceptor marketInterceptor) {
        this.marketInterceptor = marketInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(marketInterceptor).addPathPatterns("/api/**");
    }
}
```

- [ ] **Step 5: Скомпилировать всё, прогнать suite — нет новых падений**

Run (sandbox off): `./gradlew test`
Expected: компиляция ок; падают ТОЛЬКО 2 известных `ApplyAutoFillServiceTest`; `MarketScopingTest` и всё остальное зелёное.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/config/MarketInterceptor.java src/main/java/com/vladoose/nir/config/WebMvcMarketConfig.java src/test/java/com/vladoose/nir/market/MarketScopingTest.java
git commit -m "$(cat <<'EOF'
feat(market): скоупинг чтения — интерсептор включает Hibernate-фильтр из X-Market

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Штамповка рынка при создании (TDD)

**Files:**
- Modify services: `TenderService.java`, `FacilityService.java`, `DistributorService.java`, `MedEquipmentService.java`, `ActivityApplyService.java`, `PriceRequestService.java`
- Test: `src/test/java/com/vladoose/nir/market/MarketStampingTest.java`

**Interfaces:**
- Consumes: `MarketContext.get()` (Task 2).
- Produces: при создании сущности через сервис `market` берётся из `MarketContext` (если у entity ещё дефолт RF и контекст KZ → ставится KZ). Существующая сигнатура `save(entity)` сохраняется.

- [ ] **Step 1: Написать тест штамповки**

Create `src/test/java/com/vladoose/nir/market/MarketStampingTest.java`:
```java
package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.FacilityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MarketStampingTest {

    @Autowired FacilityService facilityService;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void create_stampsActiveMarket_KZ() {
        MarketContext.set(Market.KZ);
        Facility saved = facilityService.save(
                Facility.builder().name("ZZSTAMP-KZ учреждение").build());
        assertThat(saved.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void create_defaultsToRF_whenNoContext() {
        MarketContext.clear();
        Facility saved = facilityService.save(
                Facility.builder().name("ZZSTAMP-RF учреждение").build());
        assertThat(saved.getMarket()).isEqualTo(Market.RF);
    }
}
```

- [ ] **Step 2: Запустить — падает**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.market.MarketStampingTest"`
Expected: FAIL — `create_stampsActiveMarket_KZ` падает (сервис ещё не штампует, остаётся дефолт RF).

- [ ] **Step 3: Штамповать рынок в 6 сервисах**

В каждом из 6 сервисов в методе `save(...)` ПЕРЕД `repository.save(...)` добавить штамповку из контекста. Пример для `FacilityService.save`:
```java
    @Transactional
    public Facility save(Facility facility) {
        facility.setMarket(com.vladoose.nir.context.MarketContext.get());
        return repository.save(facility);
    }
```
Аналогично в `TenderService.save` (`tender.setMarket(...)`), `DistributorService.save`, `MedEquipmentService.save`, `ActivityApplyService.save`, `PriceRequestService.save`. (Импортировать `MarketContext` или использовать полное имя, как выше.)
> Для `TenderService.save` дополнительно: если `tender.getCurrency()` пуст/равен дефолту — выставить из рынка: `tender.setCurrency(MarketContext.get().currencyCode());` (валюта тендера по рынку).

- [ ] **Step 4: Запустить — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.market.MarketStampingTest"`
Expected: PASS (оба теста).

- [ ] **Step 5: Полный suite — нет новых падений**

Run (sandbox off): `./gradlew test`
Expected: только 2 известных падения `ApplyAutoFillServiceTest`, остальное зелёное.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/TenderService.java src/main/java/com/vladoose/nir/service/FacilityService.java src/main/java/com/vladoose/nir/service/DistributorService.java src/main/java/com/vladoose/nir/service/MedEquipmentService.java src/main/java/com/vladoose/nir/service/ActivityApplyService.java src/main/java/com/vladoose/nir/service/PriceRequestService.java src/test/java/com/vladoose/nir/market/MarketStampingTest.java
git commit -m "$(cat <<'EOF'
feat(market): штамповка активного рынка при создании сущностей + валюта тендера по рынку

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Эндпоинт активного рынка + KZ демо-данные

**Files:**
- Create: `src/main/java/com/vladoose/nir/controller/MarketController.java`
- Modify: `src/main/resources/data.sql`

**Interfaces:**
- Produces: `GET /api/market/current` → `{ market, currencyCode, currencySymbol, companyShortName }` (рынок из активного контекста — фронт может верифицировать); KZ-демо-данные в `data.sql` (market='KZ').

- [ ] **Step 1: Создать `MarketController`**

Create `src/main/java/com/vladoose/nir/controller/MarketController.java`:
```java
package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    @GetMapping("/current")
    public Map<String, String> current() {
        Market m = MarketContext.get();
        return Map.of(
                "market", m.name(),
                "currencyCode", m.currencyCode(),
                "currencySymbol", m.currencySymbol(),
                "companyShortName", m.companyShortName());
    }
}
```

- [ ] **Step 2: Добавить KZ демо-данные в `data.sql`**

В конец `src/main/resources/data.sql` добавить блок West-Med/KZ (все вставки с `market='KZ'`; `equipment_type` общий — переиспользуем существующие типы по имени). Конкретно:
```sql
-- ========== West-Med (KZ) демо-данные ==========
INSERT INTO facility (name, inn, address, market) VALUES
  ('ГКП «Городская больница №1 г. Алматы»', NULL, 'Казахстан, г. Алматы, ул. Толе би, 100', 'KZ'),
  ('КГП «Областной перинатальный центр г. Шымкент»', NULL, 'Казахстан, г. Шымкент, пр. Тауке хана, 5', 'KZ');

INSERT INTO distributor (name, inn, address, market) VALUES
  ('ТОО «МедСнаб Казахстан»', NULL, 'г. Алматы, ул. Сатпаева, 30', 'KZ'),
  ('ТОО «Алматы Медтехника»', NULL, 'г. Алматы, пр. Абая, 150', 'KZ');

INSERT INTO med_equipment (name, manufact, equip_type_id, length_mm, width_mm, height_mm, weight_kg, spec, market) VALUES
  ('УЗИ-аппарат Mindray DC-70 (KZ)', 'Mindray', (SELECT id FROM equipment_type WHERE name='УЗИ'), 575, 750, 1390, 95.0, 'Поставка West-Med', 'KZ'),
  ('Аппарат ИВЛ Hamilton C6 (KZ)', 'Hamilton Medical', (SELECT id FROM equipment_type WHERE name='ИВЛ'), 420, 430, 1400, 42.0, 'Поставка West-Med', 'KZ');

INSERT INTO tender (tender_number, facility_id, status, deadline, total_cost, currency, description, market) VALUES
  ('KZ-2026-0001', (SELECT id FROM facility WHERE name='ГКП «Городская больница №1 г. Алматы»'), 'NEW',
   '2026-08-15', 45000000.00, 'KZT', 'Закуп УЗИ-аппарата для горбольницы №1 (Алматы)', 'KZ');
```
(Если у `facility`/`distributor` есть NOT NULL поля помимо `name` — заполнить минимально; ориентироваться на существующие INSERT в файле. ИНН РК-структур можно NULL.)

- [ ] **Step 3: Проверить старт + KZ-данные загрузились**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.Nir2ApplicationTests"` затем
```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -h localhost -U postgres -d nirdb -c "SELECT market, count(*) FROM facility GROUP BY market; SELECT market, count(*) FROM tender GROUP BY market;"
```
Expected: появились строки `KZ` в facility/distributor/med_equipment/tender; `RF` — прежние.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/vladoose/nir/controller/MarketController.java src/main/resources/data.sql
git commit -m "$(cat <<'EOF'
feat(market): эндпоинт /api/market/current + KZ демо-данные West-Med

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Фронт — `MarketService` + `marketInterceptor` (заголовок X-Market)

**Files:**
- Create: `frontend/src/app/services/market.service.ts`
- Create: `frontend/src/app/interceptors/market.interceptor.ts`
- Modify: `frontend/src/app/app.config.ts`

**Interfaces:**
- Produces: `MarketService` (активный рынок, signal + localStorage, `symbol()`, `companyLabel()`, `setMarket()`, `market$`); `marketInterceptor` добавляет `X-Market` ко всем `/api`-запросам.

- [ ] **Step 1: Создать `MarketService`**

Create `frontend/src/app/services/market.service.ts`:
```typescript
import { Injectable, signal } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export type Market = 'RF' | 'KZ';

interface MarketMeta { code: Market; symbol: string; companyLabel: string; logo: string; }

const MARKETS: Record<Market, MarketMeta> = {
  RF: { code: 'RF', symbol: '₽', companyLabel: 'АИС Регион-Мед', logo: 'РМ' },
  KZ: { code: 'KZ', symbol: '₸', companyLabel: 'АИС West-Med',  logo: 'WM' },
};

@Injectable({ providedIn: 'root' })
export class MarketService {
  private readonly KEY = 'ais.market';
  private current: Market = this.read();
  private subject = new BehaviorSubject<Market>(this.current);

  market = signal<Market>(this.current);
  market$ = this.subject.asObservable();

  private read(): Market {
    const v = localStorage.getItem(this.KEY);
    return v === 'KZ' ? 'KZ' : 'RF';
  }

  get value(): Market { return this.current; }
  symbol(): string { return MARKETS[this.current].symbol; }
  companyLabel(): string { return MARKETS[this.current].companyLabel; }
  logo(): string { return MARKETS[this.current].logo; }
  meta(m: Market): MarketMeta { return MARKETS[m]; }

  setMarket(m: Market) {
    if (m === this.current) return;
    this.current = m;
    localStorage.setItem(this.KEY, m);
    this.market.set(m);
    this.subject.next(m);
  }
}
```

- [ ] **Step 2: Создать `marketInterceptor`**

Create `frontend/src/app/interceptors/market.interceptor.ts`:
```typescript
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MarketService } from '../services/market.service';

export const marketInterceptor: HttpInterceptorFn = (req, next) => {
  const market = inject(MarketService);
  const cloned = req.clone({ setHeaders: { 'X-Market': market.value } });
  return next(cloned);
};
```

- [ ] **Step 3: Зарегистрировать интерсептор**

В `frontend/src/app/app.config.ts`:
1. Импорт: `import { marketInterceptor } from './interceptors/market.interceptor';`
2. Изменить `withInterceptors([authInterceptor])` → `withInterceptors([authInterceptor, marketInterceptor])`.

- [ ] **Step 4: Сборка**

Run: `cd frontend && npm run build` (при sandbox-ошибке — повторить с `dangerouslyDisableSandbox: true`).
Expected: компиляция без ошибок.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/market.service.ts frontend/src/app/interceptors/market.interceptor.ts frontend/src/app/app.config.ts
git commit -m "$(cat <<'EOF'
feat(market): фронт — MarketService + marketInterceptor (заголовок X-Market)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Фронт — селектор рынка в шапке + идентичность + перезагрузка

**Files:**
- Modify: `frontend/src/app/layout/layout.component.ts`

**Interfaces:**
- Consumes: `MarketService` (Task 6).
- Produces: селектор «Регион-Мед (РФ) ₽ ↔ West-Med (KZ) ₸» в шапке; логотип/заголовок по рынку; смена рынка → перезагрузка данных.

- [ ] **Step 1: Внедрить `MarketService` и сделать шапку market-aware**

В `frontend/src/app/layout/layout.component.ts`:
1. Импорт `import { MarketService, Market } from '../services/market.service';` и инжект в конструктор: `public market: MarketService` (плюс уже есть `Router`).
2. В `.header-left` заменить статичные «РМ»/«АИС Регион-Мед» на привязки:
```html
  <div class="header-left">
    <span class="logo">{{ market.logo() }}</span>
    <span class="header-title">{{ market.companyLabel() }}</span>
    <select class="market-select" [value]="market.value" (change)="onMarketChange($event)">
      <option value="RF">Регион-Мед (РФ) ₽</option>
      <option value="KZ">West-Med (KZ) ₸</option>
    </select>
  </div>
```
3. Метод смены рынка (перезагрузка приложения, чтобы все страницы перечитали данные нового рынка):
```typescript
  onMarketChange(e: Event) {
    const m = (e.target as HTMLSelectElement).value as Market;
    this.market.setMarket(m);
    // полный сброс данных текущего рынка: перезагрузка на дашборд
    this.router.navigateByUrl('/dashboard').then(() => location.reload());
  }
```
4. Стиль `.market-select` (в `styles: []`):
```scss
    .market-select { margin-left: 10px; background: rgba(255,255,255,0.2); color: #fff; border: 1px solid rgba(255,255,255,0.35); border-radius: 6px; padding: 4px 8px; font-size: 12px; font-weight: 600; cursor: pointer; }
    .market-select option { color: #111827; }
```

- [ ] **Step 2: Сборка**

Run: `cd frontend && npm run build` (sandbox off при необходимости).
Expected: компиляция без ошибок.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/layout/layout.component.ts
git commit -m "$(cat <<'EOF'
feat(market): селектор рынка в шапке + идентичность компании + перезагрузка

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Фронт — валюта по рынку (пайп) + замена захардкоженных ₽

**Files:**
- Create: `frontend/src/app/pipes/market-money.pipe.ts`
- Modify (замена символа ₽/&#8381; на пайп или `market.symbol()`): `pages/dashboard/dashboard.component.ts`, `pages/tenders/tenders.component.ts`, `pages/applies/applies.component.ts`, `pages/reports/reports.component.ts`, `pages/tender-search/tender-search.component.ts`, `pages/equipment/equipment.component.ts`, `components/equipment-detail-modal/equipment-detail-modal.component.ts`, `components/smart-match/smart-match.component.ts`, `pages/tenders/bulk-price-modal.component.ts`

**Interfaces:**
- Consumes: `MarketService` (Task 6).
- Produces: пайп `money` форматирует число + символ активного рынка; все ценовые подписи показывают ₽/₸ по рынку.

- [ ] **Step 1: Создать standalone-пайп `MarketMoneyPipe`**

Create `frontend/src/app/pipes/market-money.pipe.ts`:
```typescript
import { Pipe, PipeTransform } from '@angular/core';
import { MarketService } from '../services/market.service';

/** Форматирует число как сумму в валюте активного рынка: 1234567 -> "1 234 567 ₸". */
@Pipe({ name: 'money', standalone: true, pure: false })
export class MarketMoneyPipe implements PipeTransform {
  constructor(private market: MarketService) {}
  transform(value: number | null | undefined, digits: number = 0): string {
    if (value == null) return '—';
    const n = Number(value).toLocaleString('ru-RU', { minimumFractionDigits: digits, maximumFractionDigits: digits });
    return `${n} ${this.market.symbol()}`;
  }
}
```
(`pure: false` — пересчитывается при смене рынка без полной перезагрузки тоже; страховка.)

- [ ] **Step 2: Заменить захардкоженные символы валюты**

В каждом компоненте из списка Files: добавить `MarketMoneyPipe` в `imports: [...]` декоратора и заменить ценовые выражения. Паттерн замены:
- `{{ formatPrice(x) }} &#8381;` → `{{ x | money }}`
- `{{ formatPrice(x) }} ₽` → `{{ x | money }}`
- `formatPrice(x) + ' ₽'` (в TS-строках) → оставить число, символ через пайп в шаблоне; где символ в TS неизбежен — заменить литерал `' ₽'` на ` ' ' + this.market.symbol()` (инжектнув `MarketService`).
- Текстовые подписи `Цена ответа (₽)`, `Прибыль, ₽` → `Цена ответа ({{ market.symbol() }})` / динамическая подпись (инжект `MarketService` как `public market`).

Найти ВСЕ места самостоятельно грепом по перечисленным файлам и пройти по каждому:
```bash
grep -rn "&#8381;\|₽\|(₽)\|, ₽" frontend/src/app/pages frontend/src/app/components
```
Цель: после смены рынка все суммы показывают символ активного рынка. Известные файлы с символом валюты: `dashboard`, `tenders`, `applies`, `reports`, `tender-search`, `equipment` (формат-метод), `equipment-detail-modal`, `smart-match`, `bulk-price-modal` (≈40-50 вхождений: микс `&#8381;`, `₽`, текстовых подписей «(₽)»/«, ₽»).
> Если символ только в шаблоне — достаточно пайпа `| money`. Если в TS-форматтере или текстовой подписи — инжектировать `MarketService` как `public market` и брать `market.symbol()`.

- [ ] **Step 3: Сборка**

Run: `cd frontend && npm run build` (sandbox off при необходимости).
Expected: компиляция без ошибок. Греп контроль:
```bash
grep -rn "&#8381;\|formatPrice(.*) ₽\|') ₽'\|, ₽\|(₽)" frontend/src/app/pages frontend/src/app/components | grep -v money | head
```
Ожидаемо: пусто или только осознанно оставленные места.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/pipes/market-money.pipe.ts frontend/src/app/pages frontend/src/app/components
git commit -m "$(cat <<'EOF'
feat(market): валюта по рынку — пайп money, замена захардкоженных ₽ на символ рынка

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Backend — `CompanyInfo` market-aware (реквизиты компании по рынку)

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/CompanyInfoProvider.java`
- Modify: `src/main/java/com/vladoose/nir/service/PdfCompanyHeader.java`, `src/main/java/com/vladoose/nir/service/ExcelCompanyHeader.java`

**Interfaces:**
- Consumes: `MarketContext` (Task 2), существующий `CompanyInfo` (RF-реквизиты).
- Produces: `CompanyInfoProvider.current()` → реквизиты компании активного рынка (RF — существующий Регион-Мед; KZ — West-Med плейсхолдер). Генераторы PDF/Excel используют активные реквизиты.

- [ ] **Step 1: Создать `CompanyInfoProvider`**

Create `src/main/java/com/vladoose/nir/service/CompanyInfoProvider.java`. Возвращает по активному рынку набор полей-реквизитов (для KZ — плейсхолдер West-Med, т.к. реальных реквизитов пока нет):
```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import org.springframework.stereotype.Component;

/** Отдаёт реквизиты компании активного рынка для шапок PDF/Excel. */
@Component
public class CompanyInfoProvider {

    public record Company(String shortName, String fullName, String addressLine1, String addressLine2) {}

    public Company current() {
        if (MarketContext.get() == Market.KZ) {
            return new Company(
                    "ТОО «West-Med»",
                    "Товарищество с ограниченной ответственностью «West-Med»",
                    "Республика Казахстан, г. Алматы,",
                    "ул. (реквизиты уточняются)");
        }
        return new Company(
                CompanyInfo.SHORT_NAME, CompanyInfo.FULL_NAME,
                CompanyInfo.ADDRESS_LINE_1, CompanyInfo.ADDRESS_LINE_2);
    }
}
```

- [ ] **Step 2: Использовать провайдер в генераторах шапок**

В `PdfCompanyHeader.java` и `ExcelCompanyHeader.java` — там, где сейчас берутся статичные `CompanyInfo.SHORT_NAME`/`FULL_NAME`/`ADDRESS_LINE_*`, заменить на значения из `CompanyInfoProvider.current()` (инжектировать `CompanyInfoProvider`, если классы — Spring-бины; если статические утилиты — передавать `Company` параметром в метод-генератор от вызывающего сервиса, у которого есть бин-провайдер). Минимально: заменить отображаемые название/адрес компании на market-aware; банковские/ИНН-поля для KZ оставить плейсхолдером или скрыть.
> Не переусложнять: цель — чтобы при активном KZ в шапке стояло «West-Med», при RF — «Регион-Мед». Полные реквизиты West-Med — вне скоупа (плейсхолдер).

- [ ] **Step 3: Полный suite + сборка бэка**

Run (sandbox off): `./gradlew test`
Expected: компиляция ок; только 2 известных падения, ничего нового.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/CompanyInfoProvider.java src/main/java/com/vladoose/nir/service/PdfCompanyHeader.java src/main/java/com/vladoose/nir/service/ExcelCompanyHeader.java
git commit -m "$(cat <<'EOF'
feat(market): CompanyInfo market-aware — реквизиты компании по активному рынку

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Финальная проверка (после всех задач)

- [ ] `./gradlew test` (sandbox off) — компилируется, падают только 2 известных `ApplyAutoFillServiceTest`, все market/registry-тесты зелёные.
- [ ] `cd frontend && npm run build` — фронт собирается.
- [ ] **E2E-смоук (приложение + браузер):** старт → по умолчанию рынок RF (самарские тендеры, ₽, шапка «Регион-Мед») → переключить в шапке на West-Med (KZ) → видны ТОЛЬКО KZ-демо-данные (KZ-тендер, KZ-каталог), суммы в ₸, шапка «West-Med», логотип «WM» → вернуть RF → снова RF-данные. Реестр-сверка: Mindray теперь показывает кандидата (word_similarity).
