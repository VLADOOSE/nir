# Goszakup import hardening (token-free) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Закрыть 2 Important из финального whole-branch ревью + `system_id` фильтр — всё валидируется на фейк-клиенте без живого токена, чтобы T9 свёлся к чистому live-связыванию.

**Architecture:** Вынести сетевой I/O из одной большой `@Transactional`: оркестратор (`GoszakupImportService`, НЕ транзакционный) пагинирует + фильтрует + тянет subject/lots по сети, а запись одного тендера делает `GoszakupTenderWriter` (отдельный бин, per-item `@Transactional`). Ошибка элемента ловится и идёт в `ImportSummary.errors`, не валя прогон. `fetchSubject` различает 404 (нет данных → null) и сбой (пробрасывается → считается ошибкой). Плюс фильтр `system_id=3` (только текущий модуль госзакупа).

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA/Hibernate 6, Jackson, Angular 21.

## Global Constraints

- БД/`./gradlew` — Bash `dangerouslyDisableSandbox: true`. Перед тестами `lsof -ti :8080 | xargs kill -9`.
- Гейт «зелёного»: только пред-существующие `ApplyAutoFillServiceTest` (2). Фронт-гейт: `cd frontend && npm run build`.
- Многорыночность: импортные тендеры стампятся `market=KZ` явно; `findBySourceExtId` market-scoped (нужен `MarketContext=KZ` + привязанная сессия). `@FilterDef` только на `Tender` — не трогать.
- orphanRemoval: лоты через `tender.getLots()` (clear/add), не `repository.delete`.
- Коммиты заканчивать `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Коммитить ТОЛЬКО файлы задачи (в дереве есть посторонний незакоммиченный WIP — никогда `git add -A`).
- Ветка: `feat/goszakup-hardening` (уже создана от main).

---

### Task 1: Per-item устойчивость + `ImportSummary.errors` + `fetchSubject` 404-narrow

**Files:**
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/ImportSummary.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupParse.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupTenderWriter.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupNotFoundException.java`
- Modify (rewrite): `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java`
- Modify: `src/test/java/com/vladoose/nir/integration/goszakup/FakeGoszakupClient.java`
- Modify: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java`

**Interfaces:**
- Produces: `GoszakupTenderWriter` (`@Component`) `Result upsertOne(TrdBuyDto, SubjectDto, List<LotDto>)` with `enum Result { CREATED, UPDATED }`, ctor `(TenderRepository, RegionResolver)`. `GoszakupImportService` new ctor `(GoszakupClient, GoszakupTenderWriter, @Value keywords, @Value statuses, @Value sinceDays, @Value maxPages)` — **regionResolver и tenderRepository больше НЕ зависимости сервиса** (переехали в writer). `ImportSummary` + `int errors`. `GoszakupNotFoundException extends RuntimeException`.

- [ ] **Step 1: Failing test** — обновить `GoszakupImportServiceTest.java` под новый конструктор (через writer) и добавить тест ошибок. Полностью замени файл на:

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
    GoszakupTenderWriter writer;
    GoszakupImportService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGoszakupClient();
        writer = new GoszakupTenderWriter(tenderRepository, regionResolver);
        service = new GoszakupImportService(fake, writer, "аппарат,узи", "", 3650, 20);
    }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private GoszakupImportService svc(String keywords, String statuses, int sinceDays) {
        return new GoszakupImportService(fake, writer, keywords, statuses, sinceDays, 20);
    }

    @Test
    void filtersByKeyword_andCreatesKzPublicTender() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ экспертного класса", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("200-1", "Канцелярские товары для офиса", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));

        ImportSummary s = service.importMedicalTenders();

        assertThat(s.getFetched()).isEqualTo(2);
        assertThat(s.getMatched()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(s.getErrors()).isEqualTo(0);

        Optional<Tender> t = tenderRepository.findBySourceExtId("100-1");
        assertThat(t).isPresent();
        assertThat(t.get().getMarket()).isEqualTo(Market.KZ);
        assertThat(t.get().getSource()).isEqualTo(Source.PUBLIC_TENDER);
        assertThat(t.get().getCurrency()).isEqualTo("KZT");
        assertThat(t.get().getFacility()).isNull();
        assertThat(t.get().getDescription()).isEqualTo("Аппарат УЗИ экспертного класса");
        assertThat(tenderRepository.findBySourceExtId("200-1")).isEmpty();
    }

    @Test
    void importedTenderNotVisibleOnRf() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат ИВЛ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        svc("аппарат", "", 3650).importMedicalTenders();

        MarketContext.set(Market.RF);
        assertThat(tenderRepository.findBySourceExtId("100-1")).isEmpty();
    }

    @Test
    void idempotent_secondRunUpdatesNotDuplicates() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        com.vladoose.nir.integration.goszakup.dto.LotDto lot = new com.vladoose.nir.integration.goszakup.dto.LotDto();
        lot.setLotNumber("1"); lot.setNameRu("Аппарат УЗИ портативный");
        lot.setAmount(new java.math.BigDecimal("6000000")); lot.setCount(2);
        fake.lotsByAnno.put("100-1", java.util.List.of(lot));

        GoszakupImportService s1 = svc("аппарат", "", 3650);
        assertThat(s1.importMedicalTenders().getCreated()).isEqualTo(1);

        fake.pages.get(null).getItems().get(0).setTotalSum(new java.math.BigDecimal("9999999"));
        ImportSummary second = svc("аппарат", "", 3650).importMedicalTenders();
        assertThat(second.getCreated()).isEqualTo(0);
        assertThat(second.getUpdated()).isEqualTo(1);

        var t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(tenderRepository.findAll().stream()
                .filter(x -> "100-1".equals(x.getSourceExtId())).count()).isEqualTo(1);
        assertThat(t.getTotalCost()).isEqualByComparingTo("9999999");
        assertThat(t.getLots()).hasSize(1);
        assertThat(t.getLots().get(0).getEquipName()).isEqualTo("Аппарат УЗИ портативный");
        assertThat(t.getLots().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void lenientStatusesParse_skipsNonNumericTokenAndStillFiltersByValidId() {
        fake.page(null, null,
                FakeGoszakupClient.buy("OK-230", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("NO-999", "Аппарат УЗИ", 999, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));

        ImportSummary s = svc("аппарат", "230,foo", 3650).importMedicalTenders();

        assertThat(s.getMatched()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("OK-230")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("NO-999")).isEmpty();
    }

    @Test
    void resolvesRegionFromSubject() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        com.vladoose.nir.integration.goszakup.dto.SubjectDto subj = new com.vladoose.nir.integration.goszakup.dto.SubjectDto();
        subj.setBin("BIN1"); subj.setNameRu("Городская поликлиника №5 г. Алматы");
        fake.subjectsByBin.put("BIN1", subj);

        svc("аппарат", "", 3650).importMedicalTenders();

        var t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(t.getRegion()).isEqualTo("г. Алматы");
        assertThat(t.getCustomerName()).isEqualTo("Городская поликлиника №5 г. Алматы");
    }

    @Test
    void paginatesAcrossPages_andSkipsOldBySinceDays() {
        String recentIso = java.time.LocalDate.now().minusDays(5) + "T00:00:00";
        String oldIso = java.time.LocalDate.now().minusDays(400) + "T00:00:00";
        fake.page(null, "/v2/trd-buy?page=next&search_after=1",
                FakeGoszakupClient.buy("NEW-1", "Аппарат УЗИ", 230, "BIN1", recentIso, recentIso));
        fake.page("/v2/trd-buy?page=next&search_after=1", null,
                FakeGoszakupClient.buy("OLD-1", "Аппарат УЗИ", 230, "BIN2", oldIso, oldIso));

        ImportSummary s = svc("аппарат", "", 30).importMedicalTenders();

        assertThat(s.getFetched()).isEqualTo(2);
        assertThat(tenderRepository.findBySourceExtId("NEW-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("OLD-1")).isEmpty();
    }

    @Test
    void itemError_isCountedAndDoesNotAbortRun() {
        // ERR-1: fetchSubject бросает (имитация 500/таймаута) → объявление уходит в errors,
        // OK-1 импортируется штатно (раньше один сбой ронял весь @Transactional-прогон).
        fake.page(null, null,
                FakeGoszakupClient.buy("OK-1", "Аппарат УЗИ", 230, "BINOK", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("ERR-1", "Аппарат УЗИ", 230, "BINERR", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.failingSubjectBins.add("BINERR");

        ImportSummary s = svc("аппарат", "", 3650).importMedicalTenders();

        assertThat(s.getMatched()).isEqualTo(2);
        assertThat(s.getErrors()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("OK-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("ERR-1")).isEmpty();
    }
}
```

