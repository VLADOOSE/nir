# Частные заявки (Блок B) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Раздел «Частные заявки» — заявки от частных клиник с названными брендами/моделями; проверка регистрации модели (реестр) + запрос КП у поставщиков. Переиспользуем тендер (поле `source`), лоты и КП-workflow.

**Architecture:** Частная заявка = `Tender` с `source=PRIVATE_REQUEST` + лоты `tender_lot` (со строкой `manufact`=бренд). КП = существующий `PriceRequest` (привязан к лоту). Реестр-статус строки = `RegistryMatchService.findCandidates(модель, бренд)`. Единый шов приёма `PrivateRequestService.createFromLines` (ручной ввод сейчас, авто-парсинг почты — блок D). Спека: `docs/superpowers/specs/2026-06-24-private-requests-design.md`.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA/Hibernate 6, MapStruct, Lombok, PostgreSQL; Angular 21 (standalone, SCSS).

## Global Constraints

- Java **17**, Spring Boot **3.5.6**, Gradle **8.14** — не менять.
- Backend `com.vladoose.nir`. Entity на Lombok; enum-поля `@Enumerated(STRING)` + `@Column(nullable=false)` + `@Builder.Default`; сервисы `@Service` + constructor injection без `@Autowired`, `@Transactional` на записи; контроллеры `@RestController @RequestMapping("/api/...")`, «голые» DTO, записи под `@PreAuthorize("hasRole('ADMIN')")`; мапперы MapStruct `@Mapper(componentModel="spring")`.
- БД PostgreSQL `localhost:5432/nirdb` (UTF-8). `ddl-auto: none`, `schema.sql` пересоздаёт таблицы (кроме `med_registry`), сид `data.sql`. **Новые колонки — с DEFAULT/nullable, чтобы `data.sql` грузился без правок.**
- **Рыночный скоуп уже работает** (блок A): `tender`/`price_request` несут колонку `market` + `@Filter`/`@PrePersist`-листенер. Частные заявки — это `tender`, поэтому скоупятся по рынку автоматически. Ничего market-специфичного добавлять не надо.
- **КРИТИЧНО (среда):** Bash-sandbox блокирует :5432 → ЛЮБЫЕ `./gradlew`/DB-команды с флагом `dangerouslyDisableSandbox: true`. `psql` = `/Library/PostgreSQL/17/bin/psql`, `PGPASSWORD=admin`.
- **Известные PRE-EXISTING падения, НЕ наши:** `ApplyAutoFillServiceTest` (2, наценка ×1.25). `./gradlew test` = BUILD FAILED ровно с этими 2 → норма; гейт: «компилируется + только эти 2».
- Фронт: Angular standalone, инлайн-шаблон + `styles: []`, `ApiService` (база `/api`, прокси :8080), `NotificationService.success/error`, `ConfirmService.ask`, валюта — пайп `money` (блок A). Фронт-тестов нет → гейт = `npm run build` + ручная проверка.
- Каждый commit заканчивать: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Ветка `feat/private-requests` (содержит спеку). Рабочее дерево содержит несвязанный WIP пользователя — НЕ трогать; коммитить только файлы своей задачи.

---

### Task 1: Backend модель — `Source` на tender, `manufact` на лоте, nullable deadline, nullable med_equipment в КП; фильтр тендеров на PUBLIC

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/Source.java`
- Modify: `src/main/resources/schema.sql`
- Modify entities: `Tender.java`, `TenderLot.java`, `PriceRequestItem.java`
- Modify DTO: `dto/request/TenderRequest.java`, `dto/response/TenderResponse.java`, `dto/request/TenderLotRequest.java`, `dto/response/TenderLotResponse.java`, `dto/response/TenderLotShortResponse.java`
- Modify mapper: `mapper/TenderMapper.java`, `mapper/TenderLotMapper.java`
- Modify: `service/TenderService.java`, `repository/TenderRepository.java`
- Test: `src/test/java/com/vladoose/nir/privaterequest/TenderSourceFilterTest.java`

**Interfaces:**
- Produces: enum `Source { PUBLIC_TENDER, PRIVATE_REQUEST }`; `Tender.source` (default PUBLIC_TENDER), nullable `Tender.deadline`; `TenderLot.manufact` (String, nullable); `PriceRequestItem.medEquipment` nullable; `TenderService.findAll()`/`searchTenders(...)` возвращают только `PUBLIC_TENDER`.

- [ ] **Step 1: Создать enum `Source`**

Create `src/main/java/com/vladoose/nir/entity/Source.java`:
```java
package com.vladoose.nir.entity;

