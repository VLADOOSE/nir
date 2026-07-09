# Точный фильтр импорта goszakup (медтовары, без услуг) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Импорт goszakup оставляет тендеры с медицинскими ТОВАРАМИ (аппараты + расходка) и отсеивает мусор (дроны, «аппарат акима», медотходы, медосмотр, обучение) — за счёт чистки keyword-предфильтра по имени + новой проверки релевантности по лотам.

**Architecture:** Двухступенчато. Ступень 1 — keyword-предфильтр по `name_ru` (существует; чистим список). Ступень 2 (новое) — статический `MedicalRelevanceFilter.isRelevant(name, lotTexts)` по названиям лотов: лот = медтовар, если содержит POSITIVE-термин и не содержит NEGATIVE (услуга/не-медицина); тендер релевантен при ≥1 медтоварном лоте. Встраивается в `GoszakupImportService.importOne` после загрузки лотов, перед записью. Лоты и так качаются для кандидата — лишних запросов к API нет. Fix-forward: данные не чистим, схема БД не меняется.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JUnit 5, интеграция goszakup (существующая).

## Global Constraints

- **Спек:** `docs/superpowers/specs/2026-07-09-goszakup-medical-filter-design.md` (источник истины).
- **Что релевантно:** медицинские ТОВАРЫ (аппараты + расходка). Услуги («Услуги по…», «Работы по…», «Обучение…», медотходы, медосмотр) и не-медицина (дроны, «аппарат акима») — отсеиваем.
- **Правило фильтра:** лот — медтовар, если текст (`name_ru + " " + description_ru`) содержит POSITIVE-термин И не содержит NEGATIVE-термина. Тендер релевантен, если ≥1 лот — медтовар. Пустые лоты (сеть/404) → фолбэк по имени объявления тем же правилом.
- **Fix-forward:** существующие тендеры НЕ трогаем; никаких DELETE/миграций. Схема БД не меняется.
- **Без лишних запросов:** проверка ступени 2 использует лоты, которые `importOne` УЖЕ качает; новых API-вызовов не добавлять.
- **Токен goszakup:** живой импорт — только через env `GOSZAKUP_TOKEN` (лежит в `/tmp/goszakup.token`, вне репо, не эхо-печатать; читать `$(cat …)`).
- **БД/сборка:** `./gradlew` — с `dangerouslyDisableSandbox: true`; kill lingering `lsof -ti :8080 | xargs kill -9`. git/gradlew из корня репо.
- **Тест-гейт (§13 CLAUDE.md):** зелёный = падают ТОЛЬКО 2 пред-существующих `ApplyAutoFillServiceTest`. Существующие goszakup-тесты (`GoszakupDtoJsonTest`, `GoszakupHttpClientTest`, и если есть import-тест) не ломать.
- **Коммиты:** ветка `feat/goszakup-medical-filter` (создана); каждый заканчивать `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **Субагенты — наследуют модель сессии (§2 CLAUDE.md), `model` не переопределять.**

## File Structure

**Создаём:**
- `src/main/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilter.java` — статический фильтр релевантности по тексту лотов/имени.
- `src/test/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilterTest.java` — голден-тест.

**Меняем:**
- `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java` — встроить ступень 2 в `importOne`.
- `src/main/resources/application.yaml` — чистка дефолта `GOSZAKUP_KEYWORDS` (ступень 1).
- `CLAUDE.md` — §5 goszakup-импорт (в финальной задаче).

**Тест ступени 1 (нет регресса охвата)** — внутри `MedicalRelevanceFilterTest` или отдельный маленький тест на список keywords (см. Task 2).

---

### Task 1: `MedicalRelevanceFilter` + голден-тест

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilter.java`
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilterTest.java`

**Interfaces:**
- Produces:
  - `public static boolean MedicalRelevanceFilter.isRelevant(String announcementName, List<String> lotTexts)` — тендер релевантен (≥1 медтоварный лот; пустые лоты → фолбэк по имени).
  - `static boolean MedicalRelevanceFilter.isMedicalGoods(String text)` — один текст: POSITIVE ∧ ¬NEGATIVE (package-private, для теста).

- [ ] **Step 1: Failing-тест (голден-набор из спека §4)**

```java
package com.vladoose.nir.integration.goszakup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MedicalRelevanceFilterTest {

    // --- один текст: медтовар или нет ---
    @Test
    void dropsNonMedicalAndServices() {
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Приобретения аппарат летательный беспилотный (дрон)")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Государственные закупки для ГУ Аппарат акима сельского округа")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Услуги по удалению медицинских опасных отходов класса Б")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("услуги планового медицинского осмотра работников")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("работы по пошиву медицинских халатов")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Офлайн обучение среднего медицинского персонала")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Текущий ремонт и монтаж натяжного потолка в актовом зале")).isFalse();
    }

    @Test
    void keepsMedicalGoods() {
        assertThat(MedicalRelevanceFilter.isMedicalGoods("УЗИ-сканер Mindray DC-70")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Аппарат искусственной вентиляции лёгких реанимационный")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Перчатки нитриловые смотровые стерильные")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Реагенты для гематологического анализатора")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Рентгеновский аппарат стационарный")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Изделия медицинского назначения одноразовые")).isTrue();
    }

    // --- тендер: ≥1 медтоварный лот → релевантен ---
    @Test
    void tenderRelevantIfAnyLotIsMedicalGoods() {
        // тендер «Перчатки + Покрывало» — остаётся ради перчаток
        assertThat(MedicalRelevanceFilter.isRelevant("Закуп изделий",
                List.of("Перчатки нитриловые стерильные", "Покрывало изотермическое спасательное"))).isTrue();
    }

    @Test
    void tenderDroppedIfAllLotsAreServicesOrNonMedical() {
        assertThat(MedicalRelevanceFilter.isRelevant("Разное",
                List.of("Услуги по удалению медицинских отходов", "обучение медперсонала"))).isFalse();
    }

    @Test
    void emptyLotsFallBackToAnnouncementName() {
        assertThat(MedicalRelevanceFilter.isRelevant("Аппарат ИВЛ экспертного класса", List.of())).isTrue();
        assertThat(MedicalRelevanceFilter.isRelevant("Аппарат акима села", List.of())).isFalse();
        assertThat(MedicalRelevanceFilter.isRelevant("Услуги медицинского осмотра", null)).isFalse();
    }
}
```

- [ ] **Step 2: Прогнать — падает**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.MedicalRelevanceFilterTest'` (sandbox off).
Expected: FAIL — класса `MedicalRelevanceFilter` нет.

