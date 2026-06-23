# Registry-Service (Казахстан, НЦЭЛС) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Обогащение каталога `med_equipment` привязкой № РУ из гос-реестра медизделий РК (НЦЭЛС): авто-подсказка кандидатов (`pg_trgm`) + подтверждение человеком, статус допуска и производный НДС-флаг, bulk-экран «Сверка с реестром» и инлайн-бейдж в карточке.

**Architecture:** Три слоя — офлайн Node/Puppeteer фетчер (готов, вне этого плана) пишет JSON-дамп → Java импортирует в таблицу `med_registry` (Postgres) → live-матчинг на `pg_trgm` чисто на Java/Spring. Связь каталог↔реестр по естественному ключу `reg_number` (FK), реимпорт через `ON CONFLICT … DO UPDATE` не рвёт привязки. Спека: `docs/superpowers/specs/2026-06-23-registry-service-kz-design.md`.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA, MapStruct 1.5.5, Lombok, PostgreSQL + `pg_trgm`, Gradle 8.14; Angular 21 (standalone, SCSS, @lucide/angular).

## Global Constraints

- Java **17**, Spring Boot **3.5.6**, Gradle wrapper **8.14** — не менять версии.
- Backend пакет-корень: `com.vladoose.nir`. Конвенции: Entity на Lombok (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`); репозитории `extends JpaRepository<T, Long>`; сервисы `@Service` + **constructor injection без `@Autowired`**, `@Transactional` на методах записи; ошибки — `new NotFoundException(...)` / `new BadRequestException(...)` (ловит `GlobalExceptionHandler`); мапперы — MapStruct `@Mapper(componentModel = "spring")`; контроллеры `@RestController @RequestMapping("/api/...")`, возвращают «голые» DTO (не `ResponseEntity`), записи под `@PreAuthorize("hasRole('ADMIN')")`.
- БД: PostgreSQL `localhost:5432/nirdb`, `postgres`/`admin`. `ddl-auto: none`, `spring.sql.init.mode: always` — схемой управляет `schema.sql`, сид — `data.sql` (пересоздаются на каждом старте). Таблица `med_registry` — **живучая** (`IF NOT EXISTS`, НЕ в DROP-списке).
- Тесты: `@SpringBootTest` + `@Transactional` (откат после каждого) против **реального** localhost Postgres, AssertJ `assertThat`, репозитории `@Autowired`. Запуск: `./gradlew test`. **Перед прогоном тестов локальный Postgres `nirdb` должен быть запущен** (как и для существующих тестов).
- Фронт: Angular standalone-компоненты, инлайн-шаблон + `styles: []` (SCSS), иконки `@lucide/angular` (`lucideIcon`/`LucideDynamicIcon`), вызовы через `ApiService` (база `/api`, проксируется на :8080). Фронт-тестов в проекте нет — фронт-задачи проверяются запуском приложения вручную. Запуск фронта: `cd frontend && npm start` (ng serve :4200).
- Каждый git-commit заканчивать строкой:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- Ветка уже создана: `feat/registry-service-kz`. Работаем в ней.

---

### Task 1: Схема БД — таблица `med_registry`, `pg_trgm`, колонки регистрации на `med_equipment`, бандл дампа

**Files:**
- Modify: `src/main/resources/schema.sql`
- Create: `src/main/resources/registry/rk-mi-registry-full.json` (копия дампа)

**Interfaces:**
- Produces: таблица `med_registry(id, reg_number UNIQUE, name, producer, country, reg_date, expiration_date, unlimited, imported_at)`; колонки `med_equipment.registration_status`, `med_equipment.med_registry_reg_number` (FK→`med_registry.reg_number`), `med_equipment.registration_checked_at`; расширение `pg_trgm`; ресурс-дамп `classpath:registry/rk-mi-registry-full.json`.

- [ ] **Step 1: Скопировать дамп реестра в ресурсы приложения**

Run:
```bash
mkdir -p src/main/resources/registry && \
cp /Users/vlad/IdeaProjects/westmed/scripts/data/rk-mi-registry-full.json src/main/resources/registry/rk-mi-registry-full.json && \
ls -lh src/main/resources/registry/rk-mi-registry-full.json
```
Expected: файл ~4.7M на месте.

- [ ] **Step 2: Добавить `pg_trgm` и таблицу `med_registry` в начало `schema.sql`**

В `src/main/resources/schema.sql` сразу ПОСЛЕ блока удаления старых таблиц (после строки `DROP TABLE IF EXISTS tender_founder CASCADE;`) и ДО `-- ========== Справочники ==========` вставить:
```sql
-- ========== Реестр медизделий (живучая таблица, не пересоздаётся) ==========
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS med_registry (
    id              BIGSERIAL PRIMARY KEY,
    reg_number      VARCHAR(100) NOT NULL UNIQUE,   -- № РУ (естественный ключ)
    name            TEXT NOT NULL,                   -- наименование МИ
    producer        VARCHAR(500),
    country         VARCHAR(200),
    reg_date        DATE,
    expiration_date DATE,
    unlimited       BOOLEAN DEFAULT FALSE,
    imported_at     TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_reg_name_trgm     ON med_registry USING gin (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_reg_producer_trgm ON med_registry USING gin (producer gin_trgm_ops);
```

- [ ] **Step 3: Добавить колонки регистрации в определение `med_equipment`**

В `src/main/resources/schema.sql`, в `CREATE TABLE med_equipment (...)`, ПЕРЕД блоком `CONSTRAINT med_equipment_length_positive ...` добавить три колонки (после строки `spec          TEXT,`):
```sql
    registration_status     VARCHAR(30) NOT NULL DEFAULT 'UNCHECKED',
    med_registry_reg_number VARCHAR(100) REFERENCES med_registry(reg_number),
    registration_checked_at TIMESTAMPTZ,
```
(Запятые сохранить — далее идут CONSTRAINT-строки.)

- [ ] **Step 4: Проверить, что контекст поднимается и схема применяется**

Run: `./gradlew test --tests "com.vladoose.nir.Nir2ApplicationTests"`
Expected: PASS (`contextLoads` — приложение стартует, `schema.sql` применяется к `nirdb`, `data.sql` грузится без ошибок).

- [ ] **Step 5: Проверить структуру БД напрямую**

Run:
```bash
PGPASSWORD=admin psql -h localhost -U postgres -d nirdb -c "\d med_registry" -c "SELECT column_name FROM information_schema.columns WHERE table_name='med_equipment' AND column_name LIKE 'registration%' OR column_name='med_registry_reg_number';" -c "SELECT extname FROM pg_extension WHERE extname='pg_trgm';"
```
Expected: таблица `med_registry` с GIN-индексами; колонки `registration_status`, `med_registry_reg_number`, `registration_checked_at`; расширение `pg_trgm` присутствует.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/schema.sql src/main/resources/registry/rk-mi-registry-full.json
git commit -m "$(cat <<'EOF'
feat(registry): схема med_registry + pg_trgm + колонки регистрации на med_equipment

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Entity `MedRegistry`, репозиторий + native trgm-запрос кандидатов (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/MedRegistry.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/RegistryCandidateRow.java` (projection)
- Create: `src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java`
- Test: `src/test/java/com/vladoose/nir/repository/MedRegistryRepositoryTest.java`

**Interfaces:**
- Consumes: таблица `med_registry` (Task 1).
- Produces:
  - `MedRegistry` (entity) — getters: `getRegNumber()`, `getName()`, `getProducer()`, `getCountry()`, `getRegDate()` `LocalDate`, `getExpirationDate()` `LocalDate`, `getUnlimited()` `Boolean`.
  - `RegistryCandidateRow` (projection) — `getRegNumber()`, `getName()`, `getProducer()`, `getCountry()`, `getRegDate()` `LocalDate`, `getExpirationDate()` `LocalDate`, `getUnlimited()` `Boolean`, `getScore()` `Double`.
  - `MedRegistryRepository.findByRegNumber(String) : Optional<MedRegistry>`, `findCandidates(String name, String manufact, int limit) : List<RegistryCandidateRow>`, `count()`, `save(...)`.

- [ ] **Step 1: Написать падающий тест на ранжирование кандидатов**

Create `src/test/java/com/vladoose/nir/repository/MedRegistryRepositoryTest.java`:
```java
package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.RegistryCandidateRow;
import com.vladoose.nir.entity.MedRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MedRegistryRepositoryTest {

    @Autowired
    MedRegistryRepository repository;

    private MedRegistry row(String reg, String name, String producer) {
        return MedRegistry.builder()
                .regNumber(reg).name(name).producer(producer)
                .country("КАЗАХСТАН").unlimited(true)
                .build();
    }

    @Test
    void findCandidates_ranksBestMatchFirstAndPopulatesScore() {
        // Уникальные токены, чтобы тест не пересекался с реальными 14k записями
        repository.save(row("ZZTEST-001", "Аппарат УЗИ ТЕСТУНИКУМ", "ZZTESTVENDOR-QWE Ltd"));
        repository.save(row("ZZTEST-002", "Прибор посторонний ЯБЛОКО",   "ZZTESTVENDOR-QWE Ltd"));
        repository.save(row("ZZTEST-003", "Аппарат УЗИ ТЕСТУНИКУМ", "Совсем Другой ВендорZZQ"));
        repository.flush();

        List<RegistryCandidateRow> result =
                repository.findCandidates("Аппарат УЗИ ТЕСТУНИКУМ", "ZZTESTVENDOR-QWE Ltd", 5);

        assertThat(result).isNotEmpty();
        // Лучший матч: совпали и производитель (вес 0.6), и наименование (вес 0.4)
        assertThat(result.get(0).getRegNumber()).isEqualTo("ZZTEST-001");
        assertThat(result.get(0).getScore()).isNotNull();
        assertThat(result.get(0).getScore()).isGreaterThan(0.0);
        // Все три наши записи попали в выдачу
        assertThat(result).extracting(RegistryCandidateRow::getRegNumber)
                .contains("ZZTEST-001", "ZZTEST-002", "ZZTEST-003");
    }

    @Test
    void findByRegNumber_returnsRow() {
        repository.save(row("ZZTEST-FIND", "Тест-наименование", "Тест-производитель"));
        repository.flush();
        assertThat(repository.findByRegNumber("ZZTEST-FIND")).isPresent();
        assertThat(repository.findByRegNumber("НЕТ-ТАКОГО")).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить тест — убедиться, что не компилируется/падает**

Run: `./gradlew test --tests "com.vladoose.nir.repository.MedRegistryRepositoryTest"`
Expected: FAIL — компиляция падает (`MedRegistry`, `RegistryCandidateRow`, `MedRegistryRepository` ещё не существуют).

- [ ] **Step 3: Создать entity `MedRegistry`**

Create `src/main/java/com/vladoose/nir/entity/MedRegistry.java`:
```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "med_registry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reg_number", nullable = false, unique = true, length = 100)
    private String regNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(length = 500)
    private String producer;

    @Column(length = 200)
    private String country;

    @Column(name = "reg_date")
    private LocalDate regDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    private Boolean unlimited;

    @Column(name = "imported_at")
    private OffsetDateTime importedAt;
}
```

- [ ] **Step 4: Создать projection `RegistryCandidateRow`**

Create `src/main/java/com/vladoose/nir/dto/response/RegistryCandidateRow.java`:
```java
package com.vladoose.nir.dto.response;

import java.time.LocalDate;

/** Проекция результата native trgm-запроса кандидатов реестра. */
public interface RegistryCandidateRow {
    String getRegNumber();
    String getName();
    String getProducer();
    String getCountry();
    LocalDate getRegDate();
    LocalDate getExpirationDate();
    Boolean getUnlimited();
    Double getScore();
}
```

- [ ] **Step 5: Создать репозиторий с native trgm-запросом**

Create `src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java`:
```java
package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.RegistryCandidateRow;
import com.vladoose.nir.entity.MedRegistry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MedRegistryRepository extends JpaRepository<MedRegistry, Long> {

    Optional<MedRegistry> findByRegNumber(String regNumber);

    /**
     * Кандидаты по нечёткой триграммной похожести: производитель (0.6) + наименование (0.4).
     * Алиасы в camelCase без подчёркиваний → проекция матчится case-insensitive.
     */
    @Query(nativeQuery = true, value =
            "SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, " +
            "m.country AS country, m.reg_date AS regDate, m.expiration_date AS expirationDate, " +
            "m.unlimited AS unlimited, " +
            "(0.6 * similarity(m.producer, :manufact) + 0.4 * similarity(m.name, :name)) AS score " +
            "FROM med_registry m " +
            "WHERE m.producer % :manufact OR m.name % :name " +
            "ORDER BY score DESC " +
            "LIMIT :limit")
    List<RegistryCandidateRow> findCandidates(@Param("name") String name,
                                              @Param("manufact") String manufact,
                                              @Param("limit") int limit);
}
```

- [ ] **Step 6: Запустить тест — убедиться, что проходит**

Run: `./gradlew test --tests "com.vladoose.nir.repository.MedRegistryRepositoryTest"`
Expected: PASS (оба теста). Если падает на projection `getScore()`/порядке — проверить, что алиасы в SELECT без подчёркиваний (`regNumber`, `regDate`, `expirationDate`, `score`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/entity/MedRegistry.java src/main/java/com/vladoose/nir/dto/response/RegistryCandidateRow.java src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java src/test/java/com/vladoose/nir/repository/MedRegistryRepositoryTest.java
git commit -m "$(cat <<'EOF'
feat(registry): MedRegistry entity + репозиторий с trgm-поиском кандидатов

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Импорт дампа в `med_registry` (upsert) + автозагрузка на старте (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/RegistryDumpRecord.java`
- Create: `src/main/java/com/vladoose/nir/service/RegistryImportService.java`
- Create: `src/main/java/com/vladoose/nir/config/RegistryDataInitializer.java`
- Create: `src/test/resources/registry/test-dump.json`
- Test: `src/test/java/com/vladoose/nir/service/RegistryImportServiceTest.java`

**Interfaces:**
- Consumes: `MedRegistryRepository` (Task 2), ресурс-дамп (Task 1).
- Produces: `RegistryImportService.importFromDump() : int` (число записей), `importIfEmpty() : int`; конфиг-проперти `registry.kz.dump-location` (default `classpath:registry/rk-mi-registry-full.json`).

- [ ] **Step 1: Создать маленький тест-дамп**

Create `src/test/resources/registry/test-dump.json`:
```json
[
  {"reg":"ZZIMP-001","name":"Тест Аппарат А","producer":"Импорт-Вендор-1","country":"КАЗАХСТАН","regDate":"2025-01-10T00:00:00","exp":"2030-01-10T00:00:00","unlimited":false},
  {"reg":"ZZIMP-002","name":"Тест Аппарат Б","producer":"Импорт-Вендор-2","country":"РОССИЯ","regDate":"2024-05-01T00:00:00","exp":null,"unlimited":true},
  {"reg":"ZZIMP-003","name":"Тест Аппарат В","producer":"Импорт-Вендор-3","country":"ГЕРМАНИЯ","regDate":null,"exp":null,"unlimited":true}
]
```

- [ ] **Step 2: Написать падающий тест импорта (вставка + идемпотентность)**

Create `src/test/java/com/vladoose/nir/service/RegistryImportServiceTest.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "registry.kz.dump-location=classpath:registry/test-dump.json")
class RegistryImportServiceTest {

    @Autowired RegistryImportService importService;
    @Autowired MedRegistryRepository repository;

    @Test
    void importFromDump_insertsRecordsAndParsesFields() {
        int n = importService.importFromDump();
        assertThat(n).isEqualTo(3);

        MedRegistry a = repository.findByRegNumber("ZZIMP-001").orElseThrow();
        assertThat(a.getName()).isEqualTo("Тест Аппарат А");
        assertThat(a.getProducer()).isEqualTo("Импорт-Вендор-1");
        assertThat(a.getRegDate()).isEqualTo(LocalDate.of(2025, 1, 10));
        assertThat(a.getExpirationDate()).isEqualTo(LocalDate.of(2030, 1, 10));
        assertThat(a.getUnlimited()).isFalse();

        MedRegistry b = repository.findByRegNumber("ZZIMP-002").orElseThrow();
        assertThat(b.getExpirationDate()).isNull();
        assertThat(b.getUnlimited()).isTrue();
    }

    @Test
    void importFromDump_isIdempotentByRegNumber() {
        importService.importFromDump();
        importService.importFromDump(); // повторный прогон не должен плодить дубли
        long count = repository.findAll().stream()
                .filter(r -> r.getRegNumber().startsWith("ZZIMP-")).count();
        assertThat(count).isEqualTo(3);
    }
}
```

- [ ] **Step 3: Запустить — убедиться, что падает**

Run: `./gradlew test --tests "com.vladoose.nir.service.RegistryImportServiceTest"`
Expected: FAIL — `RegistryImportService` не существует (ошибка компиляции).

- [ ] **Step 4: Создать DTO записи дампа**

Create `src/main/java/com/vladoose/nir/service/RegistryDumpRecord.java`:
```java
package com.vladoose.nir.service;

import lombok.Data;

/** Запись slim-дампа реестра РК (ключи как в rk-mi-registry-full.json). */
@Data
public class RegistryDumpRecord {
    private String reg;
    private String name;
    private String producer;
    private String country;
    private String regDate;
    private String exp;
    private Boolean unlimited;
}
```

- [ ] **Step 5: Создать `RegistryImportService`**

Create `src/main/java/com/vladoose/nir/service/RegistryImportService.java`:
```java
package com.vladoose.nir.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

@Service
public class RegistryImportService {

    private static final String UPSERT_SQL =
            "INSERT INTO med_registry (reg_number, name, producer, country, reg_date, expiration_date, unlimited, imported_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, now()) " +
            "ON CONFLICT (reg_number) DO UPDATE SET " +
            "name = EXCLUDED.name, producer = EXCLUDED.producer, country = EXCLUDED.country, " +
            "reg_date = EXCLUDED.reg_date, expiration_date = EXCLUDED.expiration_date, " +
            "unlimited = EXCLUDED.unlimited, imported_at = now()";

    private final MedRegistryRepository registryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String dumpLocation;

    public RegistryImportService(MedRegistryRepository registryRepository,
                                 JdbcTemplate jdbcTemplate,
                                 ResourceLoader resourceLoader,
                                 ObjectMapper objectMapper,
                                 @Value("${registry.kz.dump-location:classpath:registry/rk-mi-registry-full.json}") String dumpLocation) {
        this.registryRepository = registryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.dumpLocation = dumpLocation;
    }

    @Transactional
    public int importFromDump() {
        Resource resource = resourceLoader.getResource(dumpLocation);
        List<RegistryDumpRecord> records;
        try (InputStream is = resource.getInputStream()) {
            records = objectMapper.readValue(is, new TypeReference<List<RegistryDumpRecord>>() {});
        } catch (IOException e) {
            throw new BadRequestException("Не удалось прочитать дамп реестра (" + dumpLocation + "): " + e.getMessage());
        }
        List<RegistryDumpRecord> valid = records.stream()
                .filter(r -> r.getReg() != null && !r.getReg().isBlank())
                .toList();

        jdbcTemplate.batchUpdate(UPSERT_SQL, valid, 500, (ps, r) -> {
            ps.setString(1, r.getReg());
            ps.setString(2, r.getName());
            ps.setString(3, r.getProducer());
            ps.setString(4, r.getCountry());
            ps.setObject(5, parseDate(r.getRegDate()));
            ps.setObject(6, parseDate(r.getExp()));
            ps.setObject(7, r.getUnlimited() != null ? r.getUnlimited() : Boolean.FALSE);
        });
        return valid.size();
    }

    @Transactional
    public int importIfEmpty() {
        if (registryRepository.count() > 0) {
            return 0;
        }
        return importFromDump();
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return LocalDate.parse(iso.substring(0, 10)); // "2026-06-17T00:00:00" -> 2026-06-17
    }
}
```

- [ ] **Step 6: Запустить тест — убедиться, что проходит**

Run: `./gradlew test --tests "com.vladoose.nir.service.RegistryImportServiceTest"`
Expected: PASS (оба теста).

- [ ] **Step 7: Добавить автозагрузку реестра на старте**

Create `src/main/java/com/vladoose/nir/config/RegistryDataInitializer.java`:
```java
package com.vladoose.nir.config;

import com.vladoose.nir.service.RegistryImportService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100) // после базового DataInitializer
public class RegistryDataInitializer implements CommandLineRunner {

    private final RegistryImportService importService;

    public RegistryDataInitializer(RegistryImportService importService) {
        this.importService = importService;
    }

    @Override
    public void run(String... args) {
        int imported = importService.importIfEmpty();
        if (imported > 0) {
            System.out.println("Реестр МИ РК импортирован: " + imported + " записей");
        }
    }
}
```

- [ ] **Step 8: Проверить автозагрузку на реальном старте**

Run (поднять приложение, проверить число записей, погасить):
```bash
./gradlew bootRun > /tmp/ais-boot.log 2>&1 &
sleep 30
PGPASSWORD=admin psql -h localhost -U postgres -d nirdb -c "SELECT count(*) FROM med_registry;"
grep -i "Реестр МИ РК импортирован" /tmp/ais-boot.log || echo "(импорт мог пройти на прошлом старте — таблица уже наполнена)"
kill %1 2>/dev/null
```
Expected: `count` ≈ 14072. На первом старте с пустой таблицей в логе строка «Реестр МИ РК импортирован: …»; на последующих стартах импорт пропускается (`importIfEmpty`), но count остаётся ≈14072 (таблица живучая).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/RegistryDumpRecord.java src/main/java/com/vladoose/nir/service/RegistryImportService.java src/main/java/com/vladoose/nir/config/RegistryDataInitializer.java src/test/resources/registry/test-dump.json src/test/java/com/vladoose/nir/service/RegistryImportServiceTest.java
git commit -m "$(cat <<'EOF'
feat(registry): импорт дампа НЦЭЛС в med_registry (upsert) + автозагрузка на старте

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Регистрация на `med_equipment` — enum, поля, DTO ответа, маппер (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/RegistrationStatus.java`
- Modify: `src/main/java/com/vladoose/nir/entity/MedEquipment.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/EquipmentRegistrationResponse.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/MedEquipmentResponse.java`
- Modify: `src/main/java/com/vladoose/nir/mapper/MedEquipmentMapper.java`
- Test: `src/test/java/com/vladoose/nir/mapper/MedEquipmentMapperRegistrationTest.java`

**Interfaces:**
- Consumes: `MedRegistry` (Task 2).
- Produces:
  - enum `RegistrationStatus { UNCHECKED, REGISTERED, NOT_REGISTERED, NOT_MEDICAL }`.
  - `MedEquipment.getRegistrationStatus()/setRegistrationStatus(RegistrationStatus)`, `getRegistration()/setRegistration(MedRegistry)`, `getRegistrationCheckedAt()/setRegistrationCheckedAt(OffsetDateTime)`.
  - `EquipmentRegistrationResponse` — `status` (String), `vatExempt` (boolean), `regNumber`, `producer`, `country`, `regDate` (LocalDate), `expirationDate` (LocalDate), `unlimited` (Boolean), `checkedAt` (OffsetDateTime).
  - `MedEquipmentResponse.getRegistration() : EquipmentRegistrationResponse`.

- [ ] **Step 1: Написать падающий тест маппера**

Create `src/test/java/com/vladoose/nir/mapper/MedEquipmentMapperRegistrationTest.java`:
```java
package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.response.MedEquipmentResponse;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MedEquipmentMapperRegistrationTest {

    @Autowired
    MedEquipmentMapper mapper;

    @Test
    void registeredEquipment_mapsToVatExemptWithRegistryDetails() {
        MedRegistry reg = MedRegistry.builder()
                .regNumber("РК МИ-TEST").producer("OMRON").country("ЯПОНИЯ").unlimited(true)
                .build();
        MedEquipment e = MedEquipment.builder()
                .name("Тонометр OMRON M2").manufact("OMRON")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registration(reg)
                .build();

        MedEquipmentResponse resp = mapper.toResponse(e);

        assertThat(resp.getRegistration()).isNotNull();
        assertThat(resp.getRegistration().getStatus()).isEqualTo("REGISTERED");
        assertThat(resp.getRegistration().isVatExempt()).isTrue();
        assertThat(resp.getRegistration().getRegNumber()).isEqualTo("РК МИ-TEST");
        assertThat(resp.getRegistration().getProducer()).isEqualTo("OMRON");
    }

    @Test
    void uncheckedEquipment_mapsToNotVatExemptWithoutDetails() {
        MedEquipment e = MedEquipment.builder()
                .name("Стол офисный").manufact("ИКЕА")
                .registrationStatus(RegistrationStatus.UNCHECKED)
                .build();

        MedEquipmentResponse resp = mapper.toResponse(e);

        assertThat(resp.getRegistration().getStatus()).isEqualTo("UNCHECKED");
        assertThat(resp.getRegistration().isVatExempt()).isFalse();
        assertThat(resp.getRegistration().getRegNumber()).isNull();
    }
}
```

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `./gradlew test --tests "com.vladoose.nir.mapper.MedEquipmentMapperRegistrationTest"`
Expected: FAIL — `RegistrationStatus`, поля и `EquipmentRegistrationResponse` ещё не существуют (ошибка компиляции).

- [ ] **Step 3: Создать enum статуса**

Create `src/main/java/com/vladoose/nir/entity/RegistrationStatus.java`:
```java
package com.vladoose.nir.entity;

public enum RegistrationStatus {
    UNCHECKED, REGISTERED, NOT_REGISTERED, NOT_MEDICAL
}
```

- [ ] **Step 4: Добавить поля регистрации в `MedEquipment`**

В `src/main/java/com/vladoose/nir/entity/MedEquipment.java` добавить импорт `import java.time.OffsetDateTime;` и ПОСЛЕ поля `private String spec;` добавить:
```java
    @Enumerated(EnumType.STRING)
    @Column(name = "registration_status", nullable = false, length = 30)
    @Builder.Default
    private RegistrationStatus registrationStatus = RegistrationStatus.UNCHECKED;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "med_registry_reg_number", referencedColumnName = "reg_number")
    private MedRegistry registration;

    @Column(name = "registration_checked_at")
    private OffsetDateTime registrationCheckedAt;
```

- [ ] **Step 5: Создать `EquipmentRegistrationResponse`**

Create `src/main/java/com/vladoose/nir/dto/response/EquipmentRegistrationResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class EquipmentRegistrationResponse {
    private String status;            // RegistrationStatus.name()
    private boolean vatExempt;        // true только для REGISTERED
    private String regNumber;
    private String producer;
    private String country;
    private LocalDate regDate;
    private LocalDate expirationDate;
    private Boolean unlimited;
    private OffsetDateTime checkedAt;
}
```

- [ ] **Step 6: Добавить поле `registration` в `MedEquipmentResponse`**

В `src/main/java/com/vladoose/nir/dto/response/MedEquipmentResponse.java` добавить поле (после `private String spec;`):
```java
    private EquipmentRegistrationResponse registration;
```

- [ ] **Step 7: Дополнить `MedEquipmentMapper` методом `@AfterMapping`**

В `src/main/java/com/vladoose/nir/mapper/MedEquipmentMapper.java` добавить импорты:
```java
import com.vladoose.nir.dto.response.EquipmentRegistrationResponse;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import org.mapstruct.AfterMapping;
```
и внутри интерфейса добавить default-метод:
```java
    @AfterMapping
    default void fillRegistration(MedEquipment entity, @MappingTarget MedEquipmentResponse response) {
        EquipmentRegistrationResponse r = new EquipmentRegistrationResponse();
        RegistrationStatus status = entity.getRegistrationStatus() != null
                ? entity.getRegistrationStatus() : RegistrationStatus.UNCHECKED;
        r.setStatus(status.name());
        r.setVatExempt(status == RegistrationStatus.REGISTERED);
        r.setCheckedAt(entity.getRegistrationCheckedAt());
        MedRegistry reg = entity.getRegistration();
        if (reg != null) {
            r.setRegNumber(reg.getRegNumber());
            r.setProducer(reg.getProducer());
            r.setCountry(reg.getCountry());
            r.setRegDate(reg.getRegDate());
            r.setExpirationDate(reg.getExpirationDate());
            r.setUnlimited(reg.getUnlimited());
        }
        response.setRegistration(r);
    }
```
(`MedEquipment` и `@MappingTarget` уже импортированы в существующем маппере.)

- [ ] **Step 8: Запустить тест — убедиться, что проходит**

Run: `./gradlew test --tests "com.vladoose.nir.mapper.MedEquipmentMapperRegistrationTest"`
Expected: PASS (оба теста).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/vladoose/nir/entity/RegistrationStatus.java src/main/java/com/vladoose/nir/entity/MedEquipment.java src/main/java/com/vladoose/nir/dto/response/EquipmentRegistrationResponse.java src/main/java/com/vladoose/nir/dto/response/MedEquipmentResponse.java src/main/java/com/vladoose/nir/mapper/MedEquipmentMapper.java src/test/java/com/vladoose/nir/mapper/MedEquipmentMapperRegistrationTest.java
git commit -m "$(cat <<'EOF'
feat(registry): статус регистрации + производный НДС-флаг на med_equipment

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `RegistryMatchService` — кандидаты, привязка/пометка, сверка (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/RegistryCandidateResponse.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/ReconciliationRowResponse.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/RegistrationAction.java`
- Create: `src/main/java/com/vladoose/nir/service/RegistryMatchService.java`
- Test: `src/test/java/com/vladoose/nir/service/RegistryMatchServiceTest.java`

**Interfaces:**
- Consumes: `MedRegistryRepository` (Task 2), `MedEquipmentRepository`, `MedEquipment` registration-поля (Task 4).
- Produces:
  - enum `RegistrationAction { CONFIRM, NOT_REGISTERED, NOT_MEDICAL, RESET }`.
  - `RegistryCandidateResponse` — `regNumber`, `name`, `producer`, `country`, `regDate` (LocalDate), `expirationDate` (LocalDate), `unlimited` (Boolean), `score` (Double).
  - `ReconciliationRowResponse` — `equipmentId` (Long), `equipmentName`, `manufact`, `equipTypeName`, `status` (String), `vatExempt` (boolean), `currentRegNumber` (String), `candidates` (List<RegistryCandidateResponse>).
  - `RegistryMatchService.findCandidates(String name, String manufact, int limit) : List<RegistryCandidateResponse>`; `candidatesForEquipment(Long id, int limit) : List<RegistryCandidateResponse>`; `applyAction(Long id, RegistrationAction action, String regNumber) : MedEquipment`; `buildReconciliation(String statusFilter, int candidatesPerRow) : List<ReconciliationRowResponse>`.

- [ ] **Step 1: Написать падающий тест сервиса**

Create `src/test/java/com/vladoose/nir/service/RegistryMatchServiceTest.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.RegistrationAction;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RegistryMatchServiceTest {

    @Autowired RegistryMatchService service;
    @Autowired MedRegistryRepository registryRepository;
    @Autowired MedEquipmentRepository equipmentRepository;

    private MedEquipment newEquipment() {
        return equipmentRepository.save(MedEquipment.builder()
                .name("Тонометр ТЕСТМАТЧ УникальныйZZ").manufact("ZZMATCHVENDOR-Uniq")
                .registrationStatus(RegistrationStatus.UNCHECKED)
                .build());
    }

    @Test
    void confirm_setsRegisteredAndLinksRegistry() {
        registryRepository.save(MedRegistry.builder()
                .regNumber("ZZMATCH-RU-1").name("Тонометр ТЕСТМАТЧ УникальныйZZ")
                .producer("ZZMATCHVENDOR-Uniq").country("ЯПОНИЯ").unlimited(true).build());
        MedEquipment e = newEquipment();

        MedEquipment updated = service.applyAction(e.getId(), RegistrationAction.CONFIRM, "ZZMATCH-RU-1");

        assertThat(updated.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(updated.getRegistration()).isNotNull();
        assertThat(updated.getRegistration().getRegNumber()).isEqualTo("ZZMATCH-RU-1");
        assertThat(updated.getRegistrationCheckedAt()).isNotNull();
    }

    @Test
    void confirm_withUnknownRegNumber_throwsBadRequest() {
        MedEquipment e = newEquipment();
        assertThatThrownBy(() -> service.applyAction(e.getId(), RegistrationAction.CONFIRM, "НЕТ-ТАКОГО"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void markNotMedical_thenReset_changesStatus() {
        MedEquipment e = newEquipment();

        MedEquipment notMed = service.applyAction(e.getId(), RegistrationAction.NOT_MEDICAL, null);
        assertThat(notMed.getRegistrationStatus()).isEqualTo(RegistrationStatus.NOT_MEDICAL);
        assertThat(notMed.getRegistration()).isNull();

        MedEquipment reset = service.applyAction(e.getId(), RegistrationAction.RESET, null);
        assertThat(reset.getRegistrationStatus()).isEqualTo(RegistrationStatus.UNCHECKED);
        assertThat(reset.getRegistrationCheckedAt()).isNull();
    }

    @Test
    void candidatesForEquipment_returnsTrgmMatch() {
        registryRepository.save(MedRegistry.builder()
                .regNumber("ZZMATCH-RU-2").name("Тонометр ТЕСТМАТЧ УникальныйZZ")
                .producer("ZZMATCHVENDOR-Uniq").country("ЯПОНИЯ").unlimited(true).build());
        MedEquipment e = newEquipment();

        var candidates = service.candidatesForEquipment(e.getId(), 5);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).getRegNumber()).isEqualTo("ZZMATCH-RU-2");
        assertThat(candidates.get(0).getScore()).isGreaterThan(0.0);
    }
}
```

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `./gradlew test --tests "com.vladoose.nir.service.RegistryMatchServiceTest"`
Expected: FAIL — `RegistryMatchService`, `RegistrationAction`, DTO не существуют (ошибка компиляции).

- [ ] **Step 3: Создать enum действия и DTO**

Create `src/main/java/com/vladoose/nir/dto/request/RegistrationAction.java`:
```java
package com.vladoose.nir.dto.request;

public enum RegistrationAction {
    CONFIRM, NOT_REGISTERED, NOT_MEDICAL, RESET
}
```

Create `src/main/java/com/vladoose/nir/dto/response/RegistryCandidateResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.LocalDate;

@Data
public class RegistryCandidateResponse {
    private String regNumber;
    private String name;
    private String producer;
    private String country;
    private LocalDate regDate;
    private LocalDate expirationDate;
    private Boolean unlimited;
    private Double score;
}
```

Create `src/main/java/com/vladoose/nir/dto/response/ReconciliationRowResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class ReconciliationRowResponse {
    private Long equipmentId;
    private String equipmentName;
    private String manufact;
    private String equipTypeName;
    private String status;           // RegistrationStatus.name()
    private boolean vatExempt;
    private String currentRegNumber; // привязанный № РУ, если есть
    private List<RegistryCandidateResponse> candidates;
}
```

- [ ] **Step 4: Создать `RegistryMatchService`**

Create `src/main/java/com/vladoose/nir/service/RegistryMatchService.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.RegistrationAction;
import com.vladoose.nir.dto.response.ReconciliationRowResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class RegistryMatchService {

    private final MedRegistryRepository registryRepository;
    private final MedEquipmentRepository equipmentRepository;

    public RegistryMatchService(MedRegistryRepository registryRepository,
                                MedEquipmentRepository equipmentRepository) {
        this.registryRepository = registryRepository;
        this.equipmentRepository = equipmentRepository;
    }

    /** Переиспользуемый примитив: (наименование, производитель) -> кандидаты реестра. */
    public List<RegistryCandidateResponse> findCandidates(String name, String manufact, int limit) {
        String n = name != null ? name : "";
        String m = manufact != null ? manufact : "";
        if (n.isBlank() && m.isBlank()) {
            return List.of();
        }
        return registryRepository.findCandidates(n, m, limit).stream()
                .map(row -> {
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
                })
                .toList();
    }

    public List<RegistryCandidateResponse> candidatesForEquipment(Long equipmentId, int limit) {
        MedEquipment e = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new NotFoundException("Оборудование не найдено: id=" + equipmentId));
        return findCandidates(e.getName(), e.getManufact(), limit);
    }

    @Transactional
    public MedEquipment applyAction(Long equipmentId, RegistrationAction action, String regNumber) {
        MedEquipment e = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new NotFoundException("Оборудование не найдено: id=" + equipmentId));
        switch (action) {
            case CONFIRM -> {
                if (regNumber == null || regNumber.isBlank()) {
                    throw new BadRequestException("Для подтверждения требуется regNumber");
                }
                MedRegistry reg = registryRepository.findByRegNumber(regNumber)
                        .orElseThrow(() -> new BadRequestException("РУ не найдено в реестре: " + regNumber));
                e.setRegistrationStatus(RegistrationStatus.REGISTERED);
                e.setRegistration(reg);
                e.setRegistrationCheckedAt(OffsetDateTime.now());
            }
            case NOT_REGISTERED -> {
                e.setRegistrationStatus(RegistrationStatus.NOT_REGISTERED);
                e.setRegistration(null);
                e.setRegistrationCheckedAt(OffsetDateTime.now());
            }
            case NOT_MEDICAL -> {
                e.setRegistrationStatus(RegistrationStatus.NOT_MEDICAL);
                e.setRegistration(null);
                e.setRegistrationCheckedAt(OffsetDateTime.now());
            }
            case RESET -> {
                e.setRegistrationStatus(RegistrationStatus.UNCHECKED);
                e.setRegistration(null);
                e.setRegistrationCheckedAt(null);
            }
        }
        return equipmentRepository.save(e);
    }

    public List<ReconciliationRowResponse> buildReconciliation(String statusFilter, int candidatesPerRow) {
        List<ReconciliationRowResponse> rows = new ArrayList<>();
        for (MedEquipment e : equipmentRepository.findAll()) {
            RegistrationStatus status = e.getRegistrationStatus() != null
                    ? e.getRegistrationStatus() : RegistrationStatus.UNCHECKED;
            if (statusFilter != null && !statusFilter.isBlank()
                    && !status.name().equalsIgnoreCase(statusFilter)) {
                continue;
            }
            ReconciliationRowResponse row = new ReconciliationRowResponse();
            row.setEquipmentId(e.getId());
            row.setEquipmentName(e.getName());
            row.setManufact(e.getManufact());
            row.setEquipTypeName(e.getEquipmentType() != null ? e.getEquipmentType().getName() : null);
            row.setStatus(status.name());
            row.setVatExempt(status == RegistrationStatus.REGISTERED);
            row.setCurrentRegNumber(e.getRegistration() != null ? e.getRegistration().getRegNumber() : null);
            row.setCandidates(findCandidates(e.getName(), e.getManufact(), candidatesPerRow));
            rows.add(row);
        }
        return rows;
    }
}
```

- [ ] **Step 5: Запустить тест — убедиться, что проходит**

Run: `./gradlew test --tests "com.vladoose.nir.service.RegistryMatchServiceTest"`
Expected: PASS (все 4 теста).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/request/RegistrationAction.java src/main/java/com/vladoose/nir/dto/response/RegistryCandidateResponse.java src/main/java/com/vladoose/nir/dto/response/ReconciliationRowResponse.java src/main/java/com/vladoose/nir/service/RegistryMatchService.java src/test/java/com/vladoose/nir/service/RegistryMatchServiceTest.java
git commit -m "$(cat <<'EOF'
feat(registry): RegistryMatchService — кандидаты, привязка/пометка, сверка

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: REST API — `RegistryController` + действие регистрации на `MedEquipmentController`

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/RegistrationActionRequest.java`
- Create: `src/main/java/com/vladoose/nir/controller/RegistryController.java`
- Modify: `src/main/java/com/vladoose/nir/controller/MedEquipmentController.java`

**Interfaces:**
- Consumes: `RegistryMatchService`, `RegistryImportService` (Tasks 3, 5), `MedEquipmentMapper`.
- Produces (HTTP):
  - `GET /api/registry/candidates?name=&manufact=&limit=` → `List<RegistryCandidateResponse>`
  - `GET /api/registry/candidates/equipment/{id}?limit=` → `List<RegistryCandidateResponse>`
  - `GET /api/registry/reconciliation?status=&candidates=` → `List<ReconciliationRowResponse>`
  - `GET /api/registry/search?q=&limit=` → `List<RegistryCandidateResponse>`
  - `POST /api/registry/refresh` (ADMIN) → `{ "imported": <int> }`
  - `POST /api/equipment/{id}/registration` (ADMIN) body `{action, regNumber?}` → `MedEquipmentResponse`

- [ ] **Step 1: Создать `RegistrationActionRequest`**

Create `src/main/java/com/vladoose/nir/dto/request/RegistrationActionRequest.java`:
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RegistrationActionRequest {
    @NotNull(message = "action обязателен")
    private RegistrationAction action;
    private String regNumber; // обязателен только для CONFIRM
}
```

- [ ] **Step 2: Создать `RegistryController`**

Create `src/main/java/com/vladoose/nir/controller/RegistryController.java`:
```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.ReconciliationRowResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.service.RegistryImportService;
import com.vladoose.nir.service.RegistryMatchService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/registry")
public class RegistryController {

    private final RegistryMatchService matchService;
    private final RegistryImportService importService;

    public RegistryController(RegistryMatchService matchService, RegistryImportService importService) {
        this.matchService = matchService;
        this.importService = importService;
    }

    @GetMapping("/candidates")
    public List<RegistryCandidateResponse> candidates(@RequestParam(required = false) String name,
                                                      @RequestParam(required = false) String manufact,
                                                      @RequestParam(defaultValue = "5") int limit) {
        return matchService.findCandidates(name, manufact, limit);
    }

    @GetMapping("/candidates/equipment/{id}")
    public List<RegistryCandidateResponse> candidatesForEquipment(@PathVariable Long id,
                                                                  @RequestParam(defaultValue = "5") int limit) {
        return matchService.candidatesForEquipment(id, limit);
    }

    @GetMapping("/reconciliation")
    public List<ReconciliationRowResponse> reconciliation(@RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "5") int candidates) {
        return matchService.buildReconciliation(status, candidates);
    }

    @GetMapping("/search")
    public List<RegistryCandidateResponse> search(@RequestParam String q,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return matchService.findCandidates(q, q, limit);
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> refresh() {
        int imported = importService.importFromDump();
        return Map.of("imported", imported);
    }
}
```

- [ ] **Step 3: Добавить действие регистрации в `MedEquipmentController`**

В `src/main/java/com/vladoose/nir/controller/MedEquipmentController.java` (контроллер уже инжектит `MedEquipmentService service`, `MedEquipmentMapper mapper` и др. через конструктор — добавляем ещё одну зависимость):
1. Импорты:
```java
import com.vladoose.nir.dto.request.RegistrationActionRequest;
import com.vladoose.nir.service.RegistryMatchService;
```
2. Добавить поле и параметр конструктора `RegistryMatchService registryMatchService` (присвоить `this.registryMatchService = registryMatchService;`).
3. Добавить метод:
```java
    @PostMapping("/{id}/registration")
    @PreAuthorize("hasRole('ADMIN')")
    public MedEquipmentResponse setRegistration(@PathVariable Long id,
                                                @Valid @RequestBody RegistrationActionRequest request) {
        return mapper.toResponse(
                registryMatchService.applyAction(id, request.getAction(), request.getRegNumber()));
    }
```

- [ ] **Step 4: Скомпилировать и прогнать весь backend-тест-сьют**

Run: `./gradlew test`
Expected: PASS — компиляция успешна, все тесты (включая прежние) зелёные.

- [ ] **Step 5: Ручная проверка эндпоинтов (приложение + curl)**

Запустить приложение в фоне и проверить (логин нужен для авторизованных эндпоинтов — куки/сессия от `/api/auth`):
```bash
./gradlew bootRun &
sleep 25
# логин (admin/admin) с сохранением сессии
curl -s -c /tmp/aiscookies -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' -o /dev/null -w "login:%{http_code}\n"
# сверка
curl -s -b /tmp/aiscookies "http://localhost:8080/api/registry/reconciliation?status=UNCHECKED&candidates=3" | head -c 800; echo
# кандидаты для оборудования id=1
curl -s -b /tmp/aiscookies "http://localhost:8080/api/registry/candidates/equipment/1?limit=3" | head -c 800; echo
kill %1
```
Expected: `login:200`; reconciliation возвращает массив строк каталога с полем `candidates`; кандидаты для id=1 — массив с `regNumber`/`score`. (Если у оборудования латинский производитель типа `Mindray`/`Philips` — ожидаемы реальные совпадения из реестра.)

> Примечание: эндпоинты под `/api/**` требуют аутентификации (сессия от `/api/auth/login`). Если cookie-сессия через curl не подхватывается, отложить функциональную проверку до UI (Task 7, Step 5) — там аутентификация работает штатно через `authInterceptor`. Главный гейт этой задачи — зелёный `./gradlew test` (Step 4).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/request/RegistrationActionRequest.java src/main/java/com/vladoose/nir/controller/RegistryController.java src/main/java/com/vladoose/nir/controller/MedEquipmentController.java
git commit -m "$(cat <<'EOF'
feat(registry): REST API сверки реестра + действие привязки регистрации

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Фронтенд — методы ApiService, страница «Сверка с реестром», маршрут, навигация

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Create: `frontend/src/app/pages/registry-reconciliation/registry-reconciliation.component.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/layout/layout.component.ts`

**Interfaces:**
- Consumes: backend `/api/registry/*` и `/api/equipment/{id}/registration` (Task 6).
- Produces: маршрут `/registry-reconciliation`; методы `ApiService.getRegistryReconciliation/getRegistryCandidatesForEquipment/searchRegistry/setEquipmentRegistration/refreshRegistry`.

- [ ] **Step 1: Добавить методы в `ApiService`**

В `frontend/src/app/services/api.service.ts`, в класс `ApiService`, добавить:
```typescript
  // === Реестр / сверка ===
  getRegistryReconciliation(status?: string, candidates: number = 5): Observable<any[]> {
    let url = `${this.base}/registry/reconciliation?candidates=${candidates}`;
    if (status) { url += `&status=${encodeURIComponent(status)}`; }
    return this.http.get<any[]>(url);
  }

  getRegistryCandidatesForEquipment(equipmentId: number, limit: number = 5): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/registry/candidates/equipment/${equipmentId}?limit=${limit}`);
  }

  searchRegistry(q: string, limit: number = 20): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/registry/search?q=${encodeURIComponent(q)}&limit=${limit}`);
  }

  setEquipmentRegistration(equipmentId: number, action: string, regNumber?: string): Observable<any> {
    return this.http.post(`${this.base}/equipment/${equipmentId}/registration`, { action, regNumber });
  }

  refreshRegistry(): Observable<any> {
    return this.http.post(`${this.base}/registry/refresh`, {});
  }
```

