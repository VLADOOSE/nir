# КП клиенту — реестр и каталог в редакторе (волна 2, часть 3) — план

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** у каждой позиции КП (KZ) честный и заметный статус регистрации в РК — «РУ ✓», «РУ истекло…», «РУ?», «нет в реестре», «не подлежит», «РУ вручную», «не проверено»; кнопкой проверить все позиции, у строки подтвердить или найти РУ, добавить позицию из реестра или каталога; внизу — «не зарегистрировано: N».

**Architecture:** подтверждение идёт обычным автосохранением (PUT КП с версией): строка присылает статус и `regNumber`, сервер сверяет № с реестром и сам собирает текст (`RegistrationText`). Подсказки — чистое вычисление без записи (`POST /api/client-offers/registry-suggestions`, правило этапа 1 `PrivateRequestRegistry.suggestion`). Общая метка РУ (`shared/registry-chip`) отвязывается от частной заявки через объект действий: заявка делает запрос, КП правит строку. Поиск по реестру — по словам наименования (все слова, любой порядок), поиск по каталогу — новый `GET /api/equipment/search`. Миграций нет.

**Tech Stack:** Spring Boot 3.5.6 / Java 17 / Hibernate 6 / PostgreSQL 17 (nirdb, pg_trgm); Angular 21 standalone, ленивые страницы.

**Spec:** `docs/superpowers/specs/2026-10-13-client-offer-registry-design.md` (дельта к §9.4 спеки `2026-10-02-client-kp-constructor-design.md`).

## Global Constraints

- Без миграций: `client_offer_item.registration_status` — `VARCHAR(20)` без ограничения; новый статус `NOT_FOUND` помещается.
- Реестр РУ — только KZ; на RF подтверждение, подсказка и «нет в реестре» через PUT — 400 «Реестр РУ — только для Казахстана — позиция № N».
- Запись — только `ADMIN`, чтение — любой вошедший. Гард рынка по id: КП — `ClientOfferService.get`; позиция каталога — сверка `market` (findById = em.find, фильтр рынка его не режет — CLAUDE.md §14).
- Текст регистрации подтверждённой строки собирает только сервер из записи реестра; присланный текст для `CONFIRMED` не читается.
- Наименование строки при подтверждении не меняется; производитель и страна из записи реестра — только в пустые поля (это делает экран).
- Печать не меняется: печатаются только `CONFIRMED` / `NOT_REQUIRED` / `MANUAL` (`KpDocumentBuilder.PRINTED_REGISTRATION` — `NOT_FOUND` туда не добавлять).
- НДС: подтверждение — `vatRegistered`, «не подлежит» — `vatNotRegistrable`; истёкшее РУ ставку не меняет.
- Тесты — `@SpringBootTest @Transactional` на nirdb; записи реестра для тестов — свои (`ZZ-…`, в откатываемой транзакции), как в `PrivateRequestLinesTest`; MockMvc — `MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity())`; реквизиты KZ в тестах КП — явно в `setUp` (как в `ClientOfferFromRequestTest`); полный прогон — `./gradlew cleanTest test`.
- Фронт: новое — только в ленивых чанках, типы в `ApiService` — `import type`, промисы — `.then`, без нативного `await`; гейт — `npm --prefix frontend run build`.
- Тире в тексте документов — `DocFormat.DASH` (длинного «—» в документах нет).
- Коммиты кончаются строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`; все делегируемые агенты — Opus.

## Review Focus

1. **Запись реестра пропала или изменилась после подтверждения** (переимпорт реестра): сохранение КП с тем же № не падает, текст не меняется — снимок. Тест — задача 2 `sameNumberKeepsSnapshotEvenIfRecordGone`.
2. **Запрос поиска по реестру со спецсимволами** (`%`, `_`, `|`, кавычки), из одних коротких слов или длиннее 200 символов: не 500 — пусто или обрезка. Тест — задача 1 `oddQueriesDoNotFail`.
3. **Новая строка с позицией каталога другого рынка** (id подобран вручную): 400, а не тихая чужая связь. Тест — задача 2 `catalogLinkOtherMarketIs400`.
4. **Код модели только в поле «Модель»** (наименование родовое): подсказка находится; соседняя модель того же бренда — «нет в реестре». Тест — задача 3 `modelCodeFromModelField_otherModelNotFound`.
5. **Вкладка, открытая до деплоя** (старый фронт не шлёт `regNumber`): автосохранение подтверждённой строки не падает — тот же статус без № значит «не менялось». Тест — задача 2 `missingNumberWithSameStatusKeeps`.

## Решения при планировании (уточнения спеки)

1. **Подтверждение без № у строки с тем же статусом — «не менялось»** (совместимость со старой вкладкой); новый статус `CONFIRMED`/`SUGGESTED` без № — 400 «Не указан № РУ».
2. **Оценка подсказки** — `suggestionScore` от 0 до 1 с клиента (из ответа подсказок), три знака; вне диапазона — 400. Подсказка не печатается — подделка оценки безвредна.
3. **Копия строки сохраняет «РУ ✓» и «РУ?» с номером** — сервер перепроверит № на новой строке (раньше копия превращала «подтверждено» в ручной текст — сервер не принимал подтверждение у новой строки).
4. **Метка одна на оба экрана — и словарь один:** «не проверено» (было «РУ —» в заявке), «нет в реестре» (было «РУ не найдено»), «РУ истекло ДД.ММ.ГГГГ» — и в заявке тоже: внутренний смысл тот же — «можно ли продать больнице». «нет в реестре» и «истекло» — красные.
5. **Поиск по реестру:** слова запроса от 3 символов (до 5, длинное первым — по нему отбор по индексу), каждое слово должно встречаться в наименовании записи (`<%`), оценка — средняя похожесть слов; либо запрос похож на производителя; порядок — оценка, затем № РУ. Из одних коротких слов — весь запрос одним словом; меньше 2 символов — пусто; длиннее 200 — обрезка.
6. **Подсказки по строкам КП:** текст = наименование + « » + модель, бренд = производитель; правило — `PrivateRequestRegistry.suggestion` (топ-5, оценка ≥ 0,75, код модели в тексте). Строки без наименования пропускаются. До 500 строк.
7. **Позиция каталога** (`med_equipment_id`) пишется только у новой строки (своего рынка, иначе 400); у сохранённой строки с клиента не меняется.
8. **Сводка «не зарегистрировано: N»** считается на экране по строкам (статус + срок записи + сегодняшняя дата KZ); серверный `unconfirmedRegistrationCount` (для RF-предупреждения) считает и `NOT_FOUND`.
9. **Результат «Проверить регистрацию»** применяется только к строкам, которые всё ещё «не проверено» и у которых наименование, модель и производитель не менялись с момента запроса.

## Карта файлов

| Файл | Что |
|---|---|
| `src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java` | + `searchByName` |
| `src/main/java/com/vladoose/nir/service/RegistryMatchService.java` | + `search(q, limit)`, `searchTokens(q)` |
| `src/main/java/com/vladoose/nir/controller/RegistryController.java` | `search` → новый поиск |
| `src/main/java/com/vladoose/nir/entity/OfferRegistrationStatus.java` | + `NOT_FOUND` |
| `src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java` | `regNumber` пишется, + `suggestionScore`, `medEquipmentId`, `registry` (чтение) |
| `src/main/java/com/vladoose/nir/service/ClientOfferService.java` | `checkRegistration`, `checkCatalog`, `Reg`, правка `checkItem`/`syncItems`/`applyItem` |
| `src/main/java/com/vladoose/nir/service/ClientOfferMapper.java` | запись реестра у строк, `suggestionScore`, `medEquipmentId` |
| `src/main/java/com/vladoose/nir/service/offer/ClientOfferCalculator.java` | `NOT_FOUND` — неподтверждённая регистрация |
| `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRegistry.java` | + `suggestion(text, brand)` |
| `src/main/java/com/vladoose/nir/dto/request/RegistrySuggestionsRequest.java` | новый |
| `src/main/java/com/vladoose/nir/dto/response/RegistrySuggestionResponse.java` | новый |
| `src/main/java/com/vladoose/nir/service/ClientOfferRegistryService.java` | новый — подсказки |
| `src/main/java/com/vladoose/nir/controller/ClientOfferController.java` | + `POST /registry-suggestions` |
| `src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java` | `item`: `SUGGESTED` / `NOT_FOUND` переносятся |
| `src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java` | + `search` |
| `src/main/java/com/vladoose/nir/service/MedEquipmentService.java` | + `search` |
| `src/main/java/com/vladoose/nir/dto/response/EquipmentSearchItemResponse.java` | новый |
| `src/main/java/com/vladoose/nir/controller/MedEquipmentController.java` | + `GET /search` |
| `frontend/src/app/shared/deadline.ts` | + `marketToday` |
| `frontend/src/app/shared/registry.ts` | новый — статус метки, подписи, «истекло», «не зарегистрировано», действия метки |
| `frontend/src/app/shared/registry-chip.component.ts` | входы `status`/`record`/`seed`/`actions` вместо заявки |
| `frontend/src/app/shared/private-request.ts` | `regLabel` удалить (переехал в `registry.ts`) |
| `frontend/src/app/pages/private-requests/pr-lines.component.ts` | действия метки — запросом |
| `frontend/src/app/shared/client-offer.ts` | `RegStatus` + `NOT_FOUND`, поля строки, `toRequest`, типы подсказок и каталога |
| `frontend/src/app/services/api.service.ts` | + `getRegistrySuggestions`, `searchEquipment`, тип `searchRegistry` |
| `frontend/src/app/pages/client-offers/client-offer-items.component.ts` | метка, «Проверить регистрацию», «Из реестра и каталога», «Подробнее», копия |
| `frontend/src/app/pages/client-offers/client-offer-editor.component.ts` | слияние регистрации из ответа, «не зарегистрировано: N» |
| `frontend/src/app/pages/client-offers/offer-add-item-dialog.component.ts` | новый — окно «Из реестра и каталога» |
| Тесты | `service/RegistrySearchByNameTest`, `clientoffer/ClientOfferRegistryPutTest`, `clientoffer/ClientOfferRegistrySuggestionsTest`, `clientoffer/ClientOfferFromRequestTest` (правка), `service/EquipmentSearchTest` |

---

### Task 1: Поиск по реестру — по словам наименования

**Files:**
- Modify: `src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/RegistryMatchService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/RegistryController.java:49-53`
- Test: `src/test/java/com/vladoose/nir/service/RegistrySearchByNameTest.java`

**Interfaces:**
- Produces: `RegistryMatchService.search(String q, int limit) → List<RegistryCandidateResponse>`; `static List<String> RegistryMatchService.searchTokens(String q)`; `GET /api/registry/search?q=&limit=` (тот же путь).

- [ ] **Step 1: Write the failing test**

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Поиск по реестру по наименованию (спека 2026-10-13 §2.5, решение плана 5). */
@SpringBootTest
@Transactional
class RegistrySearchByNameTest {

    @Autowired MedRegistryRepository registry;
    @Autowired RegistryMatchService match;

    private void rec(String no, String name, String producer) {
        registry.save(MedRegistry.builder().regNumber(no).name(name).producer(producer).country("КАЗАХСТАН").unlimited(true).build());
    }

    private static List<String> numbers(List<RegistryCandidateResponse> r) {
        return r.stream().map(RegistryCandidateResponse::getRegNumber).toList();
    }

    @Test
    void wordsInAnyOrder_eachMustBeInName() {
        rec("ZZ-RSN-1", "Рециркулятор воздуха бактерицидный ZZRSNMARK передвижной", "ZZRSN Завод");
        rec("ZZ-RSN-2", "Облучатель-рециркулятор передвижной ZZRSNMARK", "ZZRSN Завод");
        rec("ZZ-RSN-3", "Рециркулятор настенный ZZRSNMARK", "ZZRSN Завод");
        registry.flush();

        List<String> found = numbers(match.search("передвижной zzrsnmark рециркулятор", 20));

        assertThat(found).contains("ZZ-RSN-1", "ZZ-RSN-2").doesNotContain("ZZ-RSN-3");
    }

    @Test
    void equalScoreOrderedByRegNumber() {
        rec("ZZ-RSN-B", "Тонометр ZZRSNTIE механический", "ZZRSN Завод");
        rec("ZZ-RSN-A", "Тонометр ZZRSNTIE механический", "ZZRSN Завод");
        registry.flush();

        List<String> found = numbers(match.search("тонометр zzrsntie", 20));

        assertThat(found.indexOf("ZZ-RSN-A")).isGreaterThanOrEqualTo(0).isLessThan(found.indexOf("ZZ-RSN-B"));
    }

    @Test
    void producerFindsRecord() {
        rec("ZZ-RSN-P", "Монитор пациента", "ZZRSNVENDOR GmbH");
        registry.flush();

        assertThat(numbers(match.search("ZZRSNVENDOR", 20))).contains("ZZ-RSN-P");
    }

    /** Review Focus 2: спецсимволы, одни короткие слова, длинный запрос — пусто или обрезка, не 500. */
    @Test
    void oddQueriesDoNotFail() {
        assertThat(match.search("а", 20)).isEmpty();
        assertThat(match.search("100% _x_ | «ZZ»", 20)).isNotNull();
        assertThat(match.search("ab cd", 20)).isNotNull();
        assertThat(match.search("рециркулятор ".repeat(40), 20)).isNotNull();
    }

    /** Критерий спеки на живом снимке реестра (14 072): прежний поиск давал одну запись. */
    @Test
    void liveSnapshot_recirculatorMobile_moreThanOne() {
        assertThat(match.search("рециркулятор передвижной", 20)).hasSizeGreaterThan(1);
    }

    @Test
    void tokens_longestFirst_upToFive_shortDropped() {
        assertThat(RegistryMatchService.searchTokens("Дефибриллятор ДКИ-Н-12 с принадлежностями"))
                .containsExactly("принадлежностями", "дефибриллятор", "дки-н-12");
        assertThat(RegistryMatchService.searchTokens("ab cd")).containsExactly("ab cd");
        assertThat(RegistryMatchService.searchTokens("a|b%c")).doesNotContain("|");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.service.RegistrySearchByNameTest'`