- [ ] **Step 3: Реализовать `MedicalRelevanceFilter`**

```java
package com.vladoose.nir.integration.goszakup;

import java.util.List;
import java.util.Locale;

/**
 * Релевантность тендера goszakup: оставляем медицинские ТОВАРЫ (аппараты + расходка),
 * отсеиваем услуги (медотходы, медосмотр, обучение, ремонт) и не-медицину (дроны, «аппарат акима»).
 * Ступень 2 импорта — судим по названиям ЛОТОВ, а не по имени объявления.
 * Правило: лот — медтовар, если POSITIVE ∧ ¬NEGATIVE; тендер релевантен при ≥1 медтоварном лоте.
 */
public final class MedicalRelevanceFilter {

    /** Медицинский товар: виды МИ + расходка/изделия. */
    private static final List<String> POSITIVE = List.of(
            "узи", "ультразвук", "эхокардиограф", "рентген", "флюорограф", "маммограф", "ангиограф",
            "томограф", "мрт", "ивл", "вентиляц", "наркоз", "анестезиолог",
            "анализатор", "гематологич", "биохимич", "коагулометр", "центрифуг", "микроскоп",
            "стерилизатор", "автоклав", "эндоскоп", "гастроскоп", "колоноскоп", "бронхоскоп", "лапароскоп",
            "дефибрил", "монитор пациента", "прикроватн монитор", "кардиограф", "электрокардиограф", "экг",
            "спирометр", "инкубатор", "облучатель", "физиотерап", "электрофорез", "магнитотерап",
            "отсасыватель", "аспиратор", "оксиметр", "тонометр", "глюкометр", "коагулятор",
            "стоматологическ", "дентальн", "хирургическ", "операционн стол", "операционн светильник",
            "перчат", "шприц", "катетер", "зонд медицин", "бинт", "пластыр", "электрод", "реагент",
            "тест-систем", "изделие медицинск", "изделия медицинск", "медицинского назначения",
            "расходн материал", "имплант", "протез", "шовн материал", "игл", "скальпел", "пробирк");

    /** Услуга/работы/не-медицина — лот НЕ товар. */
    private static final List<String> NEGATIVE = List.of(
            "услуг", "работы по", "обучен", "осмотр", "утилизац", "удаление", "отход", "ремонт",
            "монтаж", "обслуживан", "замер", "аренд", "страхован", "пошив", "стирк",
            "летательн", "беспилотн", "дрон", "потолок");

    private MedicalRelevanceFilter() {}

    /** Тендер релевантен, если ≥1 лот — медтовар. Пустые лоты (сеть/404) → судим по имени объявления. */
    public static boolean isRelevant(String announcementName, List<String> lotTexts) {
        if (lotTexts != null && !lotTexts.isEmpty()) {
            return lotTexts.stream().anyMatch(MedicalRelevanceFilter::isMedicalGoods);
        }
        return isMedicalGoods(announcementName);
    }

    /** Текст — медицинский товар: содержит POSITIVE и НЕ содержит NEGATIVE. */
    static boolean isMedicalGoods(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        if (NEGATIVE.stream().anyMatch(t::contains)) return false;
        return POSITIVE.stream().anyMatch(t::contains);
    }
}
```