- [ ] **Step 2: Создать компонент страницы сверки**

Create `frontend/src/app/pages/registry-reconciliation/registry-reconciliation.component.ts`:
```typescript
import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

@Component({
  selector: 'app-registry-reconciliation',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, DecimalPipe],
  template: `
    <div class="page">
      <header class="page-head">
        <div>
          <h1>Сверка с реестром РК</h1>
          <p class="sub">Привязка позиций каталога к № РУ (НЦЭЛС). Зелёный = зарегистрировано (НДС-льгота).</p>
        </div>
        <button class="btn-refresh" (click)="onRefresh()" [disabled]="refreshing">
          ↻ {{ refreshing ? 'Обновляю…' : 'Обновить реестр' }}
        </button>
      </header>

      <div class="filters">
        <label>Статус:</label>
        <select [(ngModel)]="statusFilter" (change)="load()">
          <option value="">Все</option>
          <option value="UNCHECKED">Не проверено</option>
          <option value="REGISTERED">Зарегистрировано</option>
          <option value="NOT_REGISTERED">Не зарегистрировано</option>
          <option value="NOT_MEDICAL">Не медизделие</option>
        </select>
        <span class="count" *ngIf="!loading">{{ rows.length }} позиций</span>
      </div>

      <div class="loading" *ngIf="loading">Загрузка…</div>

      <table *ngIf="!loading && rows.length">
        <thead>
          <tr><th></th><th>Позиция каталога</th><th>Производитель</th><th>Статус</th><th>Топ-кандидат</th></tr>
        </thead>
        <tbody>
          <ng-container *ngFor="let r of rows">
            <tr class="row" [class.focused]="r.equipmentId === focusId" (click)="toggle(r)">
              <td class="chev">{{ expanded[r.equipmentId] ? '▾' : '▸' }}</td>
              <td class="name">{{ r.equipmentName }}</td>
              <td>{{ r.manufact }}</td>
              <td>
                <span class="badge" [class]="'b-' + r.status">{{ statusLabel(r.status) }}</span>
                <span class="vat" *ngIf="r.status === 'REGISTERED'">НДС-льгота</span>
                <span class="vat vat-no" *ngIf="r.status === 'NOT_REGISTERED' || r.status === 'NOT_MEDICAL'">НДС 12%</span>
              </td>
              <td class="top">
                <span *ngIf="r.candidates?.length">{{ r.candidates[0].producer }} · {{ r.candidates[0].score | number:'1.2-2' }}</span>
                <span class="muted" *ngIf="!r.candidates?.length">нет кандидатов</span>
              </td>
            </tr>
            <tr class="detail" *ngIf="expanded[r.equipmentId]">
              <td colspan="5">
                <div class="cands" *ngIf="r.candidates?.length">
                  <div class="cand" *ngFor="let c of r.candidates" [class.current]="c.regNumber === r.currentRegNumber">
                    <div class="cand-main">
                      <div class="cand-name">{{ c.name }}</div>
                      <div class="cand-meta">{{ c.producer }} · {{ c.country }} · {{ c.regNumber }}
                        <span *ngIf="c.unlimited">· бессрочно</span>
                        <span *ngIf="!c.unlimited && c.expirationDate">· до {{ c.expirationDate }}</span>
                      </div>
                      <div class="bar"><div class="bar-fill" [style.width.%]="(c.score || 0) * 100"></div></div>
                    </div>
                    <button class="btn-confirm" (click)="confirm(r, c.regNumber); $event.stopPropagation()">✓ Подтвердить</button>
                  </div>
                </div>
                <div class="actions">
                  <button class="btn-sm" (click)="mark(r, 'NOT_REGISTERED'); $event.stopPropagation()">Нет в реестре</button>
                  <button class="btn-sm" (click)="mark(r, 'NOT_MEDICAL'); $event.stopPropagation()">Не медизделие</button>
                  <button class="btn-sm" (click)="mark(r, 'RESET'); $event.stopPropagation()">Сбросить</button>
                </div>
              </td>
            </tr>
          </ng-container>
        </tbody>
      </table>

      <div class="empty" *ngIf="!loading && !rows.length">Нет позиций для выбранного фильтра.</div>
    </div>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; }
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    h1 { font-size: 22px; color: #111827; }
    .sub { color: #6b7280; font-size: 13px; margin-top: 4px; }
    .btn-refresh { display: inline-flex; align-items: center; gap: 6px; background: #1a56db; color: #fff; border: none; padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; }
    .btn-refresh:disabled { opacity: .6; cursor: default; }
    .filters { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; font-size: 13px; color: #374151; }
    .filters select { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; }
    .count { color: #6b7280; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    thead th { text-align: left; padding: 8px 10px; color: #6b7280; border-bottom: 1px solid #e5e7eb; font-weight: 600; }
    .row { cursor: pointer; border-bottom: 1px solid #f3f4f6; }
    .row:hover { background: #f9fafb; }
    .row.focused { background: #eff6ff; }
    .row td { padding: 9px 10px; vertical-align: middle; }
    .chev { width: 24px; color: #9ca3af; }
    .name { font-weight: 500; color: #111827; }
    .badge { padding: 2px 9px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .b-UNCHECKED { background: #e5e7eb; color: #374151; }
    .b-REGISTERED { background: #d1fae5; color: #065f46; }
    .b-NOT_REGISTERED { background: #fee2e2; color: #991b1b; }
    .b-NOT_MEDICAL { background: #fef3c7; color: #92400e; }
    .vat { margin-left: 8px; font-size: 11px; color: #065f46; font-weight: 600; }
    .vat-no { color: #92400e; }
    .top .muted { color: #9ca3af; }
    .detail td { background: #f9fafb; padding: 12px 16px; }
    .cands { display: flex; flex-direction: column; gap: 8px; margin-bottom: 10px; }
    .cand { display: flex; align-items: center; justify-content: space-between; gap: 12px; background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px 12px; }
    .cand.current { border-color: #10b981; box-shadow: 0 0 0 1px #10b981; }
    .cand-main { flex: 1; }
    .cand-name { font-weight: 500; color: #111827; }
    .cand-meta { color: #6b7280; font-size: 12px; margin: 2px 0 5px; }
    .bar { height: 5px; background: #e5e7eb; border-radius: 3px; overflow: hidden; max-width: 280px; }
    .bar-fill { height: 100%; background: #1a56db; }
    .btn-confirm { background: #10b981; color: #fff; border: none; padding: 6px 12px; border-radius: 6px; cursor: pointer; font-size: 12px; white-space: nowrap; }
    .actions { display: flex; gap: 8px; }
    .btn-sm { background: #fff; border: 1px solid #d1d5db; color: #374151; padding: 5px 11px; border-radius: 6px; cursor: pointer; font-size: 12px; }
    .btn-sm:hover { background: #f3f4f6; }
    .loading, .empty { padding: 30px; text-align: center; color: #9ca3af; }
  `]
})
export class RegistryReconciliationComponent {
  rows: any[] = [];
  loading = false;
  refreshing = false;
  statusFilter = 'UNCHECKED';
  expanded: Record<number, boolean> = {};
  focusId: number | null = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private route: ActivatedRoute, private notify: NotificationService) {
    this.route.queryParams.subscribe(p => {
      if (p['focus']) {
        this.focusId = +p['focus'];
        this.statusFilter = '';
        this.expanded[this.focusId] = true;
      }
    });
    this.load();
  }

  load() {
    this.loading = true;
    this.api.getRegistryReconciliation(this.statusFilter || undefined, 5).subscribe({
      next: data => { this.rows = data; this.loading = false; this.cdr.detectChanges(); },
      error: err => { this.loading = false; this.notify.error('Ошибка загрузки сверки: ' + (err.error?.message || err.message)); this.cdr.detectChanges(); }
    });
  }

  toggle(r: any) { this.expanded[r.equipmentId] = !this.expanded[r.equipmentId]; }

  statusLabel(s: string): string {
    return { UNCHECKED: 'Не проверено', REGISTERED: 'Зарегистрировано', NOT_REGISTERED: 'Не зарегистрировано', NOT_MEDICAL: 'Не медизделие' }[s] || s;
  }

  confirm(r: any, regNumber: string) {
    this.api.setEquipmentRegistration(r.equipmentId, 'CONFIRM', regNumber).subscribe({
      next: () => { this.notify.success('Привязка сохранена: ' + regNumber); this.load(); },
      error: err => this.notify.error(err.error?.message || 'Ошибка привязки')
    });
  }

  mark(r: any, action: string) {
    this.api.setEquipmentRegistration(r.equipmentId, action).subscribe({
      next: () => { this.notify.success('Статус обновлён'); this.load(); },
      error: err => this.notify.error(err.error?.message || 'Ошибка')
    });
  }

  onRefresh() {
    this.refreshing = true;
    this.api.refreshRegistry().subscribe({
      next: (res: any) => { this.refreshing = false; this.notify.success('Реестр обновлён: ' + res.imported + ' записей'); this.cdr.detectChanges(); },
      error: err => { this.refreshing = false; this.notify.error(err.error?.message || 'Ошибка обновления'); this.cdr.detectChanges(); }
    });
  }
}
```
> Примечание: компонент намеренно без lucide-иконок (юникод ▸/▾ и ↻) — самодостаточен, без риска по иконкам. `NotificationService` методы — `success(msg)`/`error(msg)` (подтверждено).

- [ ] **Step 3: Зарегистрировать маршрут**

В `frontend/src/app/app.routes.ts` добавить импорт и маршрут в массив `children` (рядом с `equipment`):
```typescript
import { RegistryReconciliationComponent } from './pages/registry-reconciliation/registry-reconciliation.component';
// ...
      { path: 'registry-reconciliation', component: RegistryReconciliationComponent },
```

- [ ] **Step 4: Добавить пункт навигации**

В `frontend/src/app/layout/layout.component.ts`, в сайдбаре, в группе «Каталог» (рядом со ссылкой на `/equipment`) добавить:
```html
    <a routerLink="/registry-reconciliation" routerLinkActive="active">
      <svg lucideIcon="badge-check" [size]="16"></svg> Сверка с реестром
    </a>
```
(Имя иконки взять из набора, уже используемого проектом; если `badge-check` недоступна — `check-circle`.)

- [ ] **Step 5: Собрать фронт и проверить вручную**

Run:
```bash
cd frontend && npm run build
```
Expected: сборка без ошибок. Затем запустить backend (`./gradlew bootRun`) и фронт (`cd frontend && npm start`), открыть `http://localhost:4200`, залогиниться (admin/admin), перейти «Каталог → Сверка с реестром». Проверить: таблица грузится, фильтр статуса работает, разворот строки показывает кандидатов со score-барами, «✓ Подтвердить» меняет статус на «Зарегистрировано» + бейдж «НДС-льгота», «Обновить реестр» показывает число записей.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/pages/registry-reconciliation/ frontend/src/app/app.routes.ts frontend/src/app/layout/layout.component.ts
git commit -m "$(cat <<'EOF'
feat(registry): фронт — страница «Сверка с реестром» + методы ApiService

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Фронтенд — инлайн-бейдж «Регистрация РК» в карточке оборудования

**Files:**
- Modify: `frontend/src/app/components/equipment-detail-modal/equipment-detail-modal.component.ts`

**Interfaces:**
- Consumes: поле `registration` в объекте оборудования (приходит из `MedEquipmentResponse`, Task 4) — `{status, vatExempt, regNumber, producer, country, regDate, expirationDate, unlimited, checkedAt}`.

- [ ] **Step 1: Добавить секцию «Регистрация РК» в карточку**

В `frontend/src/app/components/equipment-detail-modal/equipment-detail-modal.component.ts`, в шаблоне внутри `<div class="body">`, сразу ПОСЛЕ секции «Характеристики» (после её закрывающего `</section>`, перед секцией «Потенциальные поставщики») добавить блок:
```html
    <section class="section" *ngIf="equipment?.registration as reg">
      <h3 class="section-title">Регистрация РК</h3>
      <div class="reg-row">
        <span class="reg-label">Статус</span>
        <span class="reg-badge" [class]="'rb-' + reg.status">{{ regStatusLabel(reg.status) }}</span>
        <span class="reg-vat" *ngIf="reg.status === 'REGISTERED'">НДС-льгота</span>
        <span class="reg-vat reg-vat-no" *ngIf="reg.status === 'NOT_REGISTERED' || reg.status === 'NOT_MEDICAL'">облагается НДС 12%</span>
      </div>
      <div class="reg-row" *ngIf="reg.regNumber"><span class="reg-label">№ РУ</span><span>{{ reg.regNumber }}</span></div>
      <div class="reg-row" *ngIf="reg.producer"><span class="reg-label">Держатель</span><span>{{ reg.producer }}</span></div>
      <div class="reg-row" *ngIf="reg.country"><span class="reg-label">Страна</span><span>{{ reg.country }}</span></div>
      <div class="reg-row" *ngIf="reg.regNumber"><span class="reg-label">Срок</span>
        <span *ngIf="reg.unlimited">бессрочно</span>
        <span *ngIf="!reg.unlimited && reg.expirationDate">до {{ reg.expirationDate }}</span>
        <span *ngIf="!reg.unlimited && !reg.expirationDate">—</span>
      </div>
      <a class="reg-link" [routerLink]="['/registry-reconciliation']" [queryParams]="{ focus: equipment.id }" (click)="onClose()">
        Изменить привязку →
      </a>
    </section>
```

- [ ] **Step 2: Добавить хелпер, импорт RouterLink и стили**

В том же компоненте:
1. Добавить импорт `import { RouterLink } from '@angular/router';` и в `imports` декоратора изменить `imports: [NgIf, NgFor, NgClass]` → `imports: [NgIf, NgFor, NgClass, RouterLink]`.
2. В класс добавить метод:
```typescript
  regStatusLabel(s: string): string {
    return { UNCHECKED: 'Не проверено', REGISTERED: 'Зарегистрировано', NOT_REGISTERED: 'Не зарегистрировано', NOT_MEDICAL: 'Не медизделие' }[s] || s;
  }
```
3. В `styles: []` добавить:
```scss
    .reg-row { display: flex; align-items: center; gap: 10px; padding: 4px 0; font-size: 13px; }
    .reg-label { color: #6b7280; min-width: 90px; }
    .reg-badge { padding: 2px 9px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .rb-UNCHECKED { background: #e5e7eb; color: #374151; }
    .rb-REGISTERED { background: #d1fae5; color: #065f46; }
    .rb-NOT_REGISTERED { background: #fee2e2; color: #991b1b; }
    .rb-NOT_MEDICAL { background: #fef3c7; color: #92400e; }
    .reg-vat { font-size: 11px; color: #065f46; font-weight: 600; }
    .reg-vat-no { color: #92400e; }
    .reg-link { display: inline-block; margin-top: 8px; color: #1a56db; font-size: 13px; cursor: pointer; text-decoration: none; }
    .reg-link:hover { text-decoration: underline; }
```
> Подтверждено по коду: поле — `@Input() equipment: any`, закрытие — `onClose()` (эмитит `close`). Объект `equipment` приходит из `MedEquipmentResponse` и уже содержит `registration` (Task 4), отдельный запрос не нужен.

- [ ] **Step 3: Собрать и проверить вручную**

Run: `cd frontend && npm run build`
Expected: сборка без ошибок. Запустить backend+фронт, открыть карточку оборудования: для непривязанной позиции — бейдж «Не проверено»; после подтверждения на экране сверки — в карточке «Зарегистрировано» + «НДС-льгота» + реквизиты РУ; ссылка «Изменить привязку →» ведёт на сверку с раскрытой строкой этой позиции.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/components/equipment-detail-modal/equipment-detail-modal.component.ts
git commit -m "$(cat <<'EOF'
feat(registry): инлайн-бейдж «Регистрация РК» в карточке оборудования

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Финальная проверка (после всех задач)

- [ ] `./gradlew test` — весь backend-сьют зелёный.
- [ ] `cd frontend && npm run build` — фронт собирается.
- [ ] E2E-смоук: старт приложения → реестр импортирован (`SELECT count(*) FROM med_registry` ≈ 14072) → «Сверка с реестром»: подтверждение известной позиции (латинский производитель, напр. `Mindray`/`Philips`) даёт статус `REGISTERED` + «НДС-льгота» → в карточке оборудования виден тот же статус и реквизиты РУ.