Expected: FAIL — компиляция: `search(String,int)` и `searchTokens(String)` в `RegistryMatchService` не найдены.

- [ ] **Step 3: Write minimal implementation**

`MedRegistryRepository` — после `findCandidates`:

```java
    /**
     * Поиск по наименованию (спека 2026-10-13 §2.5): каждое слово запроса встречается в наименовании записи
     * (word_similarity, любой порядок), оценка — средняя похожесть слов; или весь запрос похож на производителя.
     * Отбор по индексу — по самому длинному слову (:first <% name); слова — через «|» (как searchByTokensV2).
     * Равная оценка — по № РУ: выдача не зависит от LIMIT.
     */
    @Query(nativeQuery = true, value =
            "SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, m.country AS country, " +
            "m.reg_date AS regDate, m.expiration_date AS expirationDate, m.unlimited AS unlimited, " +
            "GREATEST(" +
            " CASE WHEN (SELECT count(*) FROM unnest(string_to_array(:tokens,'|')) tk(tok) WHERE tk.tok <% m.name) " +
            "           = array_length(string_to_array(:tokens,'|'), 1) " +
            "      THEN (SELECT avg(word_similarity(tk.tok, m.name)) FROM unnest(string_to_array(:tokens,'|')) tk(tok)) " +
            "      ELSE 0 END, " +
            " CASE WHEN :q <% m.producer THEN word_similarity(:q, m.producer) ELSE 0 END) AS score " +
            "FROM med_registry m " +
            "WHERE (:first <% m.name " +
            "       AND (SELECT count(*) FROM unnest(string_to_array(:tokens,'|')) tk(tok) WHERE tk.tok <% m.name) " +
            "           = array_length(string_to_array(:tokens,'|'), 1)) " +
            "   OR :q <% m.producer " +
            "ORDER BY score DESC, m.reg_number " +
            "LIMIT :limit")
    List<RegistryCandidateRow> searchByName(@Param("tokens") String tokens, @Param("first") String first,
                                            @Param("q") String q, @Param("limit") int limit);
```

`RegistryMatchService` — рядом с `findCandidates`:

```java
    private static final int SEARCH_MAX_QUERY = 200;
    private static final int SEARCH_MAX_TOKENS = 5;

    /**
     * Поиск по реестру для окошка метки РУ и «Из реестра и каталога» (спека 2026-10-13 §2.5): слова наименования в любом
     * порядке, каждое должно встретиться; либо производитель. Меньше 2 символов — пусто; длиннее 200 — обрезка.
     */
    public List<RegistryCandidateResponse> search(String q, int limit) {
        if (q == null) return List.of();
        String query = q.trim();
        if (query.length() > SEARCH_MAX_QUERY) query = query.substring(0, SEARCH_MAX_QUERY);
        if (query.length() < 2) return List.of();
        List<String> tokens = searchTokens(query);
        if (tokens.isEmpty()) return List.of();
        int lim = Math.max(1, Math.min(limit, 50));
        return registryRepository.searchByName(String.join("|", tokens), tokens.get(0), query.toLowerCase(Locale.ROOT), lim)
                .stream().map(this::toCandidate).toList();
    }

    /**
     * Слова запроса (решение плана 5): разделители — пробелы и знаки, кроме дефиса и точки внутри кода модели
     * («ДКИ-Н-12»); слова от 3 символов, без повторов, до 5, самое длинное первым — по нему отбор по индексу. Из одних
     * коротких слов — весь запрос одним словом. «|» — разделитель слов в SQL, в слова не попадает.
     */
    static List<String> searchTokens(String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String part : lower.split("[\\s,;:()«»\"'/|%_]+")) {
            String t = part.replaceAll("^[-.]+|[-.]+$", "");
            if (t.length() >= 3 && !out.contains(t)) out.add(t);
        }
        if (out.isEmpty()) {
            String whole = lower.replaceAll("[|%_]", " ").trim().replaceAll("\\s+", " ");
            return whole.length() >= 2 ? List.of(whole) : List.of();
        }
        out.sort(Comparator.comparingInt(String::length).reversed());
        return out.size() > SEARCH_MAX_TOKENS ? new ArrayList<>(out.subList(0, SEARCH_MAX_TOKENS)) : out;
    }
```

(импорты `java.util.ArrayList`, `java.util.Comparator`, `java.util.Locale` — если их нет.)

`RegistryController.search`:

```java
    @GetMapping("/search")
    public List<RegistryCandidateResponse> search(@RequestParam String q,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return matchService.search(q, limit);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.vladoose.nir.service.RegistrySearchByNameTest'`
Expected: PASS (6 tests). Если `tokens_longestFirst…` падает на порядке равных по длине слов — сортировка стабильна (`List.sort` — устойчивая), порядок слов одинаковой длины — как в запросе: поправить ожидание в тесте, а не код.

- [ ] **Step 5: Mutation (по одной, откат копией файла — CLAUDE.md §14)**

`cp src/main/java/com/vladoose/nir/service/RegistryMatchService.java /tmp/rms.bak` → в `search` вернуть прежнее: `return findCandidates(query, query, lim);` → прогон шага 4: Expected FAIL `liveSnapshot_recirculatorMobile_moreThanOne` (и/или `wordsInAnyOrder…`) → `cp /tmp/rms.bak …` → `diff -q` → `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java src/main/java/com/vladoose/nir/service/RegistryMatchService.java src/main/java/com/vladoose/nir/controller/RegistryController.java src/test/java/com/vladoose/nir/service/RegistrySearchByNameTest.java
git commit -m "feat(registry): поиск по реестру по словам наименования

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: PUT КП — № РУ, подсказка, «нет в реестре», позиция каталога; запись реестра в ответе

**Files:**
- Modify: `src/main/java/com/vladoose/nir/entity/OfferRegistrationStatus.java`
- Modify: `src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java`
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferService.java`
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferMapper.java`
- Modify: `src/main/java/com/vladoose/nir/service/offer/ClientOfferCalculator.java:47`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferRegistryPutTest.java`

**Interfaces:**
- Consumes: `RegistrationText.of(String, MedRegistry)`, `PrivateRequestViewBuilder.candidate(MedRegistry) → RegistryCandidateResponse`, `MedRegistryRepository.findByRegNumber`, `findByRegNumberIn`.
- Produces: `OfferRegistrationStatus.NOT_FOUND`; `ClientOfferItemDto.regNumber` (пишется), `suggestionScore` (BigDecimal), `medEquipmentId` (Long), `registry` (RegistryCandidateResponse, только чтение); PUT принимает их (решения 1, 2, 7).

- [ ] **Step 1: Write the failing test**