- [ ] **Step 4: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.MedicalRelevanceFilterTest'` (sandbox off).
Expected: PASS (6 тестов). Если какой-то голден-кейс падает — подправить POSITIVE/NEGATIVE, НЕ ослабляя правило (услуги/дроны остаются ДРОП, медтовары ОСТАВИТЬ).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilter.java src/test/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilterTest.java && git commit -m "feat(goszakup): MedicalRelevanceFilter — медтовары по лотам (услуги/дроны отсеиваются)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Встроить ступень 2 в импорт + почистить keyword-предфильтр

**Files:**
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java` (`importOne`)
- Modify: `src/main/resources/application.yaml` (`GOSZAKUP_KEYWORDS`)
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportRelevanceTest.java` (новый, лёгкий)

**Interfaces:**
- Consumes: `MedicalRelevanceFilter.isRelevant` (Task 1), `LotDto.getNameRu()/getDescriptionRu()`, `client.fetchLots`, `writer.upsertOne`.
- Produces: `importOne` не пишет тендер, если лоты не прошли релевантность (`skipped++`); keyword-дефолт ступени 1 — без «аппарат»/«медицинск», плюс медтоварные термины.

- [ ] **Step 1: Встроить фильтр в `importOne`**

Найти в `GoszakupImportService.importOne` строку `List<LotDto> lots = client.fetchLots(d.getNumberAnno());` и вставить ПОСЛЕ неё (перед `GoszakupTenderWriter.Result r = writer.upsertOne(...)`):

```java
            List<String> lotTexts = lots.stream()
                    .map(l -> ((l.getNameRu() == null ? "" : l.getNameRu()) + " "
                             + (l.getDescriptionRu() == null ? "" : l.getDescriptionRu())).trim())
                    .toList();
            if (!MedicalRelevanceFilter.isRelevant(d.getNameRu(), lotTexts)) {
                sum.setSkipped(sum.getSkipped() + 1); // ступень 2: лоты — не медтовар
                return;
            }
```
`MedicalRelevanceFilter` в том же пакете (`integration.goszakup`) — импорт не нужен. Проверить компиляцию: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileJava` (sandbox off).

- [ ] **Step 2: Почистить keyword-дефолт ступени 1 в `application.yaml`**

Заменить строку `keywords: ${GOSZAKUP_KEYWORDS:…}` (убрать голое «аппарат» и «медицинск», добавить медтоварные термины):

```yaml
    keywords: ${GOSZAKUP_KEYWORDS:узи,ультразвук,томограф,рентген,флюорограф,маммограф,монитор пациента,дефибриллятор,ивл,вентиляц,наркоз,анализатор,центрифуг,стерилизатор,автоклав,эндоскоп,гастроскоп,колоноскоп,электрокардиограф,кардиомонитор,инкубатор,облучатель,спирометр,хирургическ,реанимац,эхокардиограф,ангиограф,коагулометр,микроскоп,дефибрил,перчат,шприц,катетер,реагент,тест-систем,изделие медицинск,изделия медицинск,медицинского назначения,расходн материал,имплант,протез,стоматолог,дентальн,физиотерап}