- [ ] **Step 2: Run, verify FAIL** — `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest" -q` (sandbox off, после kill :8080). Ожидание: компиляция падает (`GoszakupTenderWriter` нет, конструктор сервиса не тот, `getErrors`/`failingSubjectBins` нет).

- [ ] **Step 3: `ImportSummary.java`** — добавить поле (после `skipped`):
```java
    private int errors;
```

- [ ] **Step 4: `GoszakupParse.java`** (новый):
```java
package com.vladoose.nir.integration.goszakup;

import java.time.LocalDate;

/** Null-безопасный разбор полей goszakup. */
final class GoszakupParse {
    private GoszakupParse() {}

    static LocalDate localDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try { return LocalDate.parse(iso.substring(0, 10)); }
        catch (Exception e) { return null; }
    }

    static Integer intOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.valueOf(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
```

- [ ] **Step 5: `GoszakupNotFoundException.java`** (новый):
```java
package com.vladoose.nir.integration.goszakup;

/** Ресурс не найден (HTTP 404) — отличаем «нет данных» от сбоя API. */
class GoszakupNotFoundException extends RuntimeException {
    GoszakupNotFoundException(String message) { super(message); }
}
```

- [ ] **Step 6: `GoszakupTenderWriter.java`** (новый):
```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Пишет ОДИН импортный тендер в собственной транзакции (отдельный бин → Spring-прокси
 * даёт привязанную сессию для marketFilter и per-item коммит). subject/lots уже получены
 * ВНЕ транзакции и переданы сюда — транзакция короткая, без блокирующего сетевого I/O.
 */
@Component
public class GoszakupTenderWriter {

    public enum Result { CREATED, UPDATED }

    private final TenderRepository tenderRepository;
    private final RegionResolver regionResolver;

    public GoszakupTenderWriter(TenderRepository tenderRepository, RegionResolver regionResolver) {
        this.tenderRepository = tenderRepository;
        this.regionResolver = regionResolver;
    }

    @Transactional
    public Result upsertOne(TrdBuyDto d, SubjectDto subj, List<LotDto> lots) {
        Tender t = tenderRepository.findBySourceExtId(d.getNumberAnno()).orElse(null);
        boolean isNew = (t == null);
        if (isNew) { t = new Tender(); t.setSourceExtId(d.getNumberAnno()); }
        applyFields(t, d);
        applyRegion(t, subj);
        rebuildLots(t, lots);
        tenderRepository.save(t);
        return isNew ? Result.CREATED : Result.UPDATED;
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
        t.setPublishDate(GoszakupParse.localDate(d.getPublishDate()));
        t.setDeadline(GoszakupParse.localDate(d.getEndDate()));
        t.setCustomerBin(d.getCustomerBin());
    }

    private void applyRegion(Tender t, SubjectDto subj) {
        String customerName = subj != null ? subj.getNameRu() : null;
        String address = subj != null ? subj.getAddress() : null;
        String kato = subj != null ? subj.getKatoId() : null;
        t.setCustomerName(customerName);
        t.setRegionKato(kato);
        if (address != null) t.setDeliveryAddress(address);
        t.setRegion(regionResolver.resolve(customerName, address)); // null допустим
    }

    private void rebuildLots(Tender t, List<LotDto> lots) {
        // §7/§14: лоты ТОЛЬКО через коллекцию (orphanRemoval), не repository.delete
        t.getLots().clear();
        if (lots == null) return;
        for (LotDto l : lots) {
            TenderLot lot = new TenderLot();
            lot.setTender(t);
            lot.setLotNumber(GoszakupParse.intOrNull(l.getLotNumber()));
            lot.setEquipName(l.getNameRu());
            lot.setQuantity(l.getCount());
            lot.setMaxCost(l.getAmount());
            t.getLots().add(lot);
        }
    }

    static String mapStatus(Integer refBuyStatusId) {
        // TODO(T9): map real ref_buy_status_id → domain status (ids confirmed against live API token)
        return "ACTIVE";
    }
}
```