public enum Source {
    PUBLIC_TENDER, PRIVATE_REQUEST
}
```

- [ ] **Step 2: Схема — колонки + ослабление NOT NULL**

В `src/main/resources/schema.sql`:
1. В `CREATE TABLE tender (...)`: колонку `deadline DATE NOT NULL` → `deadline DATE` (убрать NOT NULL); добавить колонку (например, после `currency`): `source VARCHAR(20) NOT NULL DEFAULT 'PUBLIC_TENDER',`.
2. В `CREATE TABLE tender_lot (...)`: добавить `manufact VARCHAR(255),`.
3. В `CREATE TABLE price_request_item (...)`: `med_equipment_id BIGINT NOT NULL REFERENCES ...` → убрать `NOT NULL` (`med_equipment_id BIGINT REFERENCES ...`).

- [ ] **Step 3: Entity — поля**

`Tender.java`: поле `deadline` — убрать `nullable=false` (`@Column private LocalDate deadline;`). Добавить:
```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Source source = Source.PUBLIC_TENDER;
```
`TenderLot.java`: добавить `@Column(length = 255) private String manufact;`
`PriceRequestItem.java`: у `medEquipment` `@JoinColumn(name = "med_equipment_id", nullable = false)` → убрать `nullable = false`.

- [ ] **Step 4: DTO — проброс полей**

> Важно: публичные тендеры по-прежнему создаются через `TenderRequest`/`TenderController` и им дедлайн нужен — `@NotNull` на `TenderRequest.deadline` НЕ трогаем. Частные заявки идут через отдельный `PrivateRequestCreate` (Task 2), не через `TenderRequest`. Nullable `deadline` нужен только на уровне entity/схемы (Steps 2-3).

`TenderResponse.java`: добавить `private String source;` (для отображения; маппится по имени авто).
`TenderLotRequest.java`: добавить `private String manufact;`.
`TenderLotResponse.java` и `TenderLotShortResponse.java`: добавить `private String manufact;`.
(`TenderRequest.java` — без изменений.)

- [ ] **Step 5: Mapper — source/manufact**

`TenderMapper.java`: в `toEntity` и `updateEntity` добавить `@Mapping(target = "source", ignore = true)` (source ставит сервис, не из request напрямую) — НО `toResponse` должен мапить source (имя совпадает → авто). Добавить `source` в `toShortResponse`? нет — короткий ответ без source.
`TenderLotMapper.java`: `manufact` мапится по имени авто (и в toEntity, и в toResponse) — добавлять `@Mapping` не нужно, поле есть в request/response/entity с одним именем. Проверить, что нет «unmapped target» варнинга по `manufact` (если есть — он маппится, ок).

- [ ] **Step 6: Фильтр тендеров на PUBLIC_TENDER**

`TenderRepository.java`: добавить метод
```java
    List<Tender> findBySource(com.vladoose.nir.entity.Source source);
```
и в `searchTenders` JPQL добавить условие `AND t.source = com.vladoose.nir.entity.Source.PUBLIC_TENDER` (публичный поиск — только тендеры).
`TenderService.java`: `findAll()` → `return repository.findBySource(Source.PUBLIC_TENDER);` (импорт `Source`). `searchTenders` — без изменений сигнатуры (фильтр в репозитории).

- [ ] **Step 7: Написать тест фильтрации**

Create `src/test/java/com/vladoose/nir/privaterequest/TenderSourceFilterTest.java`:
```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.TenderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenderSourceFilterTest {

    @Autowired TenderService tenderService;
    @Autowired TenderRepository tenderRepository;

    @Test
    void findAll_excludesPrivateRequests() {
        Tender priv = tenderRepository.save(Tender.builder()
                .tenderNumber("ZZPR-PRIV-1").status("NEW")
                .source(Source.PRIVATE_REQUEST).build());   // deadline null допустим
        tenderRepository.flush();

        assertThat(tenderService.findAll())
                .extracting(Tender::getTenderNumber)
                .doesNotContain("ZZPR-PRIV-1");
        assertThat(tenderRepository.findBySource(Source.PRIVATE_REQUEST))
                .extracting(Tender::getTenderNumber)
                .contains("ZZPR-PRIV-1");
    }

    @Test
    void publicTender_savedWithNullableDeadline_andDefaultSource() {
        Tender pub = tenderRepository.save(Tender.builder()
                .tenderNumber("ZZPR-PUB-1").status("NEW").build());
        tenderRepository.flush();
        assertThat(pub.getSource()).isEqualTo(Source.PUBLIC_TENDER);
        assertThat(pub.getDeadline()).isNull();
    }
}
```

- [ ] **Step 8: RED → реализация уже сделана в Steps 1-6 → GREEN**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.privaterequest.TenderSourceFilterTest"`
Expected: PASS (оба теста). Затем контекст-лоад: `./gradlew test --tests "com.vladoose.nir.Nir2ApplicationTests"` — PASS (schema + data.sql грузятся; существующие тендеры получают `source='PUBLIC_TENDER'` через DEFAULT).

- [ ] **Step 9: Полный suite — нет новых падений**