```java
package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PUT КП: регистрация строки через реестр (спека 2026-10-13 §4, решения плана 1, 2, 7). */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class ClientOfferRegistryPutTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClientOfferService offers;
    @Autowired ClientOfferRepository offerRepo;
    @Autowired CompanyProfileRepository profiles;
    @Autowired MedRegistryRepository registry;
    @Autowired MedEquipmentRepository equipment;
    MockMvc mvc;
    ClientOffer kp;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        CompanyProfile p = profiles.findByMarket(Market.KZ).orElseThrow();   // реквизиты правит оператор — явно, с откатом
        p.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        p.setVatDefault(new BigDecimal("16"));
        p.setVatRegistered(new BigDecimal("5"));
        p.setVatNotRegistrable(new BigDecimal("16"));
        registry.save(MedRegistry.builder().regNumber("ZZ-KPR-1").name("Дефибриллятор ZZKPR ДКИ-Н-99").producer("ZZKPR Концерн")
                .country("РОССИЯ").regDate(LocalDate.of(2024, 1, 15)).unlimited(true).build());
        registry.save(MedRegistry.builder().regNumber("ZZ-KPR-2").name("Отсасыватель ZZKPR ОМХ-9").producer("ZZKPR Концерн")
                .country("РОССИЯ").regDate(LocalDate.of(2021, 6, 28)).expirationDate(LocalDate.of(2026, 6, 28)).unlimited(false).build());
        registry.flush();
        kp = offers.create("test");
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private JsonNode getOffer() throws Exception {
        return om.readTree(mvc.perform(get("/api/client-offers/" + kp.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Тело PUT = ответ GET + одна новая позиция (если row == null). */
    private ObjectNode bodyWithNewRow(ObjectNode row) throws Exception {
        ObjectNode body = getOffer().deepCopy();
        ((ArrayNode) body.get("items")).add(row);
        return body;
    }

    private ObjectNode newRow(String status, String regNumber) {
        ObjectNode r = om.createObjectNode();
        r.put("key", "n" + System.nanoTime());
        r.put("kind", "ITEM");
        r.put("name", "ZZ Позиция");
        r.put("quantity", 1);
        r.put("unit", "шт");
        r.put("vatRate", 5);
        r.put("registrationStatus", status);
        if (regNumber != null) r.put("regNumber", regNumber);
        return r;
    }

    private ResultActions putOffer(ObjectNode body) throws Exception {
        return mvc.perform(put("/api/client-offers/" + kp.getId()).header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private ClientOfferItem storedLast() {
        em.flush();
        em.clear();
        var items = offerRepo.findById(kp.getId()).orElseThrow().getItems();
        return items.get(items.size() - 1);
    }

    @Test
    void confirmFromRegistry_textBuiltByServer_clientTextIgnored() throws Exception {
        ObjectNode row = newRow("CONFIRMED", "ZZ-KPR-1");
        row.put("registrationText", "подделка");

        putOffer(bodyWithNewRow(row)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[-1:].registry.regNumber").value(org.hamcrest.Matchers.hasItem("ZZ-KPR-1")));

        ClientOfferItem it = storedLast();
        assertThat(it.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.CONFIRMED);
        assertThat(it.getRegNumber()).isEqualTo("ZZ-KPR-1");
        assertThat(it.getRegistrationText()).isEqualTo("№ ZZ-KPR-1 от 15.01.2024 г., бессрочно");
    }

    @Test
    void unknownNumberIs400() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", "ZZ-KPR-НЕТ"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("РУ не найдено в реестре: ZZ-KPR-НЕТ")));
    }

    @Test
    void newConfirmedWithoutNumberIs400() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Не указан № РУ")));
    }

    @Test
    void rfOfferIs400() throws Exception {
        MarketContext.set(Market.RF);
        CompanyProfile rfProfile = profiles.findByMarket(Market.RF).orElseThrow();   // ставки РФ — явно, с откатом
        rfProfile.setVatRates(new ArrayList<>(Arrays.asList(null, new BigDecimal("10"), new BigDecimal("22"))));
        ClientOffer rf = offers.create("test");
        em.flush();
        ObjectNode body = om.readTree(mvc.perform(get("/api/client-offers/" + rf.getId()).header("X-Market", "RF"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).deepCopy();
        ObjectNode row = newRow("NOT_FOUND", null);
        row.putNull("vatRate");
        ((ArrayNode) body.get("items")).add(row);

        mvc.perform(put("/api/client-offers/" + rf.getId()).header("X-Market", "RF")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("только для Казахстана")));
    }

    @Test
    void suggestedAndNotFoundAreSaved() throws Exception {
        ObjectNode sug = newRow("SUGGESTED", "ZZ-KPR-1");
        sug.put("suggestionScore", 0.8125);
        putOffer(bodyWithNewRow(sug)).andExpect(status().isOk());
        ClientOfferItem s = storedLast();
        assertThat(s.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.SUGGESTED);
        assertThat(s.getRegNumber()).isEqualTo("ZZ-KPR-1");
        assertThat(s.getSuggestionScore()).isEqualByComparingTo("0.813");
        assertThat(s.getRegistrationText()).isNull();

        putOffer(bodyWithNewRow(newRow("NOT_FOUND", "ZZ-KPR-1"))).andExpect(status().isOk());
        ClientOfferItem nf = storedLast();
        assertThat(nf.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.NOT_FOUND);
        assertThat(nf.getRegNumber()).isNull();
        assertThat(nf.getRegistrationText()).isNull();
    }

    @Test
    void scoreOutOfRangeIs400() throws Exception {
        ObjectNode sug = newRow("SUGGESTED", "ZZ-KPR-1");
        sug.put("suggestionScore", 1.5);
        putOffer(bodyWithNewRow(sug)).andExpect(status().isBadRequest());
    }

    /** Review Focus 1: тот же № у подтверждённой строки не перечитывается — даже если записи в реестре больше нет. */
    @Test
    void sameNumberKeepsSnapshotEvenIfRecordGone() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", "ZZ-KPR-1"))).andExpect(status().isOk());
        Long id = storedLast().getId();
        jdbc.update("update client_offer_item set registration_text = 'старый текст' where id = ?", id);
        jdbc.update("delete from med_registry where reg_number = 'ZZ-KPR-1'");

        putOffer(getOffer().deepCopy()).andExpect(status().isOk());

        assertThat(storedLast().getRegistrationText()).isEqualTo("старый текст");
    }

    /** Review Focus 5: старая вкладка не шлёт regNumber — тот же статус без № значит «не менялось». */
    @Test
    void missingNumberWithSameStatusKeeps() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", "ZZ-KPR-1"))).andExpect(status().isOk());
        ObjectNode body = getOffer().deepCopy();
        ObjectNode last = (ObjectNode) body.get("items").get(body.get("items").size() - 1);
        last.remove("regNumber");

        putOffer(body).andExpect(status().isOk());

        ClientOfferItem it = storedLast();
        assertThat(it.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.CONFIRMED);
        assertThat(it.getRegNumber()).isEqualTo("ZZ-KPR-1");
    }

    @Test
    void responseCarriesRecordWithExpiry() throws Exception {
        putOffer(bodyWithNewRow(newRow("CONFIRMED", "ZZ-KPR-2"))).andExpect(status().isOk());
        JsonNode last = getOffer().get("items").get(getOffer().get("items").size() - 1);
        assertThat(last.get("registry").get("expirationDate").asText()).isEqualTo("2026-06-28");
        assertThat(last.get("registry").get("unlimited").asBoolean()).isFalse();
        assertThat(last.get("registrationText").asText()).isEqualTo("№ ZZ-KPR-2 от 28.06.2021 г., до 28.06.2026 г.");
    }

    @Test
    void catalogLinkOnNewRow_ownMarket_keptAfterwards() throws Exception {
        MedEquipment kzItem = equipment.save(MedEquipment.builder().name("ZZ Каталог KZ").manufact("ZZ").market(Market.KZ).build());
        ObjectNode row = newRow("UNCHECKED", null);
        row.put("medEquipmentId", kzItem.getId());
        putOffer(bodyWithNewRow(row)).andExpect(status().isOk());
        assertThat(storedLast().getMedEquipmentId()).isEqualTo(kzItem.getId());

        MedEquipment other = equipment.save(MedEquipment.builder().name("ZZ Каталог KZ 2").manufact("ZZ").market(Market.KZ).build());
        ObjectNode body = getOffer().deepCopy();
        ((ObjectNode) body.get("items").get(body.get("items").size() - 1)).put("medEquipmentId", other.getId());
        putOffer(body).andExpect(status().isOk());
        assertThat(storedLast().getMedEquipmentId()).isEqualTo(kzItem.getId());   // у сохранённой строки с клиента не меняется
    }

    /** Review Focus 3: позиция каталога другого рынка — 400. */
    @Test
    void catalogLinkOtherMarketIs400() throws Exception {
        MedEquipment rfItem = equipment.save(MedEquipment.builder().name("ZZ Каталог RF").manufact("ZZ").market(Market.RF).build());
        ObjectNode row = newRow("UNCHECKED", null);
        row.put("medEquipmentId", rfItem.getId());

        putOffer(bodyWithNewRow(row)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Позиция каталога не найдена")));
    }

    @Test
    void notFoundCountsAsUnconfirmed() throws Exception {
        putOffer(bodyWithNewRow(newRow("NOT_FOUND", null))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.unconfirmedRegistrationCount").value(1));
    }
}
```

Проверить при исполнении: `jsonPath("$.items[-1:]...")` возвращает список — оставлен `hasItem`; если в `MedEquipment.builder()` нет `market(...)` — поле `market` ставит листенер при `MarketContext` (тогда для RF-позиции — `MarketContext.set(Market.RF)` перед `save` и обратно `KZ`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferRegistryPutTest'`
Expected: FAIL — компиляция (`NOT_FOUND` нет в `OfferRegistrationStatus`) или 400 «Подтвердить регистрацию можно только из реестра» на `confirmFromRegistry…`.

- [ ] **Step 3: Write minimal implementation**

`OfferRegistrationStatus`:

```java
/**
 * Регистрация строки КП. Печатается только CONFIRMED / NOT_REQUIRED / MANUAL; SUGGESTED — подсказка реестра, NOT_FOUND —
 * проверено, в реестре нет (спека 2026-10-13 §2.1) — оба не печатаются. CONFIRMED и SUGGESTED — только с № из реестра
 * (сервер проверяет, текст подтверждённой собирает сам); имя не RegistrationStatus — тот занят каталогом.
 */
public enum OfferRegistrationStatus { UNCHECKED, SUGGESTED, CONFIRMED, NOT_REQUIRED, MANUAL, NOT_FOUND }
```

`ClientOfferItemDto` — заменить поле `regNumber` и добавить поля (рядом):

```java
    /** № РУ (спека 2026-10-13 §4): у CONFIRMED / SUGGESTED сервер сверяет с реестром; у остальных статусов не хранится. */
    @Size(max = 100) private String regNumber;
    /** Оценка подсказки (0…1, три знака); у остальных статусов не хранится. */
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal suggestionScore;
    /** Позиция каталога: пишется только у новой строки (своего рынка, иначе 400), у сохранённой с клиента не меняется. */
    private Long medEquipmentId;
    /** Запись реестра для № строки — показать запись и срок; только для чтения. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private RegistryCandidateResponse registry;
```

(импорт `com.vladoose.nir.dto.response.RegistryCandidateResponse`; прежнюю пару `@JsonProperty(READ_ONLY) private String regNumber;` удалить.)

`ClientOfferService`:

1) Поля и конструктор — два новых репозитория в конец:

```java
    private final MedRegistryRepository registry;
    private final MedEquipmentRepository equipment;

    public ClientOfferService(ClientOfferRepository repository, CompanyProfileService profiles,
                              FacilityRepository facilities, ClientOfferCalculator calculator, KpDocumentBuilder builder,
                              KpHtmlRenderer html, KpPdfRenderer pdf, KpPreviewRenderer preview, KpDocxRenderer docx,
                              PlatformTransactionManager transactions, PriceRequestItemRepository priceRequestItems,
                              MedRegistryRepository registry, MedEquipmentRepository equipment) {
        // … прежние присваивания …
        this.registry = registry;
        this.equipment = equipment;
    }
```

2) `checkItems` — собрать ещё регистрацию и позицию каталога:

```java
    private Checked checkItems(ClientOffer o, List<ClientOfferItemDto> dtos, CompanyProfile profile) {
        Map<Long, ClientOfferItem> existing = new HashMap<>();
        for (ClientOfferItem it : o.getItems()) existing.put(it.getId(), it);
        List<ClientOfferItem> stored = new ArrayList<>(dtos.size());
        List<Link> links = new ArrayList<>(dtos.size());
        List<Reg> regs = new ArrayList<>(dtos.size());
        List<Long> catalog = new ArrayList<>(dtos.size());
        int itemNo = 0;
        for (int i = 0; i < dtos.size(); i++) {
            ClientOfferItemDto dto = dtos.get(i);
            if (dto == null) throw new BadRequestException("Пустая строка в списке строк КП (строка " + (i + 1) + ")");
            ClientOfferItem it = null;
            if (dto.getId() != null) {
                it = existing.remove(dto.getId());
                if (it == null) throw new BadRequestException("Строка не найдена или повторяется: id=" + dto.getId());
            }
            if (dto.getKind() == null) throw new BadRequestException("Не указан вид строки (строка " + (i + 1) + ")");
            Link link = Link.NONE;
            Reg reg = null;
            Long catalogId = null;
            if (dto.getKind() == ClientOfferItemKind.ITEM) {
                String row = "№ " + (++itemNo) + quotedName(dto.getName());
                checkItem(dto, it, profile, row);
                link = checkLink(o, dto, it, row);
                reg = checkRegistration(o, dto, it, row);
                catalogId = checkCatalog(o, dto, it, row);
            }
            stored.add(it);
            links.add(link);
            regs.add(reg);
            catalog.add(catalogId);
        }
        return new Checked(stored, links, regs, catalog);
    }
```

и `private record Checked(List<ClientOfferItem> stored, List<Link> links, List<Reg> regs, List<Long> catalog) {}`.

3) В `checkItem` удалить блок про `CONFIRMED`/`SUGGESTED` (последние строки с `requested` и «Подтвердить регистрацию можно только из реестра»).

4) Новые методы (после `checkLink`):

```java
    /** Регистрация строки после проверки (спека 2026-10-13 §4): keep — сохранённая не меняется (снимок). */
    private record Reg(boolean keep, OfferRegistrationStatus status, String regNumber, String text, BigDecimal score) {
        static final Reg KEEP = new Reg(true, null, null, null, null);
    }

    /**
     * CONFIRMED / SUGGESTED — № из реестра (только KZ), текст подтверждённой собирает сервер; тот же статус с тем же № или
     * без № (старая вкладка, решение плана 1) — не меняется и не перечитывается. NOT_FOUND — только KZ, без № и текста.
     */
    private Reg checkRegistration(ClientOffer o, ClientOfferItemDto dto, ClientOfferItem stored, String row) {
        OfferRegistrationStatus requested = dto.getRegistrationStatus() == null
                ? OfferRegistrationStatus.UNCHECKED : dto.getRegistrationStatus();
        String number = trim(dto.getRegNumber());
        switch (requested) {
            case CONFIRMED, SUGGESTED -> {
                boolean sameStatus = stored != null && stored.getRegistrationStatus() == requested;
                if (number == null) {
                    if (sameStatus) return Reg.KEEP;
                    throw new BadRequestException("Не указан № РУ — позиция " + row);
                }
                if (sameStatus && number.equals(stored.getRegNumber())) return Reg.KEEP;
                if (o.getMarket() != Market.KZ) throw new BadRequestException("Реестр РУ — только для Казахстана — позиция " + row);
                MedRegistry rec = registry.findByRegNumber(number)
                        .orElseThrow(() -> new BadRequestException("РУ не найдено в реестре: " + number + " — позиция " + row));
                if (requested == OfferRegistrationStatus.CONFIRMED) {
                    BigDecimal score = stored != null && number.equals(stored.getRegNumber()) ? stored.getSuggestionScore() : null;
                    return new Reg(false, OfferRegistrationStatus.CONFIRMED, rec.getRegNumber(),
                            RegistrationText.of(rec.getRegNumber(), rec), score);
                }
                return new Reg(false, OfferRegistrationStatus.SUGGESTED, rec.getRegNumber(), null, score(dto.getSuggestionScore(), row));
            }
            case NOT_FOUND -> {
                if (o.getMarket() != Market.KZ) throw new BadRequestException("Реестр РУ — только для Казахстана — позиция " + row);
                return new Reg(false, OfferRegistrationStatus.NOT_FOUND, null, null, null);
            }
            case NOT_REQUIRED -> {
                return new Reg(false, OfferRegistrationStatus.NOT_REQUIRED, null, NOT_REQUIRED_TEXT, null);
            }
            default -> {   // UNCHECKED, MANUAL
                String text = trim(dto.getRegistrationText());
                return new Reg(false, text == null ? OfferRegistrationStatus.UNCHECKED : OfferRegistrationStatus.MANUAL,
                        null, text, null);
            }
        }
    }

    private static BigDecimal score(BigDecimal s, String row) {
        if (s == null) return null;
        if (s.signum() < 0 || s.compareTo(BigDecimal.ONE) > 0) {
            throw new BadRequestException("Оценка подсказки — от 0 до 1 — позиция " + row);
        }
        return s.setScale(3, RoundingMode.HALF_UP);
    }

    /** Позиция каталога (решение плана 7): только у новой строки и только своего рынка; у сохранённой — прежняя. */
    private Long checkCatalog(ClientOffer o, ClientOfferItemDto dto, ClientOfferItem stored, String row) {
        if (stored != null) return stored.getMedEquipmentId();
        Long id = dto.getMedEquipmentId();
        if (id == null) return null;
        MedEquipment e = equipment.findById(id).orElse(null);   // findById = em.find — фильтр рынка его не режет (§14)
        if (e == null || e.getMarket() != o.getMarket()) {
            throw new BadRequestException("Позиция каталога не найдена — позиция " + row);
        }
        return e.getId();
    }
```