- [ ] **Step 7: `GoszakupImportService.java`** — заменить файл целиком на (НЕ `@Transactional`; сеть вне транзакции; per-item try/catch → errors):
```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Оркеструет импорт KZ-тендеров: пагинация + фильтры + получение subject/lots по сети,
 * запись каждого тендера — через GoszakupTenderWriter (per-item @Transactional).
 * Сам НЕ транзакционный: блокирующий сетевой I/O не держит БД-коннект, а ошибка одного
 * объявления идёт в ImportSummary.errors и не валит весь прогон.
 */
@Service
public class GoszakupImportService {

    private static final Logger log = LoggerFactory.getLogger(GoszakupImportService.class);

    private final GoszakupClient client;
    private final GoszakupTenderWriter writer;
    private final List<String> keywords;
    private final Set<Integer> statuses;
    private final int sinceDays;
    private final int maxPages;

    public GoszakupImportService(GoszakupClient client,
                                 GoszakupTenderWriter writer,
                                 @Value("${goszakup.import.keywords:}") String keywordsCsv,
                                 @Value("${goszakup.import.statuses:}") String statusesCsv,
                                 @Value("${goszakup.import.since-days:30}") int sinceDays,
                                 @Value("${goszakup.import.max-pages:20}") int maxPages) {
        this.client = client;
        this.writer = writer;
        this.keywords = csv(keywordsCsv).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.statuses = parseStatuses(statusesCsv);
        this.sinceDays = sinceDays;
        this.maxPages = maxPages;
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isBlank()).toList();
    }

    /** Лояльный разбор статусов: нечисловые токены пропускаем, чтобы кривой конфиг не ронял старт. */
    private static Set<Integer> parseStatuses(String s) {
        Set<Integer> ids = new HashSet<>();
        for (String token : csv(s)) {
            try { ids.add(Integer.valueOf(token)); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return ids;
    }

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
                LocalDate pub = GoszakupParse.localDate(d.getPublishDate());
                if (pub != null && pub.isBefore(cutoff)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
                if (!statusOk(d) || !keywordOk(d)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
                sum.setMatched(sum.getMatched() + 1);
                importOne(d, sum);
            }
            cursor = page.getNextPage();
            pagesRead++;
        } while (cursor != null && !cursor.isBlank() && pagesRead < maxPages);

        sum.setMessage(String.format("Получено %d, релевантных %d, создано %d, обновлено %d, ошибок %d",
                sum.getFetched(), sum.getMatched(), sum.getCreated(), sum.getUpdated(), sum.getErrors()));
        return sum;
    }

    /** Сеть — ВНЕ транзакции; запись — в отдельной per-item транзакции writer'а. Ошибка элемента не валит прогон. */
    private void importOne(TrdBuyDto d, ImportSummary sum) {
        try {
            SubjectDto subj = client.fetchSubject(d.getCustomerBin());
            List<LotDto> lots = client.fetchLots(d.getNumberAnno());
            GoszakupTenderWriter.Result r = writer.upsertOne(d, subj, lots);
            if (r == GoszakupTenderWriter.Result.CREATED) sum.setCreated(sum.getCreated() + 1);
            else sum.setUpdated(sum.getUpdated() + 1);
        } catch (RuntimeException e) {
            sum.setErrors(sum.getErrors() + 1);
            log.warn("goszakup: ошибка импорта объявления {}: {}", d.getNumberAnno(), e.toString());
        }
    }

    private boolean statusOk(TrdBuyDto d) {
        return statuses.isEmpty() || (d.getRefBuyStatusId() != null && statuses.contains(d.getRefBuyStatusId()));
    }
    private boolean keywordOk(TrdBuyDto d) {
        String name = d.getNameRu() == null ? "" : d.getNameRu().toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(name::contains);
    }
}
```