```

> Обоснование: «аппарат» ловил дроны/«аппарат акима»; «медицинск» ловил медотходы/медосмотр/медхалаты. Реальные аппараты названы типом («аппарат **ивл**», «**рентген**-аппарат») — покрыты специфичными словами. «изделие медицинск»/«медицинского назначения» — фразы для реальных товарных объявлений.

- [ ] **Step 3: Failing-тест ступени 1 (нет регресса охвата) + ступень 2 через фильтр**

Новый файл `GoszakupImportRelevanceTest.java` — проверяет, что типовые девайс-названия содержат keyword из ступени-1 списка, и что фильтр ступени-2 корректно решает по лотам. (Без Spring/сети — проверяем контракт статикой + сам список keywords читаем из фильтра? нет — из yaml сложно; тестируем инвариант: специфичные девайс-слова присутствуют в наборе, и фильтр решает верно.)

```java
package com.vladoose.nir.integration.goszakup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class GoszakupImportRelevanceTest {

    // Ступень-1 список (дефолт yaml) — держим копию инварианта: реальные девайсы покрыты специфичным словом.
    private static final List<String> STAGE1 = List.of(
            "узи","ультразвук","томограф","рентген","флюорограф","маммограф","монитор пациента","дефибриллятор",
            "ивл","вентиляц","наркоз","анализатор","центрифуг","стерилизатор","автоклав","эндоскоп","гастроскоп",
            "колоноскоп","электрокардиограф","кардиомонитор","инкубатор","облучатель","спирометр","хирургическ",
            "реанимац","эхокардиограф","ангиограф","коагулометр","микроскоп","дефибрил","перчат","шприц","катетер",
            "реагент","тест-систем","изделие медицинск","изделия медицинск","медицинского назначения",
            "расходн материал","имплант","протез","стоматолог","дентальн","физиотерап");

    private static boolean passesStage1(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return STAGE1.stream().anyMatch(n::contains);
    }

    @Test
    void stage1KeepsRealDeviceNames() {
        assertThat(passesStage1("Аппарат искусственной вентиляции лёгких")).isTrue();   // ивл? нет — «вентиляц»
        assertThat(passesStage1("Рентгеновский аппарат стационарный")).isTrue();        // рентген
        assertThat(passesStage1("Аппарат УЗИ экспертного класса")).isTrue();            // узи
        assertThat(passesStage1("Наркозно-дыхательный аппарат")).isTrue();              // наркоз
        assertThat(passesStage1("Изделия медицинского назначения")).isTrue();           // медицинского назначения
    }

    @Test
    void stage1DropsGovtAndDrones() {
        assertThat(passesStage1("Государственные закупки для ГУ Аппарат акима")).isFalse();
        assertThat(passesStage1("Приобретения аппарат летательный (дрон)")).isFalse();
    }

    @Test
    void stage2FilterGatesByLots() {
        // прошёл бы ступень-1 по «хирургическ», но лоты — услуга → дроп
        assertThat(MedicalRelevanceFilter.isRelevant("Хирургическое отделение",
                List.of("Услуги по ремонту хирургического оборудования"))).isFalse();
        // лоты — медтовар → релевантен
        assertThat(MedicalRelevanceFilter.isRelevant("Закуп",
                List.of("Перчатки хирургические стерильные"))).isTrue();
    }
}
```

> Примечание: `stage1KeepsRealDeviceNames` держит КОПИЮ списка из yaml как инвариант охвата. Если реализатор меняет дефолт yaml — синхронизировать `STAGE1` в тесте (или тест намеренно ловит расхождение). Цель — зафиксировать, что специфичные девайс-слова присутствуют.

- [ ] **Step 4: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.GoszakupImportRelevanceTest' --tests 'com.vladoose.nir.integration.goszakup.MedicalRelevanceFilterTest'` (sandbox off).
Expected: PASS. `stage1KeepsRealDeviceNames` подтверждает: удаление «аппарат» не уронило охват (каждый девайс покрыт специфичным словом).

