# Импорт частной заявки из Excel (Блок D1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Загрузить Excel-файл клиники → парсер на правилах размечает колонки (со словарём, обучаемым от правок оператора) → превью → создать частную заявку через существующий `createFromLines`.

**Architecture:** Парсер за интерфейсом `LineExtractor` (`RuleBasedLineExtractor` читает `.xlsx`/`.xls` через POI). Словарь синонимов заголовков — таблица `header_synonym` (глобальная), пополняется при commit'е из правок оператора. `PrivateRequestImportService` делает preview (грид с разметкой) и commit (апсерт синонимов + `createFromLines`). Два эндпоинта на существующем `PrivateRequestController`. Фронт — флоу импорта в `PrivateRequestsComponent` (кнопка → файл → грид-превью с per-column select → создать). Спека: `docs/superpowers/specs/2026-06-25-file-import-private-requests-design.md`.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA/Hibernate 6, Apache POI 5.2.5 (poi-ooxml, `poi` транзитивно), Lombok, PostgreSQL; Angular 21 (standalone, SCSS).

## Global Constraints

- Java **17**, Spring Boot **3.5.6**, Gradle **8.14** — не менять.
- Backend `com.vladoose.nir`. Entity на Lombok (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`), PK `BIGSERIAL` через `@GeneratedValue(strategy = GenerationType.IDENTITY)`; сервисы `@Service` + constructor injection без `@Autowired`, `@Transactional` на записи; контроллеры `@RestController`, «голые» DTO; записи под `@PreAuthorize("hasRole('ADMIN')")` (method security включена — `@EnableMethodSecurity`).
- БД PostgreSQL `localhost:5432/nirdb` (UTF-8). `ddl-auto: none`, `schema.sql` пересоздаёт таблицы, сид `data.sql`. **Редактировать `src/main/resources/schema.sql` и `data.sql`** (не `build/...`). Новую таблицу — в DROP-список и CREATE.
- **КРИТИЧНО (среда):** Bash-sandbox блокирует :5432 → ЛЮБЫЕ `./gradlew`/DB-команды с `dangerouslyDisableSandbox: true`. `psql` = `/Library/PostgreSQL/17/bin/psql`, `PGPASSWORD=admin`, БД `nirdb`.
- **Известные PRE-EXISTING падения, НЕ наши:** `ApplyAutoFillServiceTest` (2). `./gradlew test` = BUILD FAILED ровно с этими 2 → норма; гейт: «компилируется + только эти 2».
- Фронт: Angular standalone, инлайн-шаблон + `styles: []`, `ApiService` (база `/api`, прокси :8080), `NotificationService.success(string)/error(string)`. `marketInterceptor` сам вешает `X-Market` на все запросы (включая `FormData`). Фронт-тестов нет → гейт = `npm run build` + ручная проверка.
- Каждый commit заканчивать: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Ветка `feat/file-import-private-requests` (содержит спеку). Рабочее дерево содержит несвязанный WIP пользователя — НЕ трогать; коммитить только файлы своей задачи.
- **Переиспользуемые сущности (готовы, блок B):** `PrivateRequestCreate { Long clientFacilityId; String note; List<Line> lines; }`, `PrivateRequestCreate.Line { String name; String manufact; Integer quantity; }`; `PrivateRequestService.createFromLines(PrivateRequestCreate) : Tender`; `Tender.getSource()` (`Source.PRIVATE_REQUEST`); `PrivateRequestService.linesWithRegistration(Long) : List<PrivateRequestLineResponse>` (поле `manufact`).

---

### Task 1: Backend — модель синонимов (`header_synonym` + enum + схема + сид) (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/LineField.java`
- Create: `src/main/java/com/vladoose/nir/entity/HeaderSynonym.java`
- Create: `src/main/java/com/vladoose/nir/repository/HeaderSynonymRepository.java`
- Modify: `src/main/resources/schema.sql`, `src/main/resources/data.sql`
- Test: `src/test/java/com/vladoose/nir/imports/HeaderSynonymTest.java`

**Interfaces:**
- Produces: `LineField { NAME, MANUFACT, QUANTITY, IGNORE }`; `HeaderSynonym` (`id`, `headerNorm` UK, `field` enum); `HeaderSynonymRepository.findByHeaderNorm(String) : Optional<HeaderSynonym>`, `findAll()`; сид словаря в `header_synonym`.

- [ ] **Step 1: Падающий тест**

Create `src/test/java/com/vladoose/nir/imports/HeaderSynonymTest.java`:
```java
package com.vladoose.nir.imports;

import com.vladoose.nir.entity.HeaderSynonym;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class HeaderSynonymTest {

    @Autowired HeaderSynonymRepository repository;

    @Test
    void persistsAndFindsByHeaderNorm() {
        repository.save(HeaderSynonym.builder().headerNorm("zzтест-колонка").field(LineField.MANUFACT).build());
        repository.flush();
        assertThat(repository.findByHeaderNorm("zzтест-колонка"))
                .get().extracting(HeaderSynonym::getField).isEqualTo(LineField.MANUFACT);
    }

    @Test
    void seedLoaded() {
        assertThat(repository.findByHeaderNorm("производитель"))
                .get().extracting(HeaderSynonym::getField).isEqualTo(LineField.MANUFACT);
    }
}
```

- [ ] **Step 2: Запустить — падает (нет классов)**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.imports.HeaderSynonymTest"`
Expected: FAIL (компиляция — нет `LineField`/`HeaderSynonym`/репозитория).

- [ ] **Step 3: Enum + entity + repo**

Create `src/main/java/com/vladoose/nir/entity/LineField.java`:
```java
package com.vladoose.nir.entity;

public enum LineField {
    NAME, MANUFACT, QUANTITY, IGNORE
}
```
Create `src/main/java/com/vladoose/nir/entity/HeaderSynonym.java`:
```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "header_synonym")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HeaderSynonym {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "header_norm", length = 255, unique = true, nullable = false)
    private String headerNorm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LineField field;
}
```
Create `src/main/java/com/vladoose/nir/repository/HeaderSynonymRepository.java`:
```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.HeaderSynonym;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HeaderSynonymRepository extends JpaRepository<HeaderSynonym, Long> {
    Optional<HeaderSynonym> findByHeaderNorm(String headerNorm);
}
```

- [ ] **Step 4: Схема**

В `src/main/resources/schema.sql`:
1. В DROP-блок (рядом с прочими `DROP TABLE IF EXISTS ...`) добавить:
```sql
DROP TABLE IF EXISTS header_synonym CASCADE;
```
2. После `CREATE TABLE equipment_type (...)` добавить:
```sql
CREATE TABLE header_synonym (
    id          BIGSERIAL PRIMARY KEY,
    header_norm VARCHAR(255) UNIQUE NOT NULL,
    field       VARCHAR(20) NOT NULL
);
```

- [ ] **Step 5: Сид словаря**

В конец `src/main/resources/data.sql` добавить (таблица без FK — порядок не важен):
```sql
-- ========== Синонимы заголовков для импорта (обучаемый парсер, блок D1) ==========
INSERT INTO header_synonym (header_norm, field) VALUES
  ('наименование', 'NAME'), ('наименование товара', 'NAME'), ('модель', 'NAME'), ('товар', 'NAME'), ('изделие', 'NAME'), ('позиция', 'NAME'), ('оборудование', 'NAME'),
  ('бренд', 'MANUFACT'), ('производитель', 'MANUFACT'), ('изготовитель', 'MANUFACT'), ('марка', 'MANUFACT'), ('вендор', 'MANUFACT'),
  ('кол-во', 'QUANTITY'), ('количество', 'QUANTITY'), ('шт', 'QUANTITY'), ('штук', 'QUANTITY'), ('q-ty', 'QUANTITY'), ('qty', 'QUANTITY');
```

- [ ] **Step 6: Зелёный + проверка таблицы**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.imports.HeaderSynonymTest"` → PASS (оба теста; `seedLoaded` подтверждает загрузку `data.sql`).
```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -h localhost -U postgres -d nirdb -c "\d header_synonym" 2>&1 | head
```
Expected: таблица существует (`header_norm` UNIQUE).

- [ ] **Step 7: Полный suite**

Run (sandbox off): `./gradlew test` → только 2 известных `ApplyAutoFillServiceTest`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/entity/LineField.java src/main/java/com/vladoose/nir/entity/HeaderSynonym.java src/main/java/com/vladoose/nir/repository/HeaderSynonymRepository.java src/main/resources/schema.sql src/main/resources/data.sql src/test/java/com/vladoose/nir/imports/HeaderSynonymTest.java
git commit -m "$(cat <<'EOF'
feat(import): модель синонимов заголовков header_synonym + LineField + сид словаря

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Backend — парсер Excel (`LineExtractor` + `RuleBasedLineExtractor`) (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/PreviewColumnResponse.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/ImportPreviewResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/LineExtractor.java`
- Create: `src/main/java/com/vladoose/nir/service/RuleBasedLineExtractor.java`
- Test: `src/test/java/com/vladoose/nir/imports/RuleBasedLineExtractorTest.java`

**Interfaces:**
- Consumes: `LineField` (Task 1).
- Produces:
  - `ImportPreviewResponse { List<PreviewColumnResponse> columns; List<List<String>> rows; }`
  - `PreviewColumnResponse { int index; String header; LineField field; }`
  - `LineExtractor.extract(byte[] content, String filename, Map<String,LineField> learned) : ImportPreviewResponse`; `@Service RuleBasedLineExtractor` его реализует.

- [ ] **Step 1: DTO**

Create `src/main/java/com/vladoose/nir/dto/response/PreviewColumnResponse.java`:
```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.LineField;
import lombok.Data;

@Data
public class PreviewColumnResponse {
    private int index;
    private String header;
    private LineField field;
}
```
Create `src/main/java/com/vladoose/nir/dto/response/ImportPreviewResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class ImportPreviewResponse {
    private List<PreviewColumnResponse> columns;
    private List<List<String>> rows;
}
```

- [ ] **Step 2: Падающий тест**

Create `src/test/java/com/vladoose/nir/imports/RuleBasedLineExtractorTest.java`:
```java
package com.vladoose.nir.imports;

import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.service.RuleBasedLineExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedLineExtractorTest {

    private final RuleBasedLineExtractor extractor = new RuleBasedLineExtractor();

    private byte[] xlsx(String[] header, Object[]... rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            Row h = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) h.createCell(c).setCellValue(header[c]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Number) row.createCell(c).setCellValue(((Number) v).doubleValue());
                    else row.createCell(c).setCellValue(String.valueOf(v));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void mapsColumnsByLearnedSynonyms_andParsesRows() throws Exception {
        byte[] file = xlsx(
            new String[]{"Наименование", "Производитель", "Кол-во"},
            new Object[]{"ЭКГ BeneHeart R12", "Mindray", 2},
            new Object[]{"Криосауна", "CryoSpace", 1}
        );
        Map<String, LineField> learned = Map.of(
            "наименование", LineField.NAME,
            "производитель", LineField.MANUFACT,
            "кол-во", LineField.QUANTITY
        );

        ImportPreviewResponse p = extractor.extract(file, "z.xlsx", learned);

        assertThat(p.getColumns()).extracting("field")
            .containsExactly(LineField.NAME, LineField.MANUFACT, LineField.QUANTITY);
        assertThat(p.getRows()).hasSize(2);
        assertThat(p.getRows().get(0)).containsExactly("ЭКГ BeneHeart R12", "Mindray", "2");
    }

    @Test
    void unknownHeader_isIgnore() throws Exception {
        byte[] file = xlsx(new String[]{"Загадочная колонка"}, new Object[]{"x"});
        ImportPreviewResponse p = extractor.extract(file, "z.xlsx", Map.of());
        assertThat(p.getColumns().get(0).getField()).isEqualTo(LineField.IGNORE);
    }
}
```

- [ ] **Step 3: Запустить — падает**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.imports.RuleBasedLineExtractorTest"`
Expected: FAIL — нет `LineExtractor`/`RuleBasedLineExtractor`.

- [ ] **Step 4: Интерфейс + реализация**

Create `src/main/java/com/vladoose/nir/service/LineExtractor.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.entity.LineField;

import java.util.Map;

public interface LineExtractor {
    /** content — байты Excel; learned — карта нормализованный_заголовок → поле (словарь + выученное). */
    ImportPreviewResponse extract(byte[] content, String filename, Map<String, LineField> learned);
}
```
Create `src/main/java/com/vladoose/nir/service/RuleBasedLineExtractor.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.dto.response.PreviewColumnResponse;
import com.vladoose.nir.entity.LineField;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RuleBasedLineExtractor implements LineExtractor {

    @Override
    public ImportPreviewResponse extract(byte[] content, String filename, Map<String, LineField> learned) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            int headerRowIdx = firstNonEmptyRow(sheet);
            if (headerRowIdx < 0) {
                throw new IllegalArgumentException("Файл пустой — нет строки заголовков");
            }
            Row headerRow = sheet.getRow(headerRowIdx);
            int colCount = headerRow.getLastCellNum();

            List<PreviewColumnResponse> columns = new ArrayList<>();
            for (int c = 0; c < colCount; c++) {
                String header = cellString(headerRow.getCell(c));
                PreviewColumnResponse col = new PreviewColumnResponse();
                col.setIndex(c);
                col.setHeader(header);
                col.setField(learned.getOrDefault(header.trim().toLowerCase(), LineField.IGNORE));
                columns.add(col);
            }

            List<List<String>> rows = new ArrayList<>();
            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                boolean anyValue = false;
                for (int c = 0; c < colCount; c++) {
                    String v = cellString(row.getCell(c));
                    if (!v.isBlank()) anyValue = true;
                    cells.add(v);
                }
                if (anyValue) rows.add(cells);
            }

            ImportPreviewResponse preview = new ImportPreviewResponse();
            preview.setColumns(columns);
            preview.setRows(rows);
            return preview;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Не удалось прочитать файл (ожидается Excel .xlsx/.xls): " + e.getMessage());
        }
    }

    private int firstNonEmptyRow(Sheet sheet) {
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            short last = row.getLastCellNum();
            for (int c = 0; c < last; c++) {
                if (!cellString(row.getCell(c)).isBlank()) return r;
            }
        }
        return -1;
    }

    private String cellString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }
}
```

- [ ] **Step 5: Зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.imports.RuleBasedLineExtractorTest"` → PASS (обе проверки; кол-во `2` форматируется как `"2"`, неизвестный заголовок → `IGNORE`).

- [ ] **Step 6: Полный suite**

Run (sandbox off): `./gradlew test` → только 2 известных `ApplyAutoFillServiceTest`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/response/PreviewColumnResponse.java src/main/java/com/vladoose/nir/dto/response/ImportPreviewResponse.java src/main/java/com/vladoose/nir/service/LineExtractor.java src/main/java/com/vladoose/nir/service/RuleBasedLineExtractor.java src/test/java/com/vladoose/nir/imports/RuleBasedLineExtractorTest.java
git commit -m "$(cat <<'EOF'
feat(import): RuleBasedLineExtractor — разбор Excel в грид с разметкой колонок по словарю

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Backend — сервис импорта + эндпоинты + multipart (TDD)

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/ColumnMapping.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/ImportCommitRequest.java`
- Create: `src/main/java/com/vladoose/nir/service/PrivateRequestImportService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/com/vladoose/nir/imports/PrivateRequestImportServiceTest.java`

**Interfaces:**
- Consumes: `LineExtractor` (T2), `HeaderSynonymRepository` (T1), `PrivateRequestService.createFromLines`/`linesWithRegistration`, `PrivateRequestCreate(.Line)`.
- Produces:
  - `ColumnMapping { String header; LineField field; }`; `ImportCommitRequest { Long clientFacilityId; String note; List<ColumnMapping> mappings; List<PrivateRequestCreate.Line> lines; }`.
  - `PrivateRequestImportService.preview(byte[], String) : ImportPreviewResponse`; `.commit(ImportCommitRequest) : Tender`.
  - HTTP `POST /api/private-requests/import/preview` (multipart `file`) → `ImportPreviewResponse`; `POST /api/private-requests/import/commit` → `PrivateRequestResponse`.

- [ ] **Step 1: DTO**

Create `src/main/java/com/vladoose/nir/dto/request/ColumnMapping.java`:
```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LineField;
import lombok.Data;

@Data
public class ColumnMapping {
    private String header;
    private LineField field;
}
```
Create `src/main/java/com/vladoose/nir/dto/request/ImportCommitRequest.java`:
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ImportCommitRequest {
    @NotNull
    private Long clientFacilityId;
    private String note;
    private List<ColumnMapping> mappings;
    @NotEmpty(message = "Нужна хотя бы одна строка")
    private List<PrivateRequestCreate.Line> lines;
}
```

- [ ] **Step 2: Падающий тест сервиса**

Create `src/test/java/com/vladoose/nir/imports/PrivateRequestImportServiceTest.java`:
```java
package com.vladoose.nir.imports;

import com.vladoose.nir.dto.request.ColumnMapping;
import com.vladoose.nir.dto.request.ImportCommitRequest;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.HeaderSynonym;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import com.vladoose.nir.service.PrivateRequestImportService;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PrivateRequestImportServiceTest {

    @Autowired PrivateRequestImportService importService;
    @Autowired PrivateRequestService privateRequestService;
    @Autowired HeaderSynonymRepository synonymRepository;
    @Autowired FacilityRepository facilityRepository;

    @Test
    void commit_savesNewSynonym_andCreatesRequestWithLines() {
        Long clientId = facilityRepository.save(Facility.builder().name("ZZIMP Клиника").build()).getId();

        ImportCommitRequest dto = new ImportCommitRequest();
        dto.setClientFacilityId(clientId);
        ColumnMapping m1 = new ColumnMapping(); m1.setHeader("Изделие"); m1.setField(LineField.NAME);
        ColumnMapping m2 = new ColumnMapping(); m2.setHeader("Вендор"); m2.setField(LineField.MANUFACT);
        ColumnMapping m3 = new ColumnMapping(); m3.setHeader("Лишнее"); m3.setField(LineField.IGNORE);
        dto.setMappings(List.of(m1, m2, m3));
        PrivateRequestCreate.Line line = new PrivateRequestCreate.Line();
        line.setName("Аппарат X"); line.setManufact("BrandY"); line.setQuantity(3);
        dto.setLines(List.of(line));

        Tender t = importService.commit(dto);

        assertThat(synonymRepository.findByHeaderNorm("изделие")).get()
                .extracting(HeaderSynonym::getField).isEqualTo(LineField.NAME);
        // IGNORE не сохраняется как синоним
        assertThat(synonymRepository.findByHeaderNorm("лишнее")).isEmpty();
        assertThat(t.getSource()).isEqualTo(Source.PRIVATE_REQUEST);
        assertThat(privateRequestService.linesWithRegistration(t.getId()))
                .extracting("manufact").containsExactly("BrandY");
    }

    @Test
    void commit_updatesExistingSynonymField() {
        synonymRepository.save(HeaderSynonym.builder().headerNorm("поставка").field(LineField.NAME).build());
        synonymRepository.flush();
        Long clientId = facilityRepository.save(Facility.builder().name("ZZIMP2 Клиника").build()).getId();

        ImportCommitRequest dto = new ImportCommitRequest();
        dto.setClientFacilityId(clientId);
        ColumnMapping m = new ColumnMapping(); m.setHeader("Поставка"); m.setField(LineField.MANUFACT);
        dto.setMappings(List.of(m));
        PrivateRequestCreate.Line line = new PrivateRequestCreate.Line();
        line.setName("Z"); line.setQuantity(1);
        dto.setLines(List.of(line));

        importService.commit(dto);

        assertThat(synonymRepository.findByHeaderNorm("поставка")).get()
                .extracting(HeaderSynonym::getField).isEqualTo(LineField.MANUFACT);
    }
}
```

- [ ] **Step 3: Запустить — падает**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.imports.PrivateRequestImportServiceTest"`
Expected: FAIL — нет `PrivateRequestImportService`.

- [ ] **Step 4: Сервис**

Create `src/main/java/com/vladoose/nir/service/PrivateRequestImportService.java`:
```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.ColumnMapping;
import com.vladoose.nir.dto.request.ImportCommitRequest;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.entity.HeaderSynonym;
import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.HeaderSynonymRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class PrivateRequestImportService {

    private final LineExtractor extractor;
    private final HeaderSynonymRepository synonymRepository;
    private final PrivateRequestService privateRequestService;

    public PrivateRequestImportService(LineExtractor extractor,
                                       HeaderSynonymRepository synonymRepository,
                                       PrivateRequestService privateRequestService) {
        this.extractor = extractor;
        this.synonymRepository = synonymRepository;
        this.privateRequestService = privateRequestService;
    }

    public ImportPreviewResponse preview(byte[] content, String filename) {
        return extractor.extract(content, filename, learnedMap());
    }

    @Transactional
    public Tender commit(ImportCommitRequest dto) {
        if (dto.getMappings() != null) {
            for (ColumnMapping m : dto.getMappings()) {
                saveSynonym(m.getHeader(), m.getField());
            }
        }
        PrivateRequestCreate create = new PrivateRequestCreate();
        create.setClientFacilityId(dto.getClientFacilityId());
        create.setNote(dto.getNote());
        create.setLines(dto.getLines());
        return privateRequestService.createFromLines(create);
    }

    private void saveSynonym(String header, LineField field) {
        if (header == null || field == null || field == LineField.IGNORE) return;
        String norm = header.trim().toLowerCase();
        if (norm.isEmpty()) return;
        HeaderSynonym existing = synonymRepository.findByHeaderNorm(norm).orElse(null);
        if (existing == null) {
            synonymRepository.save(HeaderSynonym.builder().headerNorm(norm).field(field).build());
        } else if (existing.getField() != field) {
            existing.setField(field);
            synonymRepository.save(existing);
        }
    }

    private Map<String, LineField> learnedMap() {
        Map<String, LineField> map = new HashMap<>();
        for (HeaderSynonym s : synonymRepository.findAll()) {
            map.put(s.getHeaderNorm(), s.getField());
        }
        return map;
    }
}
```

- [ ] **Step 5: Зелёный**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.imports.PrivateRequestImportServiceTest"` → PASS.

- [ ] **Step 6: Эндпоинты в `PrivateRequestController`**

В `src/main/java/com/vladoose/nir/controller/PrivateRequestController.java`:
1. Импорты:
```java
import com.vladoose.nir.dto.request.ImportCommitRequest;
import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.service.PrivateRequestImportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;
```
2. Добавить зависимость `PrivateRequestImportService importService` в конструктор (присвоить полю — рядом с существующими `service`, `facilityMapper`, `sourcingService`).
3. **Выделить хелпер сборки полного ответа** (DRY — переиспуют create и import). Заменить тело `create(...)` на вызов хелпера и добавить хелпер:
```java
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestResponse create(@Valid @RequestBody PrivateRequestCreate request) {
        return buildFull(service.createFromLines(request));
    }

    @PostMapping("/import/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportPreviewResponse importPreview(@RequestParam("file") MultipartFile file) throws IOException {
        return importService.preview(file.getBytes(), file.getOriginalFilename());
    }

    @PostMapping("/import/commit")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateRequestResponse importCommit(@Valid @RequestBody ImportCommitRequest request) {
        return buildFull(importService.commit(request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badFile(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    private PrivateRequestResponse buildFull(Tender t) {
        PrivateRequestResponse r = toShort(t);
        List<PrivateRequestLineResponse> lines = service.linesWithRegistration(t.getId());
        applyCounts(r, lines);
        r.setLines(lines);
        return r;
    }
```
(`toShort`, `applyCounts`, `linesWithRegistration`, `PrivateRequestLineResponse`, `Tender`, `List` уже доступны/импортированы в этом контроллере — `buildFull` повторяет ровно текущую последовательность из `create`.)

- [ ] **Step 7: Лимит размера файла**

В `src/main/resources/application.yaml` под существующим корнем `spring:` добавить (если блока `servlet`/`multipart` ещё нет — слить аккуратно, не дублируя `spring:`):
```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
```

- [ ] **Step 8: Полный suite + контекст**

Run (sandbox off): `./gradlew test` → компилируется, только 2 известных `ApplyAutoFillServiceTest` (контекст поднимается — контроллер с новым эндпоинтом и зависимостью грузится).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/request/ColumnMapping.java src/main/java/com/vladoose/nir/dto/request/ImportCommitRequest.java src/main/java/com/vladoose/nir/service/PrivateRequestImportService.java src/main/java/com/vladoose/nir/controller/PrivateRequestController.java src/main/resources/application.yaml src/test/java/com/vladoose/nir/imports/PrivateRequestImportServiceTest.java
git commit -m "$(cat <<'EOF'
feat(import): PrivateRequestImportService — апсерт синонимов + createFromLines + эндпоинты preview/commit

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Frontend — флоу импорта в карточке «Частные заявки»

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/private-requests/private-requests.component.ts`

**Interfaces:**
- Consumes: `POST /api/private-requests/import/preview` (multipart) → `{ columns:[{index,header,field}], rows:[[string]] }`; `POST /api/private-requests/import/commit` → `PrivateRequestResponse`.
- Produces: `ApiService.previewImport(file)`, `commitImport(body)`; кнопка «Импорт из файла» + грид-превью + создание.

- [ ] **Step 1: Методы ApiService**

В `frontend/src/app/services/api.service.ts` рядом с `createPrivateRequest`:
```typescript
  previewImport(file: File): Observable<any> {
    const fd = new FormData();
    fd.append('file', file);
    return this.http.post<any>(`${this.base}/private-requests/import/preview`, fd);
  }

  commitImport(body: any): Observable<any> {
    return this.http.post<any>(`${this.base}/private-requests/import/commit`, body);
  }
```
(`Content-Type` не задавать — Angular выставит multipart-границу сам; `marketInterceptor` повесит `X-Market`.)

- [ ] **Step 2: Состояние и методы импорта в компоненте**

В `frontend/src/app/pages/private-requests/private-requests.component.ts` (класс уже инжектит `api`, `cdr`, `notify`, `facilities` загружены, есть поле `cardId`):
1. Поля компонента:
```typescript
  showImport = false;
  importPreview: any = null;
  importClientId: number | null = null;
  importError = '';
  importing = false;
  fieldOptions = [
    { v: 'NAME', l: 'Наименование' },
    { v: 'MANUFACT', l: 'Бренд' },
    { v: 'QUANTITY', l: 'Кол-во' },
    { v: 'IGNORE', l: 'Игнорировать' },
  ];
```
2. Методы:
```typescript
  openImport() {
    this.showImport = true;
    this.importPreview = null;
    this.importClientId = null;
    this.importError = '';
  }

  onImportFile(event: any) {
    const file: File = event.target?.files?.[0];
    if (!file) return;
    this.importError = '';
    this.api.previewImport(file).subscribe({
      next: (p) => { this.importPreview = p; this.cdr.detectChanges(); },
      error: (e) => { this.importError = e.error?.message || 'Не удалось прочитать файл'; this.cdr.detectChanges(); },
    });
  }

  createFromImport() {
    if (!this.importClientId) { this.importError = 'Выберите клиента'; return; }
    const cols = this.importPreview?.columns || [];
    const nameCol = cols.find((c: any) => c.field === 'NAME');
    if (!nameCol) { this.importError = 'Отметьте колонку с наименованием'; return; }
    const manuCol = cols.find((c: any) => c.field === 'MANUFACT');
    const qtyCol = cols.find((c: any) => c.field === 'QUANTITY');
    const lines = (this.importPreview.rows || [])
      .map((row: string[]) => ({
        name: row[nameCol.index],
        manufact: manuCol ? row[manuCol.index] : null,
        quantity: qtyCol ? (parseInt(row[qtyCol.index], 10) || 1) : 1,
      }))
      .filter((l: any) => l.name && String(l.name).trim());
    if (!lines.length) { this.importError = 'Нет строк с наименованием'; return; }
    const mappings = cols
      .filter((c: any) => c.field && c.field !== 'IGNORE')
      .map((c: any) => ({ header: c.header, field: c.field }));
    this.importing = true;
    this.api.commitImport({ clientFacilityId: this.importClientId, mappings, lines }).subscribe({
      next: (created: any) => {
        this.importing = false;
        this.showImport = false;
        this.notify.success('Заявка создана из файла');
        this.load();
        if (created?.id) this.cardId = created.id;
        this.cdr.detectChanges();
      },
      error: (e: any) => {
        this.importing = false;
        this.importError = e.error?.message || 'Ошибка импорта';
        this.cdr.detectChanges();
      },
    });
  }
```

- [ ] **Step 3: Кнопка + панель в шаблоне**

1. В блоке `.head` рядом с кнопкой «+ Новая заявка» добавить:
```html
        <button class="btn-line" (click)="openImport()">⬆ Импорт из файла</button>
```
2. Панель импорта (разместить рядом с блоком формы `*ngIf="showForm"`):
```html
      <div class="import-panel" *ngIf="showImport">
        <div class="import-head">
          <h3>Импорт заявки из Excel</h3>
          <button class="x" (click)="showImport=false">×</button>
        </div>
        <input type="file" accept=".xlsx,.xls" (change)="onImportFile($event)" />
        <p class="hint">Загрузите таблицу — система разметит колонки сама, поправьте при необходимости.</p>

        <div *ngIf="importPreview">
          <label class="lbl">Клиент</label>
          <select [(ngModel)]="importClientId" class="client-sel">
            <option [ngValue]="null" disabled>— выберите —</option>
            <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
          </select>

          <div class="grid-wrap">
            <table class="import-grid">
              <thead>
                <tr>
                  <th *ngFor="let c of importPreview.columns">
                    <div class="ih">{{ c.header || '—' }}</div>
                    <select [(ngModel)]="c.field" [ngModelOptions]="{standalone:true}">
                      <option *ngFor="let o of fieldOptions" [ngValue]="o.v">{{ o.l }}</option>
                    </select>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let row of importPreview.rows">
                  <td *ngFor="let cell of row">{{ cell }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="err" *ngIf="importError">{{ importError }}</div>
          <div class="import-actions">
            <button class="btn-primary" [disabled]="importing" (click)="createFromImport()">Создать заявку</button>
            <button class="btn-line" (click)="showImport=false">Отмена</button>
          </div>
        </div>
        <div class="err" *ngIf="importError && !importPreview">{{ importError }}</div>
      </div>
```
3. Стили (в `styles: []`):
```scss
    .import-panel { border: 1px solid #e5e7eb; border-radius: 10px; padding: 16px; margin: 12px 0; background: #fff; }
    .import-head { display: flex; justify-content: space-between; align-items: center; }
    .import-head .x { background: none; border: none; font-size: 22px; cursor: pointer; color: #6b7280; }
    .import-panel .hint { color: #6b7280; font-size: 12px; margin: 6px 0 12px; }
    .import-panel .lbl { display: block; font-size: 12px; color: #374151; margin-bottom: 4px; }
    .client-sel { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; margin-bottom: 12px; min-width: 260px; }
    .grid-wrap { overflow-x: auto; border: 1px solid #eee; border-radius: 8px; }
    .import-grid { border-collapse: collapse; width: 100%; font-size: 13px; }
    .import-grid th { background: #f9fafb; padding: 8px; border: 1px solid #eee; vertical-align: top; }
    .import-grid th .ih { font-weight: 600; margin-bottom: 4px; }
    .import-grid th select { width: 100%; padding: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 12px; }
    .import-grid td { padding: 6px 8px; border: 1px solid #f0f0f0; white-space: nowrap; }
    .import-actions { display: flex; gap: 8px; margin-top: 12px; }
    .btn-line { background: #fff; border: 1px solid #9ca3af; border-radius: 6px; padding: 6px 14px; cursor: pointer; font-size: 13px; color: #374151; }
```
(`FormsModule`, `NgFor`, `NgIf` уже в imports компонента — форма создания их использует.)

- [ ] **Step 4: Сборка**

Run: `cd frontend && npm run build` (sandbox off при необходимости).
Expected: компиляция без ошибок.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/pages/private-requests/private-requests.component.ts
git commit -m "$(cat <<'EOF'
feat(import): фронт — импорт частной заявки из Excel (грид-превью + правка колонок)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Финальная проверка (после всех задач)

- [ ] `./gradlew test` (sandbox off) — только 2 известных `ApplyAutoFillServiceTest`; все import-тесты зелёные.
- [ ] `cd frontend && npm run build` — фронт собирается.
- [ ] **E2E-смоук (рынок KZ):** поднять бэк (`bootRun`); «Частные заявки» → «Импорт из файла» → загрузить `.xlsx` с шапкой «Наименование | Производитель | Кол-во» → превью показывает разметку NAME/MANUFACT/QUANTITY → выбрать клиента → «Создать заявку» → открылась карточка с разобранными строками. Повторно загрузить файл с заголовком, размеченным вручную в прошлый раз (напр. «Изделие»→Наименование) → он узнаётся автоматически (синоним выучен).