- [ ] **Step 8: `GoszakupHttpClient.java`** — сузить `fetchSubject` до 404 + кидать `GoszakupNotFoundException` на 404 в `rawGet`.

(8a) В `rawGet`, перед общей проверкой не-2xx, добавить ветку 404:
```java
            if (resp.statusCode() == 404) {
                throw new GoszakupNotFoundException("goszakup 404 на " + url);
            }
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("goszakup API " + resp.statusCode() + " на " + url);
            }
```
(8b) Заменить тело `fetchSubject` (ловим ТОЛЬКО not-found → null; прочие ошибки пробрасываются и будут посчитаны в errors):
```java
    @Override
    public SubjectDto fetchSubject(String bin) {
        if (bin == null || bin.isBlank()) return null;
        try {
            return get(baseUrl + "/subject/" + enc(bin), SubjectDto.class);
        } catch (GoszakupNotFoundException notFound) {
            return null; // организации нет в реестре — регион просто не определится
        }
    }
```
(Логгер `log` в классе уже есть; импорт `SubjectDto` уже есть. Старый `catch (RuntimeException ...) { log.warn ... return null; }` удалить.)

- [ ] **Step 9: `FakeGoszakupClient.java`** — добавить управляемый сбой subject. Добавить поле и заменить `fetchSubject`:
```java
    public final java.util.Set<String> failingSubjectBins = new java.util.HashSet<>();
```
```java
    @Override public SubjectDto fetchSubject(String bin) {
        if (failingSubjectBins.contains(bin)) throw new RuntimeException("fake subject failure: " + bin);
        return subjectsByBin.get(bin);
    }
```