- [ ] **Step 5: Регресс — существующие goszakup + вся сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test` (sandbox off).
Expected: падают ТОЛЬКО 2 `ApplyAutoFillServiceTest`. Существующие `Goszakup*Test` зелёные (мы не трогали клиент/DTO/писатель).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java src/main/resources/application.yaml src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportRelevanceTest.java && git commit -m "feat(goszakup): ступень 2 релевантности по лотам в importOne + чистка keyword-предфильтра

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Живая проверка (реальный импорт токеном) + CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (§5 goszakup-импорт)

- [ ] **Step 1: Поднять бэк с токеном + фронт**

Токен уже в `/tmp/goszakup.token`. Backend: `cd /Users/vlad/IdeaProjects/AIS && GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" ./gradlew bootRun` (sandbox off, фон; не эхо-печатать токен). Frontend: `cd frontend && npm start` (фон). Дождаться :8080/:4200.

- [ ] **Step 2: Реальный импорт + проверка**

Логин admin/admin, рынок KZ, `/tenders`, кнопка «Обновить тендеры» (синхронно ~2–3 мин; поллинг статуса). После завершения:
- В summary-прогрессе `skipped` заметно вырос (ступень 2 отсеивает).
- В списке активных KZ-тендеров БОЛЬШЕ НЕТ новых дронов/медотходов/медосмотра; появились реальные медтовары. (Старый мусор до фикса остаётся до истечения дедлайна — fix-forward.)
- Проверка БД: `PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -tA -c "SELECT count(*) FROM tender WHERE market='KZ' AND status='ACTIVE';"` (sandbox off) — сравнить состав, не только число.
Снять скриншот `goszakup-medical-filter.png` (список активных без мусора).

> Если live-импорт упрётся в протухший токен (401/«токен не настроен») — зафиксировать в отчёте, что фильтр-код проверен юнит-тестами (Task 1/2), а живой прогон требует свежего токена (перевыпуск оператором). Не блокер для мержа.

- [ ] **Step 3: Обновить CLAUDE.md (§5, блок goszakup-импорта)**

Добавить: импорт-фильтр стал двухступенчатым — ступень 1 keyword-предфильтр по имени (почищен: без «аппарат»/«медицинск», + медтоварные термины), ступень 2 `MedicalRelevanceFilter` по названиям лотов (медтовар = POSITIVE ∧ ¬NEGATIVE; тендер релевантен при ≥1 медтоварном лоте; пустые лоты → фолбэк по имени) в `importOne` перед `upsertOne` — без доп. API-запросов; `skipped` теперь включает лот-дропы. Fix-forward (старые данные не чистятся). Причина: keyword-по-имени ловил дроны/«аппарат акима»/медотходы/медосмотр.

- [ ] **Step 4: Commit + финиш**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md goszakup-medical-filter.png 2>/dev/null; git add CLAUDE.md; git commit -m "docs: CLAUDE.md — двухступенчатый фильтр импорта goszakup (медтовары по лотам)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Затем — whole-branch review (superpowers:requesting-code-review) и finishing-a-development-branch (merge --ff-only в main, удалить ветку).

---

## Self-Review (выполнено при написании)

- **Покрытие спека:** §3 ступень 1 (чистка keywords) → Task 2 Step 2; ступень 2 → Task 2 Step 1; §4 `MedicalRelevanceFilter` (POSITIVE/NEGATIVE/правило/фолбэк) → Task 1; §5 встройка в `importOne` → Task 2 Step 1; §6 тесты (голден, тендер-уровень, фолбэк, нет-регресса-ступени-1) → Task 1 + Task 2 Step 3; живая проверка → Task 3; §7 без миграций — соблюдено.
- **Отклонения:** нет (следует спеку).
- **Плейсхолдеры:** POSITIVE/NEGATIVE-списки — конкретные (не «TBD»); голден-тест их пинит; при промахе кейса реализатор тюнит, не ослабляя правило. Не плейсхолдер.
- **Согласованность типов:** `isRelevant(String, List<String>)` и `isMedicalGoods(String)` (Task 1) ↔ вызовы в `importOne` (Task 2) и тестах; `sum.setSkipped(sum.getSkipped()+1)` — существующий паттерн `ImportSummary`; `LotDto.getNameRu()/getDescriptionRu()` — реальные Lombok-геттеры.
- **Риск (отмечен):** `GoszakupImportRelevanceTest.STAGE1` держит копию yaml-списка — при правке дефолта синхронизировать (тест это и ловит).