Run (sandbox off): `./gradlew test`
Expected: только 2 известных `ApplyAutoFillServiceTest`, ничего нового. (Особенно проверь, что КП-тесты `BulkPriceRequestServiceTest` зелёные — med_equipment стал nullable, но bulk-поток всё равно его заполняет.)

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/vladoose/nir/entity/Source.java src/main/resources/schema.sql src/main/java/com/vladoose/nir/entity/Tender.java src/main/java/com/vladoose/nir/entity/TenderLot.java src/main/java/com/vladoose/nir/entity/PriceRequestItem.java src/main/java/com/vladoose/nir/dto src/main/java/com/vladoose/nir/mapper/TenderMapper.java src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java src/main/java/com/vladoose/nir/service/TenderService.java src/main/java/com/vladoose/nir/repository/TenderRepository.java src/test/java/com/vladoose/nir/privaterequest/TenderSourceFilterTest.java
git commit -m "$(cat <<'EOF'
feat(private-requests): модель — Source на tender, manufact на лоте, nullable deadline/med_equip, фильтр тендеров на PUBLIC

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `PrivateRequestService` + DTO — приём строк, автономер, реестр-статус (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/PrivateRequestCreate.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestResponse.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestLineResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/PrivateRequestService.java`
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestServiceTest.java`

**Interfaces:**
- Consumes: `TenderRepository`, `TenderLotRepository`, `FacilityRepository`, `RegistryMatchService.findCandidates(name, manufact, limit)` (→ `List<RegistryCandidateResponse>`), `Source` (Task 1).
- Produces:
  - `PrivateRequestCreate` — `clientFacilityId` (Long), `note` (String, опц.), `lines` (List of `Line{ name, manufact, quantity }`).
  - `PrivateRequestLineResponse` — `lotId`, `name`, `manufact`, `quantity`, `registrationStatus` (String: REGISTERED/NOT_FOUND), `topCandidate` (RegistryCandidateResponse | null).
  - `PrivateRequestResponse` — `id`, `number`, `client` (FacilityResponse), `status`, `createdLines` (List<PrivateRequestLineResponse>).
  - `PrivateRequestService.createFromLines(PrivateRequestCreate) : Tender`; `findAll() : List<Tender>` (PRIVATE_REQUEST); `findById(Long) : Tender`; `linesWithRegistration(Long tenderId) : List<PrivateRequestLineResponse>`.

- [ ] **Step 1: DTO**

Create `src/main/java/com/vladoose/nir/dto/request/PrivateRequestCreate.java`:
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class PrivateRequestCreate {
    @NotNull(message = "Клиент обязателен")
    private Long clientFacilityId;
    private String note;

    @NotEmpty(message = "Нужна хотя бы одна строка")
    private List<Line> lines;

    @Data
    public static class Line {
        private String name;      // наименование/модель
        private String manufact;  // бренд
        private Integer quantity;
    }
}
```

Create `src/main/java/com/vladoose/nir/dto/response/PrivateRequestLineResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class PrivateRequestLineResponse {
    private Long lotId;
    private String name;
    private String manufact;
    private Integer quantity;
    private String registrationStatus;             // REGISTERED | NOT_FOUND
    private RegistryCandidateResponse topCandidate; // лучший кандидат реестра или null
}
```

Create `src/main/java/com/vladoose/nir/dto/response/PrivateRequestResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class PrivateRequestResponse {
    private Long id;
    private String number;
    private FacilityResponse client;
    private String status;
    private OffsetDateTime createdAt;
    private List<PrivateRequestLineResponse> lines;
}
```

- [ ] **Step 2: Написать падающий тест сервиса**

Create `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestServiceTest.java`:
```java
package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.PrivateRequestLineResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PrivateRequestServiceTest {

    @Autowired PrivateRequestService service;
    @Autowired FacilityRepository facilityRepository;
    @Autowired MedRegistryRepository registryRepository;

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private Long client() {
        return facilityRepository.save(Facility.builder().name("ZZPRS Клиника").build()).getId();
    }

    @Test
    void createFromLines_buildsPrivateTenderWithLotsAndAutoNumber() {
        PrivateRequestCreate dto = new PrivateRequestCreate();
        dto.setClientFacilityId(client());
        PrivateRequestCreate.Line l = new PrivateRequestCreate.Line();
        l.setName("Тонометр OMRON M2"); l.setManufact("OMRON"); l.setQuantity(3);
        dto.setLines(List.of(l));

        Tender t = service.createFromLines(dto);

        assertThat(t.getSource()).isEqualTo(Source.PRIVATE_REQUEST);
        assertThat(t.getTenderNumber()).startsWith("ЧЗ-");
        assertThat(t.getLots()).hasSize(1);
        TenderLot lot = t.getLots().get(0);
        assertThat(lot.getEquipName()).isEqualTo("Тонометр OMRON M2");
        assertThat(lot.getManufact()).isEqualTo("OMRON");
        assertThat(lot.getQuantity()).isEqualTo(3);
    }

    @Test
    void findAll_returnsOnlyPrivateRequests() {
        PrivateRequestCreate dto = new PrivateRequestCreate();
        dto.setClientFacilityId(client());
        PrivateRequestCreate.Line l = new PrivateRequestCreate.Line();
        l.setName("ZZPRS Аппарат"); l.setManufact("ZZBrand"); l.setQuantity(1);
        dto.setLines(List.of(l));
        Tender created = service.createFromLines(dto);

        assertThat(service.findAll()).extracting(Tender::getId).contains(created.getId());
        assertThat(service.findAll()).allMatch(t -> t.getSource() == Source.PRIVATE_REQUEST);
    }

    @Test
    void linesWithRegistration_returnsCandidateForRegisteredModel() {
        registryRepository.save(MedRegistry.builder()
                .regNumber("ZZPRS-RU-1").name("Тонометр ZZUNIQMODEL автоматический")
                .producer("ZZUNIQVENDOR").country("ЯПОНИЯ").unlimited(true).build());
        registryRepository.flush();

        PrivateRequestCreate dto = new PrivateRequestCreate();
        dto.setClientFacilityId(client());
        PrivateRequestCreate.Line l = new PrivateRequestCreate.Line();
        l.setName("Тонометр ZZUNIQMODEL автоматический"); l.setManufact("ZZUNIQVENDOR"); l.setQuantity(1);
        dto.setLines(List.of(l));
        Tender t = service.createFromLines(dto);

        List<PrivateRequestLineResponse> lines = service.linesWithRegistration(t.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getTopCandidate()).isNotNull();
        assertThat(lines.get(0).getTopCandidate().getRegNumber()).isEqualTo("ZZPRS-RU-1");
        assertThat(lines.get(0).getRegistrationStatus()).isEqualTo("REGISTERED");
    }
}
```

- [ ] **Step 3: Запустить — падает (нет `PrivateRequestService`)**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.privaterequest.PrivateRequestServiceTest"`
Expected: FAIL — компиляция (нет сервиса/DTO `PrivateRequestService`).

- [ ] **Step 4: Реализовать `PrivateRequestService`**

Create `src/main/java/com/vladoose/nir/service/PrivateRequestService.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.PrivateRequestLineResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class PrivateRequestService {

    private final TenderRepository tenderRepository;
    private final TenderLotRepository tenderLotRepository;
    private final FacilityRepository facilityRepository;
    private final RegistryMatchService registryMatchService;

    public PrivateRequestService(TenderRepository tenderRepository,
                                 TenderLotRepository tenderLotRepository,
                                 FacilityRepository facilityRepository,
                                 RegistryMatchService registryMatchService) {
        this.tenderRepository = tenderRepository;
        this.tenderLotRepository = tenderLotRepository;
        this.facilityRepository = facilityRepository;
        this.registryMatchService = registryMatchService;
    }

    /** Шов приёма: создаёт частную заявку (tender source=PRIVATE_REQUEST) + лоты из строк.
     *  Ручной ввод сейчас; авто-парсер почты (блок D) вызовет этот же метод. */
    @Transactional
    public Tender createFromLines(PrivateRequestCreate dto) {
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            throw new BadRequestException("Нужна хотя бы одна строка");
        }
        Facility client = facilityRepository.findById(dto.getClientFacilityId())
                .orElseThrow(() -> new NotFoundException("Клиент не найден: id=" + dto.getClientFacilityId()));

        Tender t = Tender.builder()
                .tenderNumber(nextNumber())
                .facility(client)
                .status("NEW")
                .source(Source.PRIVATE_REQUEST)
                .description(dto.getNote())
                .build();
        // market проставит @PrePersist-листенер из активного рынка
        Tender saved = tenderRepository.save(t);

        int lotNo = 1;
        for (PrivateRequestCreate.Line line : dto.getLines()) {
            TenderLot lot = TenderLot.builder()
                    .tender(saved)
                    .lotNumber(lotNo++)
                    .equipName(line.getName())
                    .manufact(line.getManufact())
                    .quantity(line.getQuantity())
                    .build();
            saved.getLots().add(tenderLotRepository.save(lot));
        }
        return saved;
    }

    public List<Tender> findAll() {
        return tenderRepository.findBySource(Source.PRIVATE_REQUEST);
    }

    public Tender findById(Long id) {
        Tender t = tenderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена: id=" + id));
        if (t.getSource() != Source.PRIVATE_REQUEST) {
            throw new NotFoundException("Заявка не найдена: id=" + id);
        }
        return t;
    }

    /** Реестр-статус по каждой строке заявки (через примитив реестра). */
    public List<PrivateRequestLineResponse> linesWithRegistration(Long tenderId) {
        Tender t = findById(tenderId);
        List<PrivateRequestLineResponse> result = new ArrayList<>();
        for (TenderLot lot : t.getLots()) {
            PrivateRequestLineResponse r = new PrivateRequestLineResponse();
            r.setLotId(lot.getId());
            r.setName(lot.getEquipName());
            r.setManufact(lot.getManufact());
            r.setQuantity(lot.getQuantity());
            List<RegistryCandidateResponse> cands =
                    registryMatchService.findCandidates(lot.getEquipName(), lot.getManufact(), 1);
            if (!cands.isEmpty()) {
                r.setTopCandidate(cands.get(0));
                r.setRegistrationStatus("REGISTERED");
            } else {
                r.setRegistrationStatus("NOT_FOUND");
            }
            result.add(r);
        }
        return result;
    }

    private String nextNumber() {
        int year = OffsetDateTime.now(ZoneOffset.UTC).getYear();
        long count = tenderRepository.findBySource(Source.PRIVATE_REQUEST).size();
        return String.format("ЧЗ-%d-%04d", year, count + 1);
    }
}
```
> Примечание: `nextNumber` — count-based (для одного оператора достаточно; рыночный @Filter ограничивает count активным рынком). При желании усилить — отдельный запрос max-номера, но это вне скоупа.

- [ ] **Step 5: Запустить — зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.privaterequest.PrivateRequestServiceTest"`
Expected: PASS (3 теста). Если реестр-тест падает на матче — проверь, что строки достаточно уникальны и реестр-БД UTF-8 (кириллица матчится).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/request/PrivateRequestCreate.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestResponse.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestLineResponse.java src/main/java/com/vladoose/nir/service/PrivateRequestService.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestServiceTest.java
git commit -m "$(cat <<'EOF'
feat(private-requests): PrivateRequestService — приём строк, автономер ЧЗ, реестр-статус строки

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: REST `PrivateRequestController` + допуск null med_equipment в КП

**Files:**
- Create: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java`

**Interfaces:**
- Consumes: `PrivateRequestService` (Task 2), `FacilityMapper` (для client в ответе).
- Produces (HTTP):
  - `GET /api/private-requests` → `List<PrivateRequestResponse>` (без строк-реестра — облегчённо: id/number/client/status; строки по `/{id}`)
  - `GET /api/private-requests/{id}` → `PrivateRequestResponse` с `lines` (реестр-статус)
  - `POST /api/private-requests` (ADMIN) body `PrivateRequestCreate` → `PrivateRequestResponse`
  - `PriceRequestController.create` теперь корректно обрабатывает item с `medEquipmentId == null` (частная заявка).

- [ ] **Step 1: Допуск null med_equipment в КП-create**

В `src/main/java/com/vladoose/nir/controller/PriceRequestController.java`, в методе `create`, где создаются `PriceRequestItem` из `request.getItems()`: убедиться, что `medEquipment` ставится только если `item.getMedEquipmentId() != null` (иначе `null`). Найти строку, где резолвится `medEquipmentService.findById(item.getMedEquipmentId())` и обернуть в null-проверку:
```java
            if (it.getMedEquipmentId() != null) {
                pri.setMedEquipment(medEquipmentService.findById(it.getMedEquipmentId()));
            }
```
(`tenderLot` остаётся обязательным — для частной заявки это строка-лот.)

- [ ] **Step 2: Создать `PrivateRequestController`**

Create `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java`:
```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.PrivateRequestResponse;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.mapper.FacilityMapper;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/private-requests")
public class PrivateRequestController {

    private final PrivateRequestService service;
    private final FacilityMapper facilityMapper;

    public PrivateRequestController(PrivateRequestService service, FacilityMapper facilityMapper) {
        this.service = service;
        this.facilityMapper = facilityMapper;
    }

    @GetMapping
    public List<PrivateRequestResponse> findAll() {
        return service.findAll().stream().map(this::toShort).toList();
    }

    @GetMapping("/{id}")
    public PrivateRequestResponse findById(@PathVariable Long id) {
        Tender t = service.findById(id);
        PrivateRequestResponse r = toShort(t);
        r.setLines(service.linesWithRegistration(id));
        return r;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestResponse create(@Valid @RequestBody PrivateRequestCreate request) {
        Tender t = service.createFromLines(request);
        PrivateRequestResponse r = toShort(t);
        r.setLines(service.linesWithRegistration(t.getId()));
        return r;
    }

    private PrivateRequestResponse toShort(Tender t) {
        PrivateRequestResponse r = new PrivateRequestResponse();
        r.setId(t.getId());
        r.setNumber(t.getTenderNumber());
        r.setStatus(t.getStatus());
        if (t.getFacility() != null) {
            r.setClient(facilityMapper.toResponse(t.getFacility()));
        }
        return r;
    }
}
```
> Примечание: проверить имя метода маппера фасилити (`facilityMapper.toResponse(...)`) — если иное, подставить фактическое.

- [ ] **Step 3: Скомпилировать + suite**

Run (sandbox off): `./gradlew test`
Expected: компиляция ок; только 2 известных падения; КП-тесты зелёные.

- [ ] **Step 4: Ручная проверка эндпоинтов (приложение + curl)**

```bash
./gradlew bootRun > /tmp/pr-boot.log 2>&1 &
sleep 30
curl -s -c /tmp/prc -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' -o /dev/null -w "login:%{http_code}\n"
# создать частную заявку под рынком KZ (клиент — KZ-учреждение из демо). Сначала возьмём id KZ-facility:
CID=$(curl -s -b /tmp/prc -H "X-Market: KZ" http://localhost:8080/api/facilities | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['id'])")
curl -s -b /tmp/prc -H "X-Market: KZ" -H 'Content-Type: application/json' -X POST http://localhost:8080/api/private-requests \
  -d "{\"clientFacilityId\":$CID,\"lines\":[{\"name\":\"Тонометр OMRON M2\",\"manufact\":\"OMRON\",\"quantity\":2}]}" | head -c 600; echo
curl -s -b /tmp/prc -H "X-Market: KZ" http://localhost:8080/api/private-requests | head -c 400; echo
kill %1 2>/dev/null
```
Expected: создаётся заявка с номером `ЧЗ-...`, в ответе строки с `registrationStatus`. (Если auth-cookie капризничает — отложить до UI; гейт — зелёный `./gradlew test`.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/main/java/com/vladoose/nir/controller/PriceRequestController.java
git commit -m "$(cat <<'EOF'
feat(private-requests): REST /api/private-requests + допуск null med_equipment в КП-create

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Фронт — раздел «Частные заявки» (список + форма создания) + маршрут + навигация + ApiService

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Create: `frontend/src/app/pages/private-requests/private-requests.component.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/layout/layout.component.ts`

**Interfaces:**
- Consumes: `/api/private-requests` (Task 3), `/api/facilities` (клиенты).
- Produces: маршрут `/private-requests`; методы `ApiService.getPrivateRequests/getPrivateRequest/createPrivateRequest`.

- [ ] **Step 1: Методы ApiService**

В `frontend/src/app/services/api.service.ts` добавить:
```typescript
  // === Частные заявки ===
  getPrivateRequests(): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/private-requests`);
  }
  getPrivateRequest(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/private-requests/${id}`);
  }
  createPrivateRequest(body: any): Observable<any> {
    return this.http.post<any>(`${this.base}/private-requests`, body);
  }
```

- [ ] **Step 2: Компонент раздела (список + форма создания)**

Create `frontend/src/app/pages/private-requests/private-requests.component.ts` — standalone, паттерн как `tenders.component.ts`: инжект `ApiService`, `ChangeDetectorRef`, `NotificationService`, `Router`, `MarketService` (валюта/контекст). Список заявок (номер `ЧЗ-...`, клиент, число строк, статус), кнопка «Новая заявка» → форма: выбор клиента (`getFacilities()`), динамический список строк `{name, manufact, quantity}` (добавить/удалить), сохранение через `createPrivateRequest({clientFacilityId, note, lines})`. Клик по заявке открывает карточку (Task 5 — на этом шаге достаточно перехода на `/private-requests?openId=ID` или хранение `selectedId`). Полный шаблон/SCSS — инлайн, в стиле существующих страниц (таблица + форма). Иконки lucide — использовать уже зарегистрированные (`clipboard-list`, `plus`, `trash-2`).
```typescript
import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';

@Component({
  selector: 'app-private-requests',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <div class="page">
      <header class="head">
        <div>
          <h1>Частные заявки</h1>
          <p class="sub">Заявки от частных клиник ({{ market.companyLabel() }}). Клиника называет бренд/модель — проверяем регистрацию и запрашиваем КП.</p>
        </div>
        <button class="btn-primary" (click)="openForm()">+ Новая заявка</button>
      </header>

      <!-- форма создания -->
      <div class="form-card" *ngIf="showForm">
        <h3>Новая частная заявка</h3>
        <label>Клиент (клиника)
          <select [(ngModel)]="form.clientFacilityId">
            <option [ngValue]="null" disabled>— выберите —</option>
            <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
          </select>
        </label>
        <div class="lines">
          <div class="line-head"><span>Наименование/модель</span><span>Бренд</span><span>Кол-во</span><span></span></div>
          <div class="line" *ngFor="let l of form.lines; let i = index">
            <input [(ngModel)]="l.name" placeholder="Тонометр OMRON M2" />
            <input [(ngModel)]="l.manufact" placeholder="OMRON" />
            <input type="number" [(ngModel)]="l.quantity" min="1" />
            <button class="btn-del" (click)="removeLine(i)" [disabled]="form.lines.length === 1">✕</button>
          </div>
        </div>
        <button class="btn-line" (click)="addLine()">+ строка</button>
        <div class="form-actions">
          <button class="btn-primary" (click)="save()">Создать заявку</button>
          <button class="btn-ghost" (click)="showForm = false">Отмена</button>
        </div>
        <div class="err" *ngIf="formError">{{ formError }}</div>
      </div>

      <div class="loading" *ngIf="loading">Загрузка…</div>
      <table *ngIf="!loading && rows.length">
        <thead><tr><th>Номер</th><th>Клиент</th><th>Строк</th><th>Статус</th></tr></thead>
        <tbody>
          <tr class="row" *ngFor="let r of rows" (click)="openCard(r)">
            <td class="num">{{ r.number }}</td>
            <td>{{ r.client?.name || '—' }}</td>
            <td>{{ r.lines?.length ?? '—' }}</td>
            <td><span class="badge">{{ r.status }}</span></td>
          </tr>
        </tbody>
      </table>
      <div class="empty" *ngIf="!loading && !rows.length">Заявок пока нет.</div>

      <app-private-request-card *ngIf="cardId !== null" [requestId]="cardId" (close)="cardId = null; load()"></app-private-request-card>
    </div>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    h1 { font-size: 22px; color: #111827; }
    .sub { color: #6b7280; font-size: 13px; margin-top: 4px; max-width: 640px; }
    .btn-primary { background: #1a56db; color: #fff; border: none; padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; }
    .btn-ghost { background: #fff; border: 1px solid #d1d5db; color: #374151; padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; }
    .form-card { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 10px; padding: 16px; margin-bottom: 18px; }
    .form-card h3 { font-size: 15px; margin-bottom: 10px; }
    .form-card label { display: block; font-size: 13px; color: #374151; margin-bottom: 10px; }
    .form-card select, .line input { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
    .form-card select { min-width: 320px; margin-top: 4px; }
    .lines { margin: 8px 0; }
    .line-head, .line { display: grid; grid-template-columns: 1fr 200px 90px 32px; gap: 8px; align-items: center; margin-bottom: 6px; }
    .line-head span { font-size: 11px; color: #6b7280; text-transform: uppercase; }
    .line input { width: 100%; }
    .btn-del { background: #fff; border: 1px solid #d1d5db; border-radius: 6px; cursor: pointer; color: #991b1b; }
    .btn-line { background: #fff; border: 1px dashed #9ca3af; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; color: #374151; }
    .form-actions { display: flex; gap: 8px; margin-top: 12px; }
    .err { color: #991b1b; font-size: 13px; margin-top: 8px; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    thead th { text-align: left; padding: 8px 10px; color: #6b7280; border-bottom: 1px solid #e5e7eb; }
    .row { cursor: pointer; border-bottom: 1px solid #f3f4f6; }
    .row:hover { background: #f9fafb; }
    .row td { padding: 9px 10px; }
    .num { font-weight: 600; color: #1a56db; }
    .badge { padding: 2px 9px; border-radius: 10px; font-size: 12px; font-weight: 600; background: #e5e7eb; color: #374151; }
    .loading, .empty { padding: 30px; text-align: center; color: #9ca3af; }
  `]
})
export class PrivateRequestsComponent {
  rows: any[] = [];
  facilities: any[] = [];
  loading = false;
  showForm = false;
  formError = '';
  cardId: number | null = null;
  form: { clientFacilityId: number | null; note: string; lines: any[] } = this.emptyForm();

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private route: ActivatedRoute, private notify: NotificationService,
              public market: MarketService) {
    this.api.getFacilities().subscribe({ next: d => { this.facilities = d; this.cdr.detectChanges(); } });
    this.route.queryParams.subscribe(p => { if (p['openId']) { this.cardId = +p['openId']; } });
    this.load();
  }

  emptyForm() { return { clientFacilityId: null, note: '', lines: [{ name: '', manufact: '', quantity: 1 }] }; }
  openForm() { this.form = this.emptyForm(); this.formError = ''; this.showForm = true; }
  addLine() { this.form.lines.push({ name: '', manufact: '', quantity: 1 }); }
  removeLine(i: number) { if (this.form.lines.length > 1) this.form.lines.splice(i, 1); }
  openCard(r: any) { this.cardId = r.id; }

  load() {
    this.loading = true;
    this.api.getPrivateRequests().subscribe({
      next: d => { this.rows = d; this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.notify.error('Ошибка загрузки: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); }
    });
  }

  save() {
    if (!this.form.clientFacilityId) { this.formError = 'Выберите клиента'; return; }
    const lines = this.form.lines.filter(l => l.name && l.name.trim());
    if (!lines.length) { this.formError = 'Добавьте хотя бы одну строку с наименованием'; return; }
    this.api.createPrivateRequest({ clientFacilityId: this.form.clientFacilityId, note: this.form.note, lines }).subscribe({
      next: () => { this.showForm = false; this.notify.success('Заявка создана'); this.load(); },
      error: e => { this.formError = e.error?.message || 'Ошибка создания'; this.cdr.detectChanges(); }
    });
  }
}
```
> Примечание: компонент `<app-private-request-card>` создаётся в Task 5; до Task 5 сборка пройдёт только после добавления его импорта. Чтобы Task 4 собиралась независимо, на этом шаге добавь во `imports` декоратора заглушку НЕТ — вместо этого в Task 4 временно убери строку `<app-private-request-card …>` и блок откроется в Task 5. (Проще: Task 4 и Task 5 — последовательны; на шаге сборки Task 4 закомментируй тег карточки, в Task 5 раскомментируй и добавь импорт.)

- [ ] **Step 3: Маршрут + навигация**

`frontend/src/app/app.routes.ts`: импорт `PrivateRequestsComponent` + маршрут в `children`: `{ path: 'private-requests', component: PrivateRequestsComponent },`.
`frontend/src/app/layout/layout.component.ts`: в группе «Заявки» (рядом с «Заявки на участие») добавить:
```html
    <a routerLink="/private-requests" routerLinkActive="active">
      <svg lucideIcon="clipboard-list" [size]="16"></svg> Частные заявки
    </a>
```

- [ ] **Step 4: Сборка**

Run: `cd frontend && npm run build` (sandbox off при необходимости).
Expected: компиляция без ошибок (с временно закомментированным тегом карточки).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/pages/private-requests/ frontend/src/app/app.routes.ts frontend/src/app/layout/layout.component.ts
git commit -m "$(cat <<'EOF'
feat(private-requests): фронт — раздел «Частные заявки» (список + форма создания)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Фронт — карточка заявки (строки + реестр-статус + запрос КП + ответы)

**Files:**
- Create: `frontend/src/app/pages/private-requests/private-request-card.component.ts`
- Modify: `frontend/src/app/pages/private-requests/private-requests.component.ts` (раскомментировать тег карточки + импорт)

**Interfaces:**
- Consumes: `/api/private-requests/{id}` (строки + реестр-статус), `/api/distributors`, `createPriceRequest`, `getPriceRequestsByTender`, `updatePriceRequestResponses` (Task 3 + существующие).
- Produces: компонент `<app-private-request-card [requestId] (close)>`.

- [ ] **Step 1: Карточка заявки**

Create `frontend/src/app/pages/private-requests/private-request-card.component.ts` — standalone, drawer/modal как `equipment-detail-modal.component.ts`. `@Input() requestId`, `@Output() close`. На `ngOnChanges` грузит `getPrivateRequest(requestId)` (строки с `registrationStatus`/`topCandidate`) + `getPriceRequestsByTender(requestId)` (существующие КП). Отображает:
- Шапку: номер, клиент, статус.
- Таблицу строк: наименование/модель, бренд, кол-во, **реестр-бейдж** (`REGISTERED` зелёный + НДС-льгота / `NOT_FOUND` серый) с № РУ топ-кандидата (стиль бейджа — как в реестр-сверке: `.b-REGISTERED`/`.b-NOT_FOUND`).
- Блок «Запросить КП»: выбор поставщика (`getDistributors()`) → создать `PriceRequest` по строкам заявки через `createPriceRequest({ tenderId: requestId, distributorId, status:'SENT', sentAt, items: lines.map(l => ({ tenderLotId: l.lotId, medEquipmentId: null, requestedQuantity: l.quantity })) })`.
- Список существующих КП по заявке с вводом цены ответа (`updatePriceRequestResponses(prId, [{itemId, responsePrice, responseNote}])`) — переиспуем существующий контракт.
Используй пайп `money` для цен (импортируй `MarketMoneyPipe`). Реестр-бейджи — те же классы/подписи, что в `RegistryReconciliationComponent`/карточке оборудования. Полный шаблон/SCSS — инлайн, зеркаля стиль `equipment-detail-modal.component.ts` (overlay + sidebar).

(Точную разметку реализующий пишет по образцу существующих компонентов; ключевые вызовы API и поля — выше. Без автоподбора и параметрических полей.)

- [ ] **Step 2: Подключить карточку в разделе**

В `private-requests.component.ts`: добавить в `imports` декоратора `PrivateRequestCardComponent` (импорт) и убедиться, что тег `<app-private-request-card [requestId]="cardId" (close)="cardId = null; load()">` присутствует (раскомментировать из Task 4).

- [ ] **Step 3: Сборка**

Run: `cd frontend && npm run build` (sandbox off при необходимости).
Expected: компиляция без ошибок.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/pages/private-requests/
git commit -m "$(cat <<'EOF'
feat(private-requests): фронт — карточка заявки (строки + реестр-статус + запрос КП + ответы)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Финальная проверка (после всех задач)

- [ ] `./gradlew test` (sandbox off) — компилируется, только 2 известных `ApplyAutoFillServiceTest`, все private-request + tender + КП тесты зелёные.
- [ ] `cd frontend && npm run build` — фронт собирается.
- [ ] **E2E-смоук (браузер, рынок KZ):** раздел «Частные заявки» → «Новая заявка» (клиент = KZ-клиника, строка «Тонометр OMRON M2» / бренд OMRON / кол-во 2) → создать → открыть карточку → строка показывает реестр-статус (кандидат/нет) → «Запросить КП» у поставщика → ввести цену ответа. Раздел «Тендеры» НЕ показывает эту частную заявку (фильтр source).