- [ ] **Step 10: Run, verify PASS** — `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest" -q` (sandbox off). Ожидание: 7/7 (6 адаптированных + `itemError_isCountedAndDoesNotAbortRun`).

- [ ] **Step 11: Прогнать соседние goszakup-тесты** (конструктор/сигнатуры не сломали `GoszakupImportSchedulerTest`): `./gradlew test --tests "com.vladoose.nir.integration.goszakup.*" -q`. Ожидание: зелено.

- [ ] **Step 12: Commit**
```bash
git add src/main/java/com/vladoose/nir/integration/goszakup/ImportSummary.java \
        src/main/java/com/vladoose/nir/integration/goszakup/GoszakupParse.java \
        src/main/java/com/vladoose/nir/integration/goszakup/GoszakupTenderWriter.java \
        src/main/java/com/vladoose/nir/integration/goszakup/GoszakupNotFoundException.java \
        src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java \
        src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java \
        src/test/java/com/vladoose/nir/integration/goszakup/FakeGoszakupClient.java \
        src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java
git commit -m "refactor(goszakup): per-item устойчивость + ImportSummary.errors + fetchSubject 404-narrow

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Фильтр `system_id=3` + показ ошибок в нотификации

**Files:**
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/dto/TrdBuyDto.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java`
- Modify: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `GoszakupImportService` (Task 1), `ImportSummary.errors` (Task 1).
- Produces: `TrdBuyDto.systemId` (`Integer`, `@JsonProperty("system_id")`). Сервис отбрасывает объявления с `system_id` ∉ {null, 3}.