(импорты: `java.math.RoundingMode`, `com.vladoose.nir.util.RegistrationText`, `com.vladoose.nir.entity.MedRegistry`, `com.vladoose.nir.entity.MedEquipment`, репозитории — если их нет.)

5) `syncItems`: `applyItem(it, dtos.get(i), checked.links().get(i), checked.regs().get(i), checked.catalog().get(i));`

6) `applyItem(ClientOfferItem it, ClientOfferItemDto dto, Link link, Reg reg, Long catalogId)`: в ветке «не позиция» добавить `it.setMedEquipmentId(null);`; вместо `applyRegistration(it, dto);`:

```java
        if (!reg.keep()) {
            it.setRegistrationStatus(reg.status());
            it.setRegistrationText(reg.text());
            it.setRegNumber(reg.regNumber());
            it.setSuggestionScore(reg.score());
        }
        it.setMedEquipmentId(catalogId);
```

Метод `applyRegistration` удалить; javadoc `applyItem` — «Регистрация — из проверки (checkRegistration); позиция каталога — из checkCatalog; позиция заявки с клиента не меняется».

`ClientOfferMapper`:

```java
    private final TenderRepository tenders;
    private final MedRegistryRepository registry;

    public ClientOfferMapper(TenderRepository tenders, MedRegistryRepository registry) {
        this.tenders = tenders;
        this.registry = registry;
    }
```

В `toResponse` перед циклом строк:

```java
        Set<String> numbers = o.getItems().stream()
                .filter(it -> it.getRegNumber() != null && (it.getRegistrationStatus() == OfferRegistrationStatus.CONFIRMED
                        || it.getRegistrationStatus() == OfferRegistrationStatus.SUGGESTED))
                .map(ClientOfferItem::getRegNumber).collect(Collectors.toSet());
        Map<String, MedRegistry> records = new HashMap<>();
        if (!numbers.isEmpty()) for (MedRegistry m : registry.findByRegNumberIn(numbers)) records.put(m.getRegNumber(), m);
        List<ClientOfferItemDto> items = new ArrayList<>();
        for (int i = 0; i < o.getItems().size(); i++) {
            ClientOfferItem it = o.getItems().get(i);
            ClientOfferItemDto d = toItemDto(it, calc.items().get(i));
            MedRegistry rec = it.getRegNumber() == null ? null : records.get(it.getRegNumber());
            d.setRegistry(rec == null ? null : PrivateRequestViewBuilder.candidate(rec));
            items.add(d);
        }
        r.setItems(items);
```

В `toItemDto`: `d.setSuggestionScore(it.getSuggestionScore()); d.setMedEquipmentId(it.getMedEquipmentId());`.

`ClientOfferCalculator` строка 47:

```java
            if (reg == OfferRegistrationStatus.UNCHECKED || reg == OfferRegistrationStatus.SUGGESTED
                    || reg == OfferRegistrationStatus.NOT_FOUND) unconfirmed++;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS — новый класс (12 тестов) и все прежние КП-тесты (в т.ч. `ClientOfferApiTest` про «Подтвердить регистрацию можно только из реестра», если такой тест есть, — перевести на новое правило: новая строка `CONFIRMED` без № — 400 «Не указан № РУ»; записать `Ruling` в леджер).

- [ ] **Step 5: Mutations (по одной)**

1. В `checkRegistration` для CONFIRMED вернуть текст с клиента: `trim(dto.getRegistrationText())` вместо `RegistrationText.of(...)` → Expected FAIL `confirmFromRegistry_textBuiltByServer_clientTextIgnored`.
2. Убрать ветку `if (sameStatus && number.equals(stored.getRegNumber())) return Reg.KEEP;` → Expected FAIL `sameNumberKeepsSnapshotEvenIfRecordGone`.
3. В `checkCatalog` убрать сверку `e.getMarket() != o.getMarket()` → Expected FAIL `catalogLinkOtherMarketIs400`.
4. В `checkRegistration` убрать `if (sameStatus) return Reg.KEEP;` при `number == null` → Expected FAIL `missingNumberWithSameStatusKeeps`.
После каждой — откат копией, `diff -q`; после серии — `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/entity/OfferRegistrationStatus.java src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java src/main/java/com/vladoose/nir/service/ClientOfferService.java src/main/java/com/vladoose/nir/service/ClientOfferMapper.java src/main/java/com/vladoose/nir/service/offer/ClientOfferCalculator.java src/test/java/com/vladoose/nir/clientoffer/ClientOfferRegistryPutTest.java
git commit -m "feat(client-offer): регистрация строки из реестра через автосохранение, «нет в реестре», позиция каталога

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Подсказки РУ для строк КП — `POST /api/client-offers/registry-suggestions`

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRegistry.java:51-74`
- Create: `src/main/java/com/vladoose/nir/dto/request/RegistrySuggestionsRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/RegistrySuggestionResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/ClientOfferRegistryService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/ClientOfferController.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferRegistrySuggestionsTest.java`

**Interfaces:**
- Consumes: `OfferRegistrationStatus.NOT_FOUND` (задача 2); `RegistryMatchService.findCandidates(name, manufact, limit)`.
- Produces: `PrivateRequestRegistry.suggestion(String text, String brand) → Optional<RegistryCandidateResponse>`; `POST /api/client-offers/registry-suggestions` (ADMIN, KZ) — тело `{rows:[{key,name,model,manufacturer}]}`, ответ `[{key,status,regNumber,score,registry}]`.

- [ ] **Step 1: Write the failing test**

```java
package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Подсказки РУ для строк КП (спека 2026-10-13 §2.3, решение плана 6). */
@SpringBootTest
@Transactional
class ClientOfferRegistrySuggestionsTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired MedRegistryRepository registry;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        registry.save(MedRegistry.builder().regNumber("ZZ-KPS-1").name("Электрокардиограф ZZKPSUG R900, ZZKPSUG R700")
                .producer("ZZKPSUGVENDOR Ltd").country("КИТАЙ").unlimited(true).build());
        registry.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static String row(String key, String name, String model, String manufacturer) {
        return "{\"key\":\"" + key + "\",\"name\":" + (name == null ? "null" : "\"" + name + "\"")
                + ",\"model\":" + (model == null ? "null" : "\"" + model + "\"")
                + ",\"manufacturer\":" + (manufacturer == null ? "null" : "\"" + manufacturer + "\"") + "}";
    }

    private Map<String, JsonNode> suggest(String market, String... rows) throws Exception {
        String body = "{\"rows\":[" + String.join(",", rows) + "]}";
        JsonNode r = om.readTree(mvc.perform(post("/api/client-offers/registry-suggestions").header("X-Market", market)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        Map<String, JsonNode> byKey = new HashMap<>();
        r.forEach(n -> byKey.put(n.get("key").asText(), n));
        return byKey;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void sameModelSuggested_blankNameSkipped() throws Exception {
        Map<String, JsonNode> r = suggest("KZ", row("a", "Электрокардиограф ZZKPSUG R900", null, "ZZKPSUGVENDOR"),
                row("b", "  ", null, "ZZKPSUGVENDOR"));

        assertThat(r.get("a").get("status").asText()).isEqualTo("SUGGESTED");
        assertThat(r.get("a").get("regNumber").asText()).isEqualTo("ZZ-KPS-1");
        assertThat(r.get("a").get("score").decimalValue()).isBetween(new java.math.BigDecimal("0.75"), java.math.BigDecimal.ONE);
        assertThat(r.get("a").get("registry").get("name").asText()).contains("ZZKPSUG R900");
        assertThat(r).doesNotContainKey("b");
    }

    /** Review Focus 4: код модели только в поле «Модель» находится; соседняя модель того же бренда — «нет в реестре». */
    @Test
    @WithMockUser(roles = "ADMIN")
    void modelCodeFromModelField_otherModelNotFound() throws Exception {
        Map<String, JsonNode> r = suggest("KZ", row("m", "Электрокардиограф ZZKPSUG", "R900", "ZZKPSUGVENDOR"),
                row("x", "Электрокардиограф ZZKPSUG R12", null, "ZZKPSUGVENDOR"));

        assertThat(r.get("m").get("status").asText()).isEqualTo("SUGGESTED");
        assertThat(r.get("x").get("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(r.get("x").get("regNumber").isNull()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rfIs400() throws Exception {
        mvc.perform(post("/api/client-offers/registry-suggestions").header("X-Market", "RF")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[" + row("a", "Монитор", null, null) + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void moreThan500RowsIs400() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 501; i++) rows.append(i == 0 ? "" : ",").append(row("k" + i, "Позиция " + i, null, null));
        mvc.perform(post("/api/client-offers/registry-suggestions").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[" + rows + "]}"))
                .andExpect(status().isBadRequest());
    }

    /** Review Focus 5 (часть): оператор подсказки не запрашивает — 403. */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotRequestSuggestions() throws Exception {
        mvc.perform(post("/api/client-offers/registry-suggestions").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferRegistrySuggestionsTest'`
Expected: FAIL — 404/405 на `POST /api/client-offers/registry-suggestions` (пути нет).

- [ ] **Step 3: Write minimal implementation**

`PrivateRequestRegistry` — вынести правило:

```java
    /**
     * Подсказка РУ по тексту позиции (у строки КП — наименование и модель) и бренду (спека 2026-10-12 §5.3,
     * 2026-10-13 §2.3): первая из топ-5 с оценкой ≥ SUGGEST_MIN и тем же кодом модели (mentionsModel).
     */
    public Optional<RegistryCandidateResponse> suggestion(String text, String brand) {
        if (text == null || text.isBlank()) return Optional.empty();
        return matcher.findCandidates(text, brand, CANDIDATES_LIMIT).stream()
                .filter(c -> c.getScore() != null && c.getScore() >= SUGGEST_MIN && mentionsModel(text, c.getName()))
                .findFirst();
    }
```

и в `suggest(TenderLot lot)` заменить поиск `best` на:

```java
        RegistryCandidateResponse best = suggestion(lot.getEquipName(), lot.getManufact()).orElse(null);
```

(импорт `java.util.Optional`.)

`RegistrySuggestionsRequest`:

```java
package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Строки КП для подсказок РУ (спека 2026-10-13 §2.3): ключ строки экрана, наименование, модель, производитель. */
public record RegistrySuggestionsRequest(@NotNull @Size(max = 500) List<@Valid Row> rows) {
    public record Row(@Size(max = 64) String key, @Size(max = 4000) String name, @Size(max = 255) String model,
                      @Size(max = 500) String manufacturer) {}
}
```

`RegistrySuggestionResponse`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.OfferRegistrationStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Подсказка для строки КП: SUGGESTED — № и запись реестра, NOT_FOUND — подходящей записи нет. */
public record RegistrySuggestionResponse(String key, OfferRegistrationStatus status, String regNumber, BigDecimal score,
                                         RegistryCandidateResponse registry) {

    public static RegistrySuggestionResponse suggested(String key, RegistryCandidateResponse c) {
        BigDecimal score = c.getScore() == null ? null : BigDecimal.valueOf(c.getScore()).setScale(3, RoundingMode.HALF_UP);
        return new RegistrySuggestionResponse(key, OfferRegistrationStatus.SUGGESTED, c.getRegNumber(), score, c);
    }

    public static RegistrySuggestionResponse notFound(String key) {
        return new RegistrySuggestionResponse(key, OfferRegistrationStatus.NOT_FOUND, null, null, null);
    }
}
```

`ClientOfferRegistryService`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.RegistrySuggestionsRequest;
import com.vladoose.nir.dto.response.RegistrySuggestionResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.privaterequest.PrivateRequestRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Подсказки РУ для строк КП (спека 2026-10-13 §2.3): ничего не пишет — правки строк уходят автосохранением КП. */
@Service
public class ClientOfferRegistryService {

    public static final int MAX_ROWS = 500;

    private final PrivateRequestRegistry registry;

    public ClientOfferRegistryService(PrivateRequestRegistry registry) {
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public List<RegistrySuggestionResponse> suggestions(List<RegistrySuggestionsRequest.Row> rows) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Реестр РУ — только для Казахстана");
        if (rows == null || rows.isEmpty()) return List.of();
        if (rows.size() > MAX_ROWS) throw new BadRequestException("За раз — не больше " + MAX_ROWS + " позиций");
        List<RegistrySuggestionResponse> out = new ArrayList<>();
        for (RegistrySuggestionsRequest.Row r : rows) {
            if (r == null || r.name() == null || r.name().isBlank()) continue;
            String text = r.model() == null || r.model().isBlank() ? r.name().trim() : r.name().trim() + " " + r.model().trim();
            out.add(registry.suggestion(text, r.manufacturer())
                    .map(c -> RegistrySuggestionResponse.suggested(r.key(), c))
                    .orElseGet(() -> RegistrySuggestionResponse.notFound(r.key())));
        }
        return out;
    }
}
```

(пакет `BadRequestException` — взять тот же, что в `ClientOfferService`.)

`ClientOfferController` — поле и параметр конструктора `ClientOfferRegistryService registryOffers`, метод (перед `/{id}`-путями):

```java
    /** Подсказки РУ для строк КП (спека 2026-10-13 §2.3): только KZ; ничего не пишет. */
    @PostMapping("/registry-suggestions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RegistrySuggestionResponse> registrySuggestions(@Valid @RequestBody RegistrySuggestionsRequest r) {
        return registryOffers.suggestions(r.rows());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferRegistrySuggestionsTest' --tests 'com.vladoose.nir.privaterequest.PrivateRequestLinesTest'`
Expected: PASS — новый класс (5 тестов) и прежние подсказки заявки (рефакторинг правила их не меняет).

- [ ] **Step 5: Mutation**

В `suggestion` убрать `&& mentionsModel(text, c.getName())` → Expected FAIL `modelCodeFromModelField_otherModelNotFound` (и `PrivateRequestLinesTest.otherModelOfSameBrandIsNotASuggestion`). Откат копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestRegistry.java src/main/java/com/vladoose/nir/dto/request/RegistrySuggestionsRequest.java src/main/java/com/vladoose/nir/dto/response/RegistrySuggestionResponse.java src/main/java/com/vladoose/nir/service/ClientOfferRegistryService.java src/main/java/com/vladoose/nir/controller/ClientOfferController.java src/test/java/com/vladoose/nir/clientoffer/ClientOfferRegistrySuggestionsTest.java
git commit -m "feat(client-offer): подсказки РУ для строк КП — правило этапа 1, без записи

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: КП из заявки переносит «РУ?» и «нет в реестре»

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java:186-212`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferFromRequestTest.java` (`registrationManufacturerCountryAndVatByLabel`)

**Interfaces:**
- Consumes: `OfferRegistrationStatus.NOT_FOUND` (задача 2).

- [ ] **Step 1: Write the failing test** — в `registrationManufacturerCountryAndVatByLabel` добавить четвёртую позицию и поменять ожидания третьей:

```java
        Tender t = request(input("ZZ КП Подтверждено", "ZZBRAND", 1, null, null, null),
                input("ZZ КП Не подлежит", "ZZBRAND", 1, null, null, null),
                input("ZZ КП Подсказка", "ZZBRAND", 1, null, null, null),
                input("ZZ КП Нет в реестре", "ZZBRAND", 1, null, null, null));
        List<TenderLot> l = sorted(t);
        // … как было для l.get(0..2) …
        l.get(2).setRegScore(new BigDecimal("0.812"));
        l.get(3).setRegStatus(LineRegStatus.NOT_FOUND);
        em.flush();
```

и вместо прежних ожиданий для `items.get(2)`:

```java
        assertThat(items.get(2).getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.SUGGESTED);
        assertThat(items.get(2).getRegNumber()).isEqualTo(reg);
        assertThat(items.get(2).getSuggestionScore()).isEqualByComparingTo("0.812");
        assertThat(items.get(2).getRegistrationText()).isNull();
        assertThat(items.get(2).getManufacturer()).isEqualTo("ZZBRAND");
        assertThat(items.get(2).getVatRate()).isEqualByComparingTo("16");
        assertThat(items.get(3).getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.NOT_FOUND);
        assertThat(items.get(3).getRegNumber()).isNull();
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferFromRequestTest'`
Expected: FAIL `registrationManufacturerCountryAndVatByLabel` — `UNCHECKED` вместо `SUGGESTED`.

- [ ] **Step 3: Write minimal implementation** — в `ClientOfferRequestService.item` перед последним `else`:

```java
        } else if (lot.getRegStatus() == LineRegStatus.SUGGESTED && !blank(lot.getRegNumber())) {
            // подсказку этапа 1 переносим как подсказку: подтвердить её в КП теперь можно (спека 2026-10-13 §2.6)
            it.setRegistrationStatus(OfferRegistrationStatus.SUGGESTED);
            it.setRegNumber(lot.getRegNumber().trim());
            it.setSuggestionScore(lot.getRegScore());
            it.setVatRate(p.getVatDefault());
        } else if (lot.getRegStatus() == LineRegStatus.NOT_FOUND) {
            it.setRegistrationStatus(OfferRegistrationStatus.NOT_FOUND);
            it.setVatRate(p.getVatDefault());
```

и javadoc `item` — «…по метке РУ (решение плана 4 этапа 3; подсказка и «нет в реестре» — с части 3 волны 2)».

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferFromRequestTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java src/test/java/com/vladoose/nir/clientoffer/ClientOfferFromRequestTest.java
git commit -m "feat(client-offer): КП из заявки переносит «РУ?» и «нет в реестре»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Поиск по каталогу — `GET /api/equipment/search`

**Files:**
- Modify: `src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/MedEquipmentService.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/EquipmentSearchItemResponse.java`
- Modify: `src/main/java/com/vladoose/nir/controller/MedEquipmentController.java`
- Test: `src/test/java/com/vladoose/nir/service/EquipmentSearchTest.java`

**Interfaces:**
- Produces: `GET /api/equipment/search?q=&limit=` → `[{id, name, manufact, registry}]` (свой рынок; `q` от 2 символов; `limit` до 20).

- [ ] **Step 1: Write the failing test**

```java
package com.vladoose.nir.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Поиск по каталогу для «Из реестра и каталога» (спека 2026-10-13 §2.4). */
@SpringBootTest
@Transactional
@WithMockUser(roles = "OPERATOR")
class EquipmentSearchTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired MedEquipmentRepository equipment;
    @Autowired MedRegistryRepository registry;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        MedRegistry rec = registry.save(MedRegistry.builder().regNumber("ZZ-EQS-1").name("Аппарат ZZEQS").producer("ZZEQS")
                .country("РОССИЯ").unlimited(true).build());
        MarketContext.set(Market.KZ);
        MedEquipment kz = equipment.save(MedEquipment.builder().name("Аппарат ZZEQS KZ").manufact("ZZEQSVENDOR").build());
        kz.setRegistration(rec);
        equipment.save(MedEquipment.builder().name("Прибор 100% ZZEQS_X").manufact("ZZEQSVENDOR").build());
        MarketContext.set(Market.RF);
        equipment.save(MedEquipment.builder().name("Аппарат ZZEQS RF").manufact("ZZEQSVENDOR").build());
        equipment.flush();
        MarketContext.set(Market.KZ);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private List<String> names(String market, String q) throws Exception {
        JsonNode r = om.readTree(mvc.perform(get("/api/equipment/search").param("q", q).header("X-Market", market))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> out = new ArrayList<>();
        r.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    @Test
    void ownMarketOnly_byNameAndManufacturer() throws Exception {
        assertThat(names("KZ", "zzeqs")).contains("Аппарат ZZEQS KZ").doesNotContain("Аппарат ZZEQS RF");
        assertThat(names("KZ", "zzeqsvendor")).contains("Аппарат ZZEQS KZ");
        assertThat(names("RF", "zzeqs")).containsExactly("Аппарат ZZEQS RF");
    }

    @Test
    void registryIncludedWhenLinked() throws Exception {
        JsonNode r = om.readTree(mvc.perform(get("/api/equipment/search").param("q", "Аппарат ZZEQS KZ").header("X-Market", "KZ"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.get(0).get("registry").get("regNumber").asText()).isEqualTo("ZZ-EQS-1");
    }

    @Test
    void shortQueryEmpty_likeWildcardsEscaped() throws Exception {
        assertThat(names("KZ", "z")).isEmpty();
        assertThat(names("KZ", "100%")).containsExactly("Прибор 100% ZZEQS_X");
        assertThat(names("KZ", "S_X")).containsExactly("Прибор 100% ZZEQS_X");
    }
}
```

Проверить при исполнении: если `MedEquipment.builder()` не даёт `registration(...)` — сеттер `setRegistration` (Lombok `@Data`) есть; поле рынка ставит листенер по `MarketContext`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.vladoose.nir.service.EquipmentSearchTest'`
Expected: FAIL — `/api/equipment/search` попадает в `GET /{id}` («search» — не число) → 400/500, или 404.

- [ ] **Step 3: Write minimal implementation**

`MedEquipmentRepository`:

```java
    /** Поиск по названию и производителю (спека 2026-10-13 §2.4); рынок — фильтром аспекта, РУ — сразу (без N+1). */
    @Query("SELECT e FROM MedEquipment e LEFT JOIN FETCH e.registration " +
           "WHERE lower(e.name) LIKE :p ESCAPE '\\' OR lower(e.manufact) LIKE :p ESCAPE '\\' ORDER BY e.name, e.id")
    List<MedEquipment> search(@Param("p") String pattern, Pageable page);
```

(импорты `org.springframework.data.domain.Pageable`, `org.springframework.data.repository.query.Param`, `org.springframework.data.jpa.repository.Query`.)

`MedEquipmentService`:

```java
    /** Позиции каталога своего рынка по названию или производителю; меньше 2 символов — пусто; до 20. */
    @Transactional(readOnly = true)
    public List<MedEquipment> search(String q, int limit) {
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) return List.of();
        String like = "%" + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return repository.search(like, PageRequest.of(0, Math.max(1, Math.min(limit, 20))));
    }
```

`EquipmentSearchItemResponse`:

```java
package com.vladoose.nir.dto.response;

/** Позиция каталога в поиске «Из реестра и каталога»: РУ — если привязан. */
public record EquipmentSearchItemResponse(Long id, String name, String manufact, RegistryCandidateResponse registry) {}
```

`MedEquipmentController` (литеральный путь «/search» Spring предпочитает шаблону «/{id}»):

```java
    @GetMapping("/search")
    public List<EquipmentSearchItemResponse> search(@RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
        return service.search(q, limit).stream()
                .map(e -> new EquipmentSearchItemResponse(e.getId(), e.getName(), e.getManufact(),
                        e.getRegistration() == null ? null : PrivateRequestViewBuilder.candidate(e.getRegistration())))
                .toList();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.vladoose.nir.service.EquipmentSearchTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Mutation**

В `MedEquipmentService.search` убрать экранирование `_` (`.replace("_", "\\_")`) → Expected FAIL `shortQueryEmpty_likeWildcardsEscaped` («S_X» найдёт лишнее при `_` = любой символ… если не падает — тест не различает; тогда усилить: добавить позицию «ZZEQSAX» и ждать её отсутствия). Откат копией, `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/repository/MedEquipmentRepository.java src/main/java/com/vladoose/nir/service/MedEquipmentService.java src/main/java/com/vladoose/nir/dto/response/EquipmentSearchItemResponse.java src/main/java/com/vladoose/nir/controller/MedEquipmentController.java src/test/java/com/vladoose/nir/service/EquipmentSearchTest.java
git commit -m "feat(equipment): поиск по каталогу для «Из реестра и каталога»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Фронт — общая метка РУ без привязки к частной заявке

**Files:**
- Modify: `frontend/src/app/shared/deadline.ts`
- Create: `frontend/src/app/shared/registry.ts`
- Modify: `frontend/src/app/shared/registry-chip.component.ts`
- Modify: `frontend/src/app/shared/private-request.ts` (удалить `regLabel`)
- Modify: `frontend/src/app/pages/private-requests/pr-lines.component.ts:63-64`, конструктор/поля

**Interfaces:**
- Produces: `marketToday(market: Market, now?: number): string`; в `shared/registry.ts` — `type ChipStatus`, `interface RegistryChipActions { candidates(): Observable<RegistryRecord[]>; act(action: RegAction, record?: RegistryRecord): Observable<unknown>; }`, `regExpired(r, today)`, `chipLabel(s, r, today)`, `chipTone(s, r, today)`, `unregistered(s, r, today)`; `<app-registry-chip [status] [record] [seed] [actions] [readonly] (changed)>`.

- [ ] **Step 1: `deadline.ts`** — в конец:

```ts
/** Сегодняшняя дата рынка «2026-10-13» — по его часам, не по поясу браузера (срок РУ «истекло», спека 2026-10-13 §2.1). */
export function marketToday(market: Market, now = Date.now()): string {
  return new Date(now + OFFSET_HOURS[market] * 60 * 60 * 1000).toISOString().slice(0, 10);
}
```

- [ ] **Step 2: `shared/registry.ts`**

```ts
import type { Observable } from 'rxjs';
import { dateText } from './private-request';
import type { RegAction, RegistryRecord } from './private-request';

/** Статус регистрации для метки: частная заявка (RegStatus) и строка КП (ещё UNCHECKED и MANUAL). */
export type ChipStatus = 'CONFIRMED' | 'SUGGESTED' | 'NOT_FOUND' | 'NOT_REQUIRED' | 'MANUAL' | 'UNCHECKED' | null | undefined;

/** Что метка делает со строкой: заявка — запросом, КП — правкой строки и автосохранением (спека 2026-10-13 §2.2). */
export interface RegistryChipActions {
  /** Кандидаты при «Найти в реестре» — до своего запроса. */
  candidates(): Observable<RegistryRecord[]>;
  /** Подтвердить (record — выбранная запись; без неё — текущая подсказка), «не подлежит», вернуть проверку. */
  act(action: RegAction, record?: RegistryRecord): Observable<unknown>;
}

/** РУ истекло: срок записи раньше сегодняшней даты рынка; бессрочное не истекает. */
export function regExpired(r: RegistryRecord | null | undefined, today: string): boolean {
  return !!r && !r.unlimited && !!r.expirationDate && r.expirationDate.slice(0, 10) < today;
}

/** Подпись метки (спека 2026-10-13 §2.1; решение плана 4 — один словарь для заявки и КП). */
export function chipLabel(s: ChipStatus, r: RegistryRecord | null | undefined, today: string): string {
  switch (s) {
    case 'CONFIRMED': return regExpired(r, today) ? 'РУ истекло ' + dateText(r!.expirationDate) : 'РУ ✓';
    case 'SUGGESTED': return 'РУ?';
    case 'NOT_FOUND': return 'нет в реестре';
    case 'NOT_REQUIRED': return 'не подлежит';
    case 'MANUAL': return 'РУ вручную';
    default: return 'не проверено';
  }
}

/** data-status метки: истёкшее подтверждённое — отдельный тон. */
export function chipTone(s: ChipStatus, r: RegistryRecord | null | undefined, today: string): string {
  if (s === 'CONFIRMED' && regExpired(r, today)) return 'EXPIRED';
  return s ?? 'UNCHECKED';
}

/** Позицию нельзя продать больнице без проверки: не проверено, «РУ?», нет в реестре, РУ истекло (сводка, §2.1). */
export function unregistered(s: ChipStatus, r: RegistryRecord | null | undefined, today: string): boolean {
  return s == null || s === 'UNCHECKED' || s === 'SUGGESTED' || s === 'NOT_FOUND' || (s === 'CONFIRMED' && regExpired(r, today));
}
```

- [ ] **Step 3: `registry-chip.component.ts`** — входы и действия; шаблон: `line.regStatus` → `status`, `line.registry` → `record`; `act('CONFIRM', r.regNumber)` → `act('CONFIRM', r)`; стили — тоны «нет в реестре» и «истекло»:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnDestroy, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ApiService } from '../services/api.service';
import { RegAction, RegistryRecord, regValidity } from './private-request';
import { ChipStatus, RegistryChipActions, chipLabel, chipTone, regExpired } from './registry';
import { marketToday } from './deadline';
import { errorText } from './error-text';
```

Шаблон (ключевые места):

```html
    <span class="rc" (click)="$event.stopPropagation()">
      <button type="button" class="chip" [attr.data-status]="tone()" (click)="toggle()"
              [attr.aria-expanded]="open" [attr.aria-label]="'Регистрация: ' + label()">{{ label() }}</button>
      <div class="pop" *ngIf="open" role="dialog" aria-label="Регистрация в реестре">
        <div class="rec" *ngIf="record as r">
          <div class="rec-kind">{{ status === 'CONFIRMED' ? 'Подтверждено' : 'Похоже на' }}</div>
          <div class="rec-name">{{ r.name }}</div>
          <div class="rec-meta">{{ r.producer || 'производитель не указан' }}<ng-container *ngIf="r.country"> · {{ r.country }}</ng-container></div>
          <div class="rec-meta" [class.expired]="expired(r)">№ {{ r.regNumber }}<ng-container *ngIf="validity(r)"> · {{ validity(r) }}</ng-container>
            <ng-container *ngIf="expired(r)"> — срок истёк: больнице не продать, пока РУ не продлено</ng-container></div>
        </div>
        <div class="rec-none" *ngIf="!record">{{ noneText() }}</div>
        <div class="pop-actions" *ngIf="!readonly">
          <button type="button" class="btn btn-primary" *ngIf="status === 'SUGGESTED'" [disabled]="busy" (click)="act('CONFIRM')">Подтвердить</button>
          <button type="button" class="btn btn-line" *ngIf="status === 'CONFIRMED'" [disabled]="busy" (click)="act('RESET')">Снять подтверждение</button>
          <button type="button" class="btn btn-line" *ngIf="status === 'NOT_REQUIRED'" [disabled]="busy" (click)="act('RESET')">Вернуть проверку</button>
          <button type="button" class="btn btn-line" *ngIf="!searching" [disabled]="busy" (click)="openSearch()">
            {{ status === 'SUGGESTED' ? 'Не то — найти в реестре' : 'Найти в реестре' }}</button>
          <button type="button" class="btn btn-line" *ngIf="status !== 'NOT_REQUIRED'" [disabled]="busy" (click)="act('NOT_REQUIRED')">Не подлежит</button>
        </div>
        <!-- блок поиска — как был; в результатах: (click)="act('CONFIRM', r)" -->
```

Стили — заменить строку тонов «NOT_FOUND / NOT_REQUIRED / NONE»:

```css
    .chip[data-status="NOT_FOUND"], .chip[data-status="EXPIRED"] {
      background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .chip[data-status="NOT_REQUIRED"], .chip[data-status="MANUAL"], .chip[data-status="UNCHECKED"] {
      background: transparent; border: 1px solid var(--border); color: var(--text-muted); }
    .rec-meta.expired { color: var(--danger-text); }
```

Класс:

```ts
export class RegistryChipComponent implements OnDestroy {
  @Input() status: ChipStatus = null;
  @Input() record: RegistryRecord | null = null;
  /** Текст поиска по умолчанию: наименование, модель, бренд строки. */
  @Input() seed = '';
  @Input({ required: true }) actions!: RegistryChipActions;
  @Input() readonly = false;
  @Output() changed = new EventEmitter<unknown>();
  // open, searching, busy, error, query, results, loadingResults, searchedOnce, timer, searchSub — как были

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  private today() { return marketToday('KZ'); }   // реестр РУ — только Казахстан
  label() { return chipLabel(this.status, this.record, this.today()); }
  tone() { return chipTone(this.status, this.record, this.today()); }
  expired(r: RegistryRecord) { return regExpired(r, this.today()); }
  validity(r: RegistryRecord) { return regValidity(r); }

  noneText(): string {
    switch (this.status) {
      case 'NOT_REQUIRED': return 'Отмечено: не подлежит регистрации.';
      case 'NOT_FOUND': return this.readonly
        ? 'В реестре РК подходящего РУ нет — больнице такое не продать.'
        : 'В реестре РК подходящего РУ нет — найдите вручную или отметьте «не подлежит».';
      case 'MANUAL': return 'Номер РУ вписан вручную — проверьте по реестру или оставьте.';
      default: return 'Регистрация ещё не проверялась.';
    }
  }

  openSearch() {
    this.searching = true;
    this.query = this.seed;
    this.results = [];
    this.searchedOnce = false;
    this.loadingResults = true;
    this.searchSub?.unsubscribe();
    this.searchSub = this.actions.candidates().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { this.results = r || []; this.loadingResults = false; this.searchedOnce = true; this.cdr.markForCheck(); },
      error: e => { this.loadingResults = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }

  act(action: RegAction, record?: RegistryRecord) {
    this.busy = true;
    this.error = '';
    this.actions.act(action, record).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: res => { this.busy = false; this.close(); this.changed.emit(res); this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }
  // ngOnDestroy, close, toggle, onQuery, search (api.searchRegistry) — как были
}
```

- [ ] **Step 4: `private-request.ts`** — удалить `regLabel` (больше не используется: `grep -rn regLabel frontend/src` — пусто).

- [ ] **Step 5: `pr-lines.component.ts`** — разметка:

```html
              <app-registry-chip *ngIf="isKz" [status]="l.regStatus" [record]="l.registry" [seed]="regSeed(l)"
                                 [actions]="regActions(l)" [readonly]="readonly" (changed)="onLine($any($event))"></app-registry-chip>
```

и в класс (импорт `RegistryChipActions` из `../../shared/registry`):

```ts
  /** Действия метки РУ позиции — запросом к заявке; по позиции один объект (вход метки не меняется на каждой проверке). */
  private regActionsByLot = new Map<number, RegistryChipActions>();

  regActions(l: PrLine): RegistryChipActions {
    let a = this.regActionsByLot.get(l.lotId);
    if (!a) {
      const lotId = l.lotId;
      a = {
        candidates: () => this.api.getPrivateRequestLineCandidates(this.view.id, lotId),
        act: (action, record) => this.api.setPrivateRequestLineRegistry(this.view.id, lotId, action, record?.regNumber),
      };
      this.regActionsByLot.set(lotId, a);
    }
    return a;
  }

  regSeed(l: PrLine): string {
    return [l.name, l.brand].filter(Boolean).join(' ');
  }
```

- [ ] **Step 6: Build**

Run: `npm --prefix frontend run build`
Expected: `Output location: …`, без ошибок; начальный бандл — не больше прежнего (≈1,34 МБ).

- [ ] **Step 7: Живьём — метка в заявке работает как раньше** (бэкенд + фронт, KZ): заявка с позицией, у которой «РУ?» → окошко → «Подтвердить» → «РУ ✓»; «Найти в реестре» → кандидаты позиции → выбор → «РУ ✓» с выбранным №; «Не подлежит» → «не подлежит»; «Вернуть проверку». Подписи — новый словарь («не проверено», «нет в реестре»).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/shared/deadline.ts frontend/src/app/shared/registry.ts frontend/src/app/shared/registry-chip.component.ts frontend/src/app/shared/private-request.ts frontend/src/app/pages/private-requests/pr-lines.component.ts
git commit -m "refactor(registry-chip): метка РУ без привязки к частной заявке, «истекло» и «нет в реестре»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Фронт — метка РУ в строках КП, «Проверить регистрацию», сводка

**Files:**
- Modify: `frontend/src/app/shared/client-offer.ts`
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/client-offers/client-offer-items.component.ts`
- Modify: `frontend/src/app/pages/client-offers/client-offer-editor.component.ts`

**Interfaces:**
- Consumes: задачи 2, 3, 6 (`registry`, `regNumber`, `suggestionScore`, `NOT_FOUND`; подсказки; `RegistryChipActions`, `unregistered`, `marketToday`).
- Produces: `ApiService.getRegistrySuggestions(rows)`; типы `RegistrySuggestion`, `SuggestionRow` в `shared/client-offer.ts`.

- [ ] **Step 1: `shared/client-offer.ts`**

```ts
export type RegStatus = 'UNCHECKED' | 'SUGGESTED' | 'CONFIRMED' | 'NOT_REQUIRED' | 'MANUAL' | 'NOT_FOUND';
```

в `OfferItem` (рядом с `regNumber`):

```ts
  /** Оценка подсказки (0…1) — с «Проверить регистрацию»; не печатается. */
  suggestionScore?: number | null;
  /** Позиция каталога — только у новой строки из «Из реестра и каталога». */
  medEquipmentId?: number | null;
  /** Запись реестра для № строки (ответ сервера) — запись и срок в метке. */
  registry?: RegistryRecord | null;
```

(`import type { RegistryRecord } from './private-request';`)

в `toRequest` у строки — `regNumber: it.regNumber ?? null, suggestionScore: it.suggestionScore ?? null, medEquipmentId: it.medEquipmentId ?? null,`.

и типы:

```ts
/** Строка для подсказок РУ и ответ (спека 2026-10-13 §2.3). */
export interface SuggestionRow { key: string; name: string; model: string | null; manufacturer: string | null; }
export interface RegistrySuggestion {
  key: string; status: 'SUGGESTED' | 'NOT_FOUND'; regNumber: string | null; score: number | null; registry: RegistryRecord | null;
}
```

- [ ] **Step 2: `api.service.ts`** — `import type { …, RegistrySuggestion, SuggestionRow } from '../shared/client-offer';`:

```ts
  /** Подсказки РУ для строк КП — ничего не пишет (спека 2026-10-13 §2.3). */
  getRegistrySuggestions(rows: SuggestionRow[]): Observable<RegistrySuggestion[]> {
    return this.http.post<RegistrySuggestion[]>(`${this.base}/client-offers/registry-suggestions`, { rows });
  }
```

и тип `searchRegistry`: `Observable<RegistryRecord[]>` (импорт типа `RegistryRecord` — уже есть для заявки).

- [ ] **Step 3: `client-offer-items.component.ts`**

Импорты: `RegistryChipComponent` (в `imports`), `RegistryChipActions` из `../../shared/registry`, типы `RegAction`, `RegistryRecord` из `../../shared/private-request`, `MarketService`, `of` из `rxjs`.

Панель «Позиции» — после «+ Включено в стоимость»:

```html
      <button type="button" class="btn btn-line" *ngIf="isKz" [disabled]="checking" (click)="checkRegistry()">
        {{ checking ? 'Проверяю…' : 'Проверить регистрацию' }}</button>
```

Строка — вместо `<span class="chip-warn" *ngIf="needsReg(it)">РУ не указано</span>`:

```html
              <app-registry-chip *ngIf="isKz" [status]="it.registrationStatus" [record]="it.registry ?? null"
                                 [seed]="regSeed(it)" [actions]="regActions(it)" [readonly]="readonly"></app-registry-chip>
              <span class="chip-warn" *ngIf="!isKz && needsReg(it)">РУ не указано</span>
```

«Подробнее» — select регистрации:

```html
              <select [ngModel]="it.registrationStatus" (ngModelChange)="setRegStatus(it, $event)" [disabled]="readonly">
                <option value="UNCHECKED">не проверено</option>
                <option value="MANUAL">указать номер РУ вручную</option>
                <option value="NOT_REQUIRED">не подлежит регистрации</option>
                <!-- подтверждение, подсказку и «нет в реестре» ставит метка РУ: здесь их можно только оставить -->
                <option *ngIf="it.registrationStatus === 'CONFIRMED'" value="CONFIRMED">подтверждена по реестру</option>
                <option *ngIf="it.registrationStatus === 'SUGGESTED'" value="SUGGESTED">подсказка реестра</option>
                <option *ngIf="it.registrationStatus === 'NOT_FOUND'" value="NOT_FOUND">нет в реестре</option>
              </select>
```

Класс:

```ts
  readonly isKz: boolean;
  checking = false;
  private regActionsByKey = new Map<string, RegistryChipActions>();

  // в конструктор: private market: MarketService — и в теле: this.isKz = market.value === 'KZ';

  regSeed(it: OfferItem): string {
    return [it.name, it.model, it.manufacturer].filter(Boolean).join(' ');
  }

  /** Действия метки — правкой строки и автосохранением (спека 2026-10-13 §4); строка ищется по ключу при вызове. */
  regActions(it: OfferItem): RegistryChipActions {
    const key = it.key;
    let a = this.regActionsByKey.get(key);
    if (!a) {
      a = {
        candidates: () => {
          const cur = this.offer.items.find(x => x.key === key);
          return this.api.searchRegistry(cur ? this.regSeed(cur) : '', 10);
        },
        act: (action, record) => {
          const cur = this.offer.items.find(x => x.key === key);
          if (cur) this.applyReg(cur, action, record);
          return of(cur);
        },
      };
      this.regActionsByKey.set(key, a);
    }
    return a;
  }

  /** Подтвердить / «не подлежит» / вернуть проверку: наименование не трогаем, производитель и страна — в пустые. */
  private applyReg(it: OfferItem, action: RegAction, record?: RegistryRecord) {
    const p = this.profile;
    if (action === 'CONFIRM') {
      const rec = record ?? it.registry ?? null;
      const number = rec?.regNumber ?? it.regNumber ?? null;
      if (!number) return;
      if (record && record.regNumber !== it.regNumber) it.suggestionScore = null;
      it.registrationStatus = 'CONFIRMED';
      it.regNumber = number;
      it.registry = rec;
      it.registrationText = null;   // текст соберёт сервер из записи реестра — придёт ответом автосохранения
      if (rec && !it.manufacturer && rec.producer) it.manufacturer = rec.producer.slice(0, 500);
      if (rec && !it.country && rec.country) it.country = rec.country.slice(0, 200);
      if (this.offer.vatEnabled && p && 'vatRegistered' in p) it.vatRate = p.vatRegistered;
    } else if (action === 'NOT_REQUIRED') {
      it.registrationStatus = 'NOT_REQUIRED';
      it.registrationText = 'Не подлежит регистрации';
      it.regNumber = null; it.registry = null; it.suggestionScore = null;
      if (this.offer.vatEnabled && p && 'vatNotRegistrable' in p) it.vatRate = p.vatNotRegistrable;
    } else {
      it.registrationStatus = 'UNCHECKED';
      it.registrationText = null; it.regNumber = null; it.registry = null; it.suggestionScore = null;
    }
    this.emit();
    this.cdr.markForCheck();
  }

  /**
   * «Проверить регистрацию» (спека 2026-10-13 §2.3, решение плана 9): подсказки для непроверенных позиций с наименованием;
   * результат — только строкам, которые всё ещё «не проверено» и не менялись с запроса; дальше — автосохранение.
   */
  checkRegistry() {
    const rows = this.offer.items.filter(it => it.kind === 'ITEM' && it.registrationStatus === 'UNCHECKED' && (it.name ?? '').trim());
    if (!rows.length) { this.notify.info('Непроверенных позиций нет'); return; }
    const sent = new Map(rows.map(it => [it.key, rowKey(it)]));
    this.checking = true;
    this.api.getRegistrySuggestions(rows.map(it => ({ key: it.key, name: it.name, model: it.model ?? null, manufacturer: it.manufacturer ?? null })))
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: results => {
          this.checking = false;
          let suggested = 0, notFound = 0;
          for (const r of results) {
            const it = this.offer.items.find(x => x.key === r.key);
            if (!it || it.registrationStatus !== 'UNCHECKED' || sent.get(it.key) !== rowKey(it)) continue;
            if (r.status === 'SUGGESTED') {
              it.registrationStatus = 'SUGGESTED'; it.regNumber = r.regNumber; it.suggestionScore = r.score; it.registry = r.registry;
              suggested++;
            } else {
              it.registrationStatus = 'NOT_FOUND'; it.regNumber = null; it.suggestionScore = null; it.registry = null;
              notFound++;
            }
          }
          this.notify.success(`Проверено позиций: ${suggested + notFound} — «РУ?»: ${suggested}, нет в реестре: ${notFound}`);
          if (suggested + notFound) this.emit();
          this.cdr.markForCheck();
        },
        error: e => { this.checking = false; this.notify.error('Проверка не удалась: ' + errorText(e)); this.cdr.markForCheck(); },
      });
  }
```

и функция модуля (рядом с константами):

```ts
/** Наименование, модель, производитель и статус — по ним «Проверить регистрацию» видит, что строку правили во время запроса. */
function rowKey(it: OfferItem): string {
  return [it.name, it.model, it.manufacturer, it.registrationStatus].map(v => v ?? '').join('|');
}
```

`setRegStatus` — снимать № и запись при уходе с реестровых статусов (в начало метода после присваивания статуса):

```ts
    if (status !== 'CONFIRMED' && status !== 'SUGGESTED') { it.regNumber = null; it.registry = null; it.suggestionScore = null; }
```

`duplicateRow` — решение плана 3: удалить две строки, превращающие `CONFIRMED` в `MANUAL` и `SUGGESTED` в `UNCHECKED`, и строку `copy.regNumber = null;`; комментарий заменить: «Регистрация копируется как есть: «РУ ✓» и «РУ?» с № сервер перепроверит у новой строки».

Подсказка `needsReg` — только для РФ (KZ — метка): оставить как есть, вызов — `!isKz && needsReg(it)`.

- [ ] **Step 4: `client-offer-editor.component.ts`**

Импорты: `unregistered` из `../../shared/registry`, `marketToday` из `../../shared/deadline`.

`merge` — в ветке `if (s) { … }` после прежних присваиваний:

```ts
        it.medEquipmentId = s.medEquipmentId ?? null;
        // регистрация, собранная сервером (текст подтверждённой, запись реестра), — только если экран её не сменил за время запроса
        if (it.registrationStatus === s.registrationStatus && (it.regNumber ?? null) === (s.regNumber ?? null)) {
          it.registry = s.registry ?? null;
          if (s.registrationStatus === 'CONFIRMED') it.registrationText = s.registrationText ?? null;
        }
```

Нижняя панель — вместо `<span *ngIf="regWarnings()">без регистрации: …</span>`:

```html
          <span class="reg-danger" *ngIf="isKz() && unregisteredCount()">не зарегистрировано: {{ unregisteredCount() }}</span>
          <span *ngIf="!isKz() && regWarnings()">без регистрации: {{ regWarnings() }}</span>
```

стиль (рядом с `.warns`): `.reg-danger { color: var(--danger-text); font-weight: 600; }`;

методы:

```ts
  isKz(): boolean {
    return this.market.value === 'KZ';
  }

  /** Сводка «не зарегистрировано: N» (спека 2026-10-13 §2.1): по строкам на экране, без сохранения; печать колонки не важна. */
  unregisteredCount(): number {
    const o = this.offer;
    if (!o) return 0;
    const today = marketToday('KZ');
    return o.items.filter(it => it.kind === 'ITEM' && unregistered(it.registrationStatus, it.registry, today)).length;
  }
```

- [ ] **Step 5: Build**

Run: `npm --prefix frontend run build`
Expected: `Output location: …`, без ошибок и без превышения бюджетов стилей (24 kB).

- [ ] **Step 6: Живьём (KZ)** — КП из 4 позиций (одна совпадает с записью реестра по коду модели и производителю, одна — нет, одна «не подлежит», одна с истёкшим РУ из реестра): «Проверить регистрацию» → «РУ?» и «нет в реестре», тост с числами; «Подтвердить» → «РУ ✓», текст в «Подробнее» после автосохранения, ставка 5 %; «Не то — найти в реестре» → поиск → выбор; сводка «не зарегистрировано: N» меняется сразу; PDF — регистрация только у подтверждённой и «не подлежит».

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/client-offer.ts frontend/src/app/services/api.service.ts frontend/src/app/pages/client-offers/client-offer-items.component.ts frontend/src/app/pages/client-offers/client-offer-editor.component.ts
git commit -m "feat(client-offer): метка РУ у позиций, «Проверить регистрацию», «не зарегистрировано: N»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Фронт — «Из реестра и каталога»

**Files:**
- Create: `frontend/src/app/pages/client-offers/offer-add-item-dialog.component.ts`
- Modify: `frontend/src/app/shared/client-offer.ts` (тип `EquipmentHit`)
- Modify: `frontend/src/app/services/api.service.ts` (`searchEquipment`)
- Modify: `frontend/src/app/pages/client-offers/client-offer-items.component.ts`

**Interfaces:**
- Consumes: `GET /api/equipment/search` (задача 5), `GET /api/registry/search` (задача 1), PUT с `medEquipmentId` и `CONFIRMED` у новой строки (задача 2).
- Produces: `<app-offer-add-item-dialog [kz] (picked) (close)>`; `type AddPick = { kind: 'registry'; record: RegistryRecord } | { kind: 'catalog'; item: EquipmentHit }`.

- [ ] **Step 1: `shared/client-offer.ts`** — тип:

```ts
/** Позиция каталога в поиске «Из реестра и каталога»: РУ — если привязан. */
export interface EquipmentHit { id: number; name: string; manufact: string | null; registry: RegistryRecord | null; }
```

`api.service.ts`:

```ts
  /** Каталог своего рынка по названию и производителю (спека 2026-10-13 §2.4). */
  searchEquipment(q: string, limit = 8): Observable<EquipmentHit[]> {
    return this.http.get<EquipmentHit[]>(`${this.base}/equipment/search`, { params: { q, limit } });
  }
```

- [ ] **Step 2: `offer-add-item-dialog.component.ts`**

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnDestroy, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { regValidity } from '../../shared/private-request';
import type { RegistryRecord } from '../../shared/private-request';
import type { EquipmentHit } from '../../shared/client-offer';
import { regExpired } from '../../shared/registry';
import { marketToday } from '../../shared/deadline';

export type AddPick = { kind: 'registry'; record: RegistryRecord } | { kind: 'catalog'; item: EquipmentHit };

/** «Из реестра и каталога» (спека 2026-10-13 §2.4): поиск — две группы, выбор добавляет позицию в конец КП. */
@Component({
  selector: 'app-offer-add-item-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="veil" (click)="close.emit()"></div>
    <div class="dlg" role="dialog" aria-label="Добавить позицию из реестра и каталога" (click)="$event.stopPropagation()">
      <div class="head">
        <h3>Из реестра и каталога</h3>
        <button type="button" class="btn btn-line" (click)="close.emit()" aria-label="Закрыть">✕</button>
      </div>
      <input type="search" [(ngModel)]="query" (input)="onQuery()" placeholder="Название, модель или производитель"
             aria-label="Поиск в реестре и каталоге" />
      <p class="hint" *ngIf="loading">Ищу…</p>
      <p class="hint" *ngIf="!loading && searched && !catalog.length && !registry.length">Ничего не нашлось</p>
      <div class="group" *ngIf="catalog.length">
        <div class="g-title">Каталог</div>
        <button type="button" class="hit" *ngFor="let e of catalog" (click)="picked.emit({ kind: 'catalog', item: e })">
          <span class="h-name">{{ e.name }}</span>
          <span class="h-meta">{{ e.manufact || 'производитель не указан' }}<ng-container *ngIf="e.registry"> · РУ № {{ e.registry.regNumber }}</ng-container>
            <ng-container *ngIf="!e.registry"> · РУ не привязан</ng-container></span>
        </button>
      </div>
      <div class="group" *ngIf="registry.length">
        <div class="g-title">Реестр НЦЭЛС</div>
        <button type="button" class="hit" *ngFor="let r of registry" (click)="picked.emit({ kind: 'registry', record: r })">
          <span class="h-name">{{ r.name }}</span>
          <span class="h-meta" [class.expired]="expired(r)">{{ r.producer || '—' }}<ng-container *ngIf="r.country"> · {{ r.country }}</ng-container>
            · № {{ r.regNumber }}<ng-container *ngIf="validity(r)"> · {{ validity(r) }}</ng-container><ng-container *ngIf="expired(r)"> — срок истёк</ng-container></span>
        </button>
      </div>
      <p class="error-banner" *ngIf="error">{{ error }}</p>
    </div>
  `,
  styles: [`
    .veil { position: fixed; inset: 0; background: rgba(17, 24, 39, .5); z-index: 40; }
    .dlg { position: fixed; z-index: 41; top: 10vh; left: 50%; transform: translateX(-50%); width: 560px; max-width: calc(100vw - 32px);
           max-height: 80vh; overflow-y: auto; background: var(--surface); border: 1px solid var(--border); border-radius: 12px;
           box-shadow: var(--shadow-lg); padding: 16px; display: flex; flex-direction: column; gap: 10px; }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    h3 { margin: 0; font-size: 16px; }
    input[type="search"] { padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); color: var(--text); font-size: 14px; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .group { display: flex; flex-direction: column; gap: 4px; }
    .g-title { font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); }
    .hit { width: 100%; text-align: left; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px;
           cursor: pointer; display: flex; flex-direction: column; gap: 2px; color: var(--text); min-height: 40px; }
    .hit:hover { background: var(--surface-2); }
    .h-name { font-size: 13px; overflow-wrap: anywhere; }
    .h-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .h-meta.expired { color: var(--danger-text); }
    @media (max-width: 900px) {
      .dlg { top: 0; left: 0; transform: none; width: 100vw; max-width: 100vw; height: 100vh; max-height: 100vh; border-radius: 0; }
      input[type="search"] { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class OfferAddItemDialogComponent implements OnDestroy {
  /** Реестр РУ — только Казахстан; на РФ — только каталог. */
  @Input() kz = false;
  @Output() picked = new EventEmitter<AddPick>();
  @Output() close = new EventEmitter<void>();

  query = '';
  catalog: EquipmentHit[] = [];
  registry: RegistryRecord[] = [];
  loading = false;
  searched = false;
  error = '';
  private timer: any = null;
  private sub?: Subscription;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  @HostListener('document:keydown.escape')
  onEsc() { this.close.emit(); }

  ngOnDestroy() { clearTimeout(this.timer); }

  validity(r: RegistryRecord) { return regValidity(r); }
  expired(r: RegistryRecord) { return regExpired(r, marketToday('KZ')); }

  onQuery() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), 300);
  }

  private search() {
    const q = this.query.trim();
    this.sub?.unsubscribe();
    this.error = '';
    if (q.length < 2) { this.catalog = []; this.registry = []; this.searched = false; this.cdr.markForCheck(); return; }
    this.loading = true;
    let failed = '';
    const fail = (what: string) => catchError(() => { failed = what; return of([]); });
    this.sub = forkJoin({
      catalog: this.api.searchEquipment(q, 8).pipe(fail('каталог')),
      registry: this.kz ? this.api.searchRegistry(q, 8).pipe(fail('реестр')) : of([] as RegistryRecord[]),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(r => {
      this.catalog = r.catalog as EquipmentHit[];
      this.registry = r.registry as RegistryRecord[];
      this.loading = false;
      this.searched = true;
      this.error = failed ? `Поиск не ответил: ${failed} — повторите` : '';
      this.cdr.markForCheck();
    });
  }
}
```

- [ ] **Step 3: `client-offer-items.component.ts`** — кнопка в панели (после «Проверить регистрацию»), окно и сборка строки:

```html
      <button type="button" class="btn btn-line" (click)="addOpen = true">Из реестра и каталога</button>
```

```html
    <app-offer-add-item-dialog *ngIf="addOpen" [kz]="isKz" (picked)="addPicked($event)" (close)="addOpen = false"></app-offer-add-item-dialog>
```

(в `imports` — `OfferAddItemDialogComponent`; тип `AddPick` — из того же файла.)

```ts
  addOpen = false;

  /** Позиция из реестра (сразу «РУ ✓») или каталога (РУ — если привязан) — в конец КП (спека 2026-10-13 §2.4). */
  addPicked(p: AddPick) {
    const prof = this.profile;
    const it = newItem('ITEM', prof?.vatDefault ?? null);
    const confirm = (rec: RegistryRecord) => {
      it.registrationStatus = 'CONFIRMED';
      it.regNumber = rec.regNumber;
      it.registry = rec;
      if (!it.manufacturer && rec.producer) it.manufacturer = rec.producer.slice(0, 500);
      if (rec.country) it.country = rec.country.slice(0, 200);
      if (this.offer.vatEnabled && prof && 'vatRegistered' in prof) it.vatRate = prof.vatRegistered;
    };
    if (p.kind === 'registry') {
      it.name = cutWords(p.record.name, 500);
      confirm(p.record);
    } else {
      it.name = p.item.name;
      it.manufacturer = p.item.manufact ?? null;
      it.medEquipmentId = p.item.id;
      if (p.item.registry) confirm(p.item.registry);
    }
    this.offer.items.push(it);
    this.addOpen = false;
    this.emit();
    this.cdr.markForCheck();
  }
```

и функция модуля:

```ts
/** Длинное наименование записи реестра — до max по слову, с «…» (полное — в окошке метки РУ). */
function cutWords(s: string, max: number): string {
  if (s.length <= max) return s;
  const cut = s.slice(0, max - 1);
  const sp = cut.lastIndexOf(' ');
  return (sp > max / 2 ? cut.slice(0, sp) : cut).trimEnd() + '…';
}
```

- [ ] **Step 4: Build**

Run: `npm --prefix frontend run build`
Expected: `Output location: …`, без ошибок.

- [ ] **Step 5: Живьём** — KZ: «Из реестра и каталога» → «дефибриллятор» → две группы; выбор из реестра → позиция в конце, «РУ ✓», производитель и страна, текст после автосохранения; выбор из каталога (с РУ и без) → связь сохраняется; RF: только «Каталог»; 390 px — окно во весь экран.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/client-offers/offer-add-item-dialog.component.ts frontend/src/app/shared/client-offer.ts frontend/src/app/services/api.service.ts frontend/src/app/pages/client-offers/client-offer-items.component.ts
git commit -m "feat(client-offer): «Из реестра и каталога» — позиция из реестра НЦЭЛС или каталога

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Живая проверка, документация, полный прогон

**Files:**
- Modify: `CLAUDE.md` (§8 — блок «КП клиенту — реестр и каталог в редакторе (волна 2, часть 3)»; §12 — фронт; §13 — число тестов; §15 — пути; §16 — что не делалось), `docs/PROGRESS.md` («▶ Последняя задача»)
- Память: `~/.claude/projects/-Users-vlad-IdeaProjects-AIS/memory/last-task-pointer.md`, `MEMORY.md`

- [ ] **Step 1: Стек** (sandbox off): бэкенд `JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun`, фронт `npm --prefix frontend start`. Записать до проверки: `select max(id) from client_offer; select max(id) from tender; select next_number from company_profile where market = 'KZ';`

- [ ] **Step 2: Сценарий (Playwright, 1280 и 390, обе темы)**
  1. КП KZ: «+ Позиция» × 3 — «Дефибриллятор ДКИ-Н-12» / производитель «Концерн Аксион»; «Кушетка ZZ несуществующая»; позиция с наименованием записи реестра с истёкшим сроком (`select reg_number, name, producer from med_registry where not unlimited and expiration_date < current_date limit 1;`).
  2. «Проверить регистрацию» → «РУ?» у первой, «нет в реестре» (красная) у второй, тост с числами; сводка «не зарегистрировано: 3».
  3. Первая: «Подтвердить» → «РУ ✓», ставка 5 %, текст «№ … от … г., бессрочно» в «Подробнее» после автосохранения; сводка −1.
  4. Третья: «Найти в реестре» → её запись → «РУ истекло ДД.ММ.ГГГГ» (красная), в сводке остаётся.
  5. Вторая: «Не подлежит» → «не подлежит», ставка 16 %; сводка −1.
  6. «Из реестра и каталога» → «рециркулятор передвижной» → запись реестра → новая позиция «РУ ✓»; каталог → позиция каталога.
  7. PDF / Word: колонка «Регистрация в РК» — у подтверждённых текст, у «не подлежит» — «Не подлежит регистрации», у остальных пусто.
  8. Копия строки «РУ ✓» → у копии «РУ ✓» после сохранения.
  9. Оператор: метки видны, окошко без действий, кнопок «Проверить регистрацию» и «Из реестра и каталога» нет.
  10. РФ-КП: меток нет, «РУ не указано» как раньше, «Из реестра и каталога» — только каталог.
  11. Частная заявка: метка РУ работает как раньше (подтвердить, найти, «не подлежит»); подписи — новые.

- [ ] **Step 3: Уборка** — СНАЧАЛА остановить бэкенд (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`), потом SQL: удалить КП и заявки проверки (id больше записанных), вернуть `company_profile.next_number` KZ; потом фронт (4200).

- [ ] **Step 4: Документация** — CLAUDE.md §8 (блок части 3: что сделано, решения плана, проверено), §12, §13, §15 (`POST /api/client-offers/registry-suggestions`, `GET /api/equipment/search`, `GET /api/registry/search` — поиск по словам; поля строки КП `regNumber`/`suggestionScore`/`medEquipmentId`/`registry`, статус `NOT_FOUND`), §16; PROGRESS «▶ Последняя задача»; память.

- [ ] **Step 5: Полный прогон**

Run (sandbox off): `./gradlew cleanTest test > .superpowers/sdd/2026-10-13-client-offer-registry/fullgate.log 2>&1`, затем подсчёт по `build/test-results/test/*.xml`.
Expected: 0 failures, 0 errors; skipped — 1 (калибровка).

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md docs/PROGRESS.md
git commit -m "docs: КП клиенту — реестр и каталог в редакторе (волна 2, часть 3)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 7: Финал** — ревью всей ветки (Opus) → одна волна исправлений (тест, красный до правки) → полный прогон → мерж в `main` fast-forward. Push — оператор (`! git push origin main`).