- [ ] **Step 1: Failing test** — добавить в `GoszakupImportServiceTest`:
```java
    @Test
    void skipsNonCurrentSystemId() {
        // system_id: 1=ценовые предложения, 2=конкурс/аукцион, 3=текущая версия госзакупа.
        var legacy = FakeGoszakupClient.buy("LEG-2", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00");
        legacy.setSystemId(2);
        var current = FakeGoszakupClient.buy("CUR-3", "Аппарат УЗИ", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00");
        current.setSystemId(3);
        fake.page(null, null, legacy, current);

        svc("аппарат", "", 3650).importMedicalTenders();

        assertThat(tenderRepository.findBySourceExtId("CUR-3")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("LEG-2")).isEmpty(); // system_id=2 отброшен
    }
```

- [ ] **Step 2: Run, verify FAIL** — `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest" -q` (sandbox off). Ожидание: FAIL — `setSystemId` нет / фильтр не отбрасывает.

- [ ] **Step 3: `TrdBuyDto.java`** — добавить поле (после `endDate`):
```java
    @JsonProperty("system_id") private Integer systemId;
```

- [ ] **Step 4: `GoszakupImportService.java`** — добавить метод `systemOk` и включить его в фильтр.

Метод (рядом со `statusOk`):
```java
    /** Только текущий модуль госзакупа (system_id=3); null трактуем как «брать» (поле может отсутствовать). */
    private boolean systemOk(TrdBuyDto d) {
        return d.getSystemId() == null || d.getSystemId() == 3;
    }
```
И в `importMedicalTenders` заменить условие фильтра:
```java
                if (!statusOk(d) || !systemOk(d) || !keywordOk(d)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
```

- [ ] **Step 5: Run, verify PASS** — `./gradlew test --tests "com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest" -q` (sandbox off). Ожидание: 8/8.

- [ ] **Step 6: Фронт — показать ошибки в тосте.** В `frontend/src/app/pages/tenders/tenders.component.ts`, метод `onImportKz`, success-ветка: заменить строку сообщения на учитывающую ошибки:
```typescript
          this.notify.success(`Импорт завершён: создано ${s?.created ?? 0}, обновлено ${s?.updated ?? 0}` + (s?.errors ? `, ошибок ${s.errors}` : ''));
```

- [ ] **Step 7: Фронт-гейт** — `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`. Ожидание: BUILD SUCCESS.

- [ ] **Step 8: Commit**
```bash
git add src/main/java/com/vladoose/nir/integration/goszakup/dto/TrdBuyDto.java \
        src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java \
        src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java \
        frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(goszakup): фильтр system_id=3 + ошибки импорта в нотификации

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-review

- Coverage: Important #1 (tx/network-I/O + errors) → Task 1 (writer + non-tx orchestrator + importOne try/catch + ImportSummary.errors). Important #2 (fetchSubject 404) → Task 1 step 8. system_id=3 → Task 2. errors в нотификации → Task 2 step 6.
- Placeholders: код приведён целиком; `mapStatus` остаётся `TODO(T9)` (real ids — только с токеном, вне скоупа).
- Типы: новый ctor `GoszakupImportService(GoszakupClient, GoszakupTenderWriter, …)` согласован между сервисом и тестом (writer строится в setUp); `Result` enum используется в `importOne`; `failingSubjectBins`/`systemId` в фейке/DTO ↔ тесты.
- T9 НЕ закрывается этим планом: остаётся живой пробой схемы (КАТО/регион поле, активные статусы), реальный прогон + Playwright.
