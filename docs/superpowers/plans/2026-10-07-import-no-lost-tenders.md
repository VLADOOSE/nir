# Импорт тендеров: «не терять тендеры» (пакет 1) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Профильные тендеры goszakup и СК-Фармации попадают в АИС целиком; всё, что не попало или попало не целиком, видно ошибкой с причиной в итоге прогона.

**Architecture:** Общий словарь медизделий для обоих фильтров релевантности; общий HTTP-слой импорта (`integration/http`: дедлайн на весь обмен, предел тела, признак «можно повторить» + `UpstreamRetry`); полнота лотов (пагинация goszakup, сверка с числом лотов СК-Фармации, слияние без удаления при неполном списке); нормализация количества/суммы. Замер «до/после» — на замороженном корпусе реальных объявлений.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JDK `HttpClient`, Jackson, Jsoup, JUnit 5 + AssertJ, PostgreSQL (nirdb) для `@SpringBootTest`.

**Spec:** `docs/superpowers/specs/2026-10-07-import-no-lost-tenders-design.md` (находки и приёмка — `docs/reviews/2026-10-06-import-review.md`, раздел «Пакет 1»).

## Global Constraints

- Ветка `feature/import-no-lost-tenders`; каждый коммит заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Все `./gradlew` и запросы к БД — с выключенной песочницей (localhost:5432); перед `./gradlew test` убить `bootRun` (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`) и `devMail` (`lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`).
- Подтверждать зелёное — `./gradlew cleanTest test` (без `cleanTest` может прийти `UP-TO-DATE` из кеша). Гейт: 0 падений.
- Сеть — вне транзакций; запись тендера — в `@Transactional`-методе отдельного бина-райтера (CLAUDE.md §6). Фоновые потоки импорта уже ставят `MarketContext` — не трогать.
- Тексты ошибок для оператора — русские, «где: что», без имён Java-классов в середине (`ErrorText.of`), без URL с токеном и без самого токена goszakup. Токен — только в заголовке `Authorization`.
- Регулярные выражения с кириллицей и `\b`/`\w`/классами букв — с флагом `U` (`(?iU)`), иначе на JDK 19+ они перестанут совпадать.
- Токен goszakup для живых шагов — `$(cat ~/.config/ais/goszakup.token)`; содержимое НИКОГДА не печатать.
- Лотами тендера управлять только через коллекцию `t.getLots()` (orphanRemoval, CLAUDE.md §7).
- Схема БД не меняется (миграций нет).
- Новых особых тестовых контекстов (`@MockitoBean`, `@TestPropertySource`) не заводить без нужды — каждый +10 соединений к nirdb (CLAUDE.md §14). Чистые классы тестировать без Spring.
- Мутационная проверка: мутацию делать копией файла (`cp X /tmp/X.bak` … `cp /tmp/X.bak X`), после серии — `./gradlew compileJava` (CLAUDE.md §14).

## Review Focus

1. **Описание лота goszakup упоминает «монтаж, обучение персонала»** — у поставки изделия это норма; лот должен остаться профильным (раньше NEGATIVE по всему тексту его убивал). Тест — Task 2.
2. **СК-Фармация: страница лотов посреди обхода пришла без таблицы** (троттлинг/ошибка портала) — список неполный, существующие лоты НЕ удаляются, в итоге ошибка «на площадке K, получено M». Тест — Task 8.
3. **goszakup отвечает 400/403 на ленту больницы** — не повторяется трижды (впустую жжём лимит), сразу в ошибки прогона; 503 и обрыв — повторяются. Тест — Task 4 и Task 7.
4. **`subject` недоступен после повторов у уже существующего тендера** — тендер обновляется, но `customerName`/`regionKato`/`deliveryAddress` не затираются пустыми. Тест — Task 7.
5. **Количество «1.00» у СК-Фармации** — остаётся 1 (регресс к «100» уже ловили); «2.5» → пусто + строка «Количество на площадке: 2,5». Тест — Task 9.

---

## File Structure

**Создать:**
- `src/main/java/com/vladoose/nir/integration/MedicalGoodsVocabulary.java` — словарь терминов (изделия сильные/общие, услуги сильные/слабые, лекарства) и матчинг стемов по началу слова.
- `src/main/java/com/vladoose/nir/integration/LotText.java` — `record LotText(String name, String description)`.
- `src/main/java/com/vladoose/nir/integration/ImportQuantity.java` — нормализация количества/суммы и текст пометки.
- `src/main/java/com/vladoose/nir/integration/http/UpstreamHttp.java` — обмен с дедлайном на весь ответ и пределом тела (для импорта; тексты с причиной).
- `src/main/java/com/vladoose/nir/integration/http/UpstreamIoException.java` — сбой обмена (`Kind`: TIMEOUT / IO / TOO_LARGE / INTERRUPTED).
- `src/main/java/com/vladoose/nir/integration/http/RetryableFailure.java` — интерфейс «можно повторить».
- `src/main/java/com/vladoose/nir/integration/http/UpstreamRetry.java` — повторы (3 попытки, паузы 1 с и 3 с) с внедряемым `Sleeper`.
- `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupCallException.java` — `IllegalStateException` + `RetryableFailure`.
- `src/main/java/com/vladoose/nir/integration/skpharmacy/SkCallException.java` — `UpstreamException` + `RetryableFailure`.
- `src/test/java/com/vladoose/nir/tools/ImportCorpusSnapshot.java` — утилита снятия корпуса (не тест, как `DevMailServer`).
- `src/test/java/com/vladoose/nir/integration/corpus/ImportCorpus.java` — чтение корпуса.
- `src/test/java/com/vladoose/nir/integration/corpus/LegacyRelevanceFilters.java` — замороженная копия фильтров ДО правок (для «было»).
- `src/test/java/com/vladoose/nir/integration/corpus/ImportCorpusReportTest.java` — отчёт «было/стало» + golden-проверка.
- `src/test/resources/import-corpus/goszakup-zko.jsonl.gz`, `sk-first5.jsonl.gz`, `golden-lots.tsv`.

**Переместить** (пакет `integration/whatsapp` → `integration/http`, у потребителей только импорты): `LimitedBytes.java`, `FileTooLargeException.java`. `GatewayHttp`/`GatewayException` остаются в whatsapp (их тексты намеренно без причины — у Green-API токен в URL; импорту причина нужна — отсюда отдельный `UpstreamHttp`; отступление от текста спеки §5 осознанное).

**Изменить:** `MedicalRelevanceFilter`, `SkPharmacyRelevanceFilter`, `GoszakupHttpClient`, `GoszakupClient` (без изменения сигнатур), `GoszakupImportService`, `GoszakupTenderWriter`, `dto/LotDto` (`count` → `BigDecimal`), `SkPharmacyHttpClient`, `SkTechSpecHttpClient`, `SkPharmacyHtmlParser` (количество), `SkLot` (+ `rawQuantity`), `SkPharmacyImportService`, `SkPharmacyTenderWriter` (+ флаг `complete`), `application.yaml` (`skpharmacy.import.max-lot-pages: 200`), `build.gradle` (задача `importCorpus`). **Удалить:** `GoszakupRetry.java`, `GoszakupRetryTest.java` (заменены `UpstreamRetry`).

---

### Task 1: Замороженный корпус и отчёт «было»

**Files:**
- Create: `src/test/java/com/vladoose/nir/tools/ImportCorpusSnapshot.java`
- Create: `src/test/java/com/vladoose/nir/integration/corpus/ImportCorpus.java`
- Create: `src/test/java/com/vladoose/nir/integration/corpus/LegacyRelevanceFilters.java`
- Create: `src/test/java/com/vladoose/nir/integration/corpus/ImportCorpusReportTest.java`
- Create: `src/test/resources/import-corpus/goszakup-zko.jsonl.gz`, `sk-first5.jsonl.gz`, `golden-lots.tsv`
- Modify: `build.gradle` (задача `importCorpus`)

**Interfaces:**
- Produces: `ImportCorpus.load(String resource) → List<CorpusTender>`; `record CorpusTender(String platform, String anno, String name, Integer lotsCount, List<CorpusLot> lots)`; `record CorpusLot(String code, String name, String description)`; `LegacyRelevanceFilters.goszakup(CorpusTender) / sk(CorpusTender) → boolean`; golden TSV формата `platform<TAB>expected(1|0)<TAB>name<TAB>description`; `ImportCorpusReportTest` печатает строку `REPORT <platform> tenders old=<n> new=<n> lots old=<n> new=<n>`.

- [ ] **Step 1: Утилита снятия корпуса**

`ImportCorpusSnapshot` — `public static void main(String[] args)`; аргументы: `out-dir`, `bins-file`. Токен — из env `GOSZAKUP_TOKEN`. Сеть — JDK `HttpClient` (HTTP/1.1, таймаут 60 с), Jackson.
- goszakup: для каждого БИН из `bins-file` (по строке) — v3 POST `https://ows.goszakup.gov.kz/v3/graphql` с запросом
  `query($o:String,$l:Int,$a:Int){ TrdBuy(filter:{orgBin:$o}, limit:$l, after:$a){ id numberAnno nameRu publishDate systemId } }`, `l=50`, страницы по `extensions.pageInfo.lastId` пока `hasNextPage`, стоп — вся страница старше 30 дней. Берём `systemId == 3 || null`. По каждому объявлению — `GET /v2/lots/number-anno/{anno}?limit=500` (проверено живьём 2026-10-07: `limit=500` принимается, у тендера 17737448-1 при 50 — 50 и `next_page`, при 500 — все 76), плюс переход по `next_page`, если не пуст. Пауза 100 мс между запросами. Ошибка объявления — в stderr и дальше.
- СК-Фармация: страницы 1–5 `https://fms.ecc.kz/ru/searchanno?page=N` → `SkPharmacyHtmlParser.parseSearch` (ТОТ ЖЕ код, что в проде — CLAUDE.md §14); по каждому объявлению — лоты всех страниц через `SkPharmacyHtmlParser.parseLots`/`hasNextLotsPage` (предел 200 страниц, пауза 300 мс, User-Agent как в `SkPharmacyHttpClient`); `isServicesLotsPage` → объявление пропускаем (как в проде).
- Пишет JSON Lines в gzip: одна строка — `{"platform":"GOSZAKUP|SK_PHARMACY","anno":…,"name":…,"lotsCount":…|null,"lots":[{"code":…,"name":…,"description":…}]}`; `description` обрезать до 600 символов. Никаких контактов и БИН в файл не писать.

```java
package com.vladoose.nir.tools;
// Снятие корпуса для ImportCorpusReportTest. Не тест: запускается `./gradlew importCorpus --args="<out-dir> <bins-file>"`.
public final class ImportCorpusSnapshot {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("usage: <out-dir> <bins-file>");
        String token = System.getenv("GOSZAKUP_TOKEN");
        if (token == null || token.isBlank()) throw new IllegalStateException("нет GOSZAKUP_TOKEN");
        java.nio.file.Path out = java.nio.file.Path.of(args[0]);
        java.util.List<String> bins = java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[1])).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        new ImportCorpusSnapshot(token).run(out, bins);
    }
    // … реализация по описанию выше: snapshotGoszakup(bins) → out/goszakup-zko.jsonl.gz, snapshotSk() → out/sk-first5.jsonl.gz
}
```

`build.gradle`, рядом с `devMail`:

```groovy
tasks.register('importCorpus', JavaExec) {
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'com.vladoose.nir.tools.ImportCorpusSnapshot'
}
```

- [ ] **Step 2: Снять корпус живьём**

```bash
cd /Users/vlad/IdeaProjects/AIS
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -At \
  -c "select inn from facility where market='KZ' and monitor_tenders and inn is not null and inn<>''" > /tmp/zko-bins.txt
wc -l /tmp/zko-bins.txt   # ожидается 29
GOSZAKUP_TOKEN="$(cat ~/.config/ais/goszakup.token)" ./gradlew importCorpus \
  --args="src/test/resources/import-corpus /tmp/zko-bins.txt"
ls -la src/test/resources/import-corpus
```
(sandbox off; ≈ 10–20 мин.) Ожидается два файла, каждый < 3 МБ. Если больше — уменьшить `description` до 300 символов.

- [ ] **Step 3: Чтение корпуса и замороженная копия старых фильтров**

`ImportCorpus.load("/import-corpus/goszakup-zko.jsonl.gz")` — `GZIPInputStream` + Jackson построчно → `List<CorpusTender>`.

`LegacyRelevanceFilters` — ДОСЛОВНАЯ копия текущих `MedicalRelevanceFilter` (списки POSITIVE/NEGATIVE, `isMedicalGoods`, `isRelevant`) и `SkPharmacyRelevanceFilter` (паттерны MED_NAME/EQUIP_HINT/DEVICE/MEDICINE, `nameCandidate`, `isDeviceLot`, `isRelevant`) на момент коммита `d14ed654`, с методами-адаптерами:

```java
static boolean goszakup(CorpusTender t) {   // как GoszakupImportService: текст лота = name + " " + description
    List<String> texts = t.lots().stream().map(l -> ((l.name() == null ? "" : l.name()) + " "
            + (l.description() == null ? "" : l.description())).trim()).toList();
    return OldGoszakup.isRelevant(t.name(), texts);
}
static boolean goszakupLot(CorpusLot l) { … isMedicalGoods(name + " " + description) … }
static boolean sk(CorpusTender t) {         // как SkPharmacyImportService: ступень 1, затем лоты по именам
    if (!OldSk.nameCandidate(t.name())) return false;
    return OldSk.isRelevant(t.name(), t.lots().stream().map(CorpusLot::name).toList());
}
static boolean skLot(CorpusLot l) { return OldSk.isDeviceLot(l.name()); }
```

Javadoc класса: «Замороженная копия фильтров на 2026-10-07 — только для отчёта „было“. Не править.»

- [ ] **Step 4: Отчёт «было» + каркас golden**

`ImportCorpusReportTest` (без Spring):
- `report()` — для каждой платформы считает число релевантных тендеров и профильных лотов старым фильтром и новым (`MedicalRelevanceFilter.isRelevant` / `SkPharmacyRelevanceFilter` — пока те же, что старые) и печатает `REPORT …`; ассертов нет, кроме `corpus.size() > 0`.
- `golden()` — читает `golden-lots.tsv` (строки, начинающиеся с `#`, — комментарии), для каждой строки `expected` сверяет с вердиктом НОВОГО лотового предиката платформы: goszakup — `MedicalRelevanceFilter.isMedicalLot(new LotText(name, description))` (появится в Task 2; до этого — временный адаптер к текущему `isMedicalGoods(name + " " + description)`, помеченный `// Task 2 заменит`), СК — `SkPharmacyRelevanceFilter.isDeviceLot(name)`. Собирает ВСЕ расхождения и падает одним сообщением со списком.
- Первичный `golden-lots.tsv`: строки из таблицы A2 ревью (все `1`: «Кушетка медицинская смотровая», «Операционный стол», «Светильник операционный», «Кислородный концентратор», «Шовный материал», «Расходные материалы для гемодиализа», «Кушетка для осмотра», «Аппарат УЗИ … с монтажом и обучением персонала» — последнее в колонке description), строки A1 ревью (все `1` для SK: «Анализатор биохимический», «Гастроскоп», «Бронхоскоп», «Микроскоп бинокулярный», «Центрифуга лабораторная», «Маммограф», «Насос инфузионный», «Капсула эндоскопическая», «Тест-полоски для глюкометра», «Экспресс-тест на тропонин», «Реагент для определения глюкозы в сыворотке крови»), и отрицательные (`0`) — из корпуса: ≥ 20 лекарств («Раствор для инфузий», «Таблетки … мг», «Вакцина…»), ≥ 10 услуг («Услуги по техническому обслуживанию…», «Ремонт аппарата УЗИ», «Медицинский осмотр…»), ≥ 10 хозтоваров/еды/стройки из корпуса.
- Разметка: `1` = медизделие/медтехника/расходник/реагент, которые может поставить West-Med; `0` = лекарства, услуги, еда, хозтовары, стройка, ИТ; спорное (дезсредства, спецодежда без мед. признака) в golden НЕ кладём — перечислить в комментарии файла `# спорное: …`.

- [ ] **Step 5: Прогон — «было» зафиксировано**

Run: `./gradlew cleanTest test --tests '*ImportCorpusReportTest*' -i | grep REPORT`
Expected: `report` PASS и две строки `REPORT`; `golden` FAIL с расхождениями по строкам A1/A2 (это и есть «было»). Записать обе строки `REPORT` и число расхождений golden в `.superpowers/sdd/2026-10-07-import-no-lost-tenders/progress.md` (раздел «Замер до»).

Чтобы гейт ветки не был красным между задачами: пометить `golden()` `@Disabled("включается в Task 3 — фильтры ещё старые")`.

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/test/java/com/vladoose/nir/tools src/test/java/com/vladoose/nir/integration/corpus src/test/resources/import-corpus
git commit -m "test(import): замороженный корпус ЗКО и СК-Фармации, отчёт «было» по фильтрам

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Общий словарь + фильтр goszakup (A2)

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/MedicalGoodsVocabulary.java`
- Create: `src/main/java/com/vladoose/nir/integration/LotText.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilter.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java:139-149`
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilterTest.java`, `src/test/java/com/vladoose/nir/integration/MedicalGoodsVocabularyTest.java`
- Modify: `src/test/java/com/vladoose/nir/integration/corpus/ImportCorpusReportTest.java` (снять временный адаптер goszakup), `golden-lots.tsv`

**Interfaces:**
- Consumes: корпус и golden из Task 1.
- Produces:
  - `record LotText(String name, String description)` (пакет `com.vladoose.nir.integration`).
  - `MedicalGoodsVocabulary`: `static boolean hasDeviceStrong(String)`, `hasDeviceWeak(String)`, `hasServiceStrong(String)`, `hasServiceWeak(String)`, `hasMedicine(String)`; `static boolean matchesAny(String text, List<String> terms)` (терм — один или несколько стемов через пробел; совпадение = каждый стем встречается как НАЧАЛО слова, в любом порядке).
  - `MedicalRelevanceFilter.isRelevant(String announcementName, List<LotText> lots)`; `static boolean isMedicalLot(LotText lot)`.

- [ ] **Step 1: Тест словаря (падает — класса нет)**

```java
package com.vladoose.nir.integration;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MedicalGoodsVocabularyTest {
    @Test
    void multiWordTerm_matchesStemsInAnyOrderAndCase() {
        assertThat(MedicalGoodsVocabulary.matchesAny("Кислородный концентратор", List.of("концентратор кислород"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Концентратор кислорода 5 л", List.of("концентратор кислород"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Государственные закупки медицинских изделий", List.of("издели медицинск"))).isTrue();
    }
    @Test
    void stemMatchesOnlyAtWordStart() {
        assertThat(MedicalGoodsVocabulary.matchesAny("кузина", List.of("узи"))).isFalse();
        assertThat(MedicalGoodsVocabulary.matchesAny("Аппарат УЗИ", List.of("узи"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("тест-полоски", List.of("полоск"))).isTrue(); // дефис — граница слова
    }
    @Test
    void medicineMarkers() {
        assertThat(MedicalGoodsVocabulary.hasMedicine("Раствор для инфузий 0,9%")).isTrue();
        assertThat(MedicalGoodsVocabulary.hasMedicine("Таблетки 8 мг")).isTrue();
        assertThat(MedicalGoodsVocabulary.hasMedicine("Монитор пациента")).isFalse();
    }
}
```

Run: `./gradlew test --tests '*MedicalGoodsVocabularyTest*'` → FAIL (компиляция).

- [ ] **Step 2: Словарь**

```java
package com.vladoose.nir.integration;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Общий словарь медизделий для фильтров релевантности goszakup и СК-Фармации. Только термины — решения
 * принимают фильтры площадок. Терм — один или несколько стемов через пробел; совпадение — каждый стем
 * встречается как НАЧАЛО слова (в любом порядке): «концентратор кислород» ловит и «Кислородный
 * концентратор», а прежняя подстрока «кислородн концентратор» не совпадала никогда (ревью A2).
 */
public final class MedicalGoodsVocabulary {

    /** Однозначные медизделия: перебивают маркеры лекарств и слабые маркеры услуг. */
    public static final List<String> DEVICE_STRONG = List.of(
            "узи", "ультразвук", "эхокардиограф", "рентген", "флюорограф", "маммограф", "ангиограф", "томограф",
            "мрт", "ивл", "вентиляц легк", "наркозн", "анестезиолог", "анализатор", "гематологическ анализатор",
            "коагулометр", "центрифуг", "микроскоп", "стерилизатор", "автоклав", "эндоскоп", "гастроскоп",
            "колоноскоп", "бронхоскоп", "лапароскоп", "цистоскоп", "ларингоскоп", "дефибрил", "кардиограф",
            "электрокардиограф", "экг", "спирометр", "инкубатор", "облучател", "рециркулятор", "бактерицидн",
            "физиотерап", "электрофорез", "магнитотерап", "отсасыват", "аспиратор", "оксиметр", "пульсоксиметр",
            "тонометр", "глюкометр", "коагулятор", "ингалятор", "небулайзер", "негатоскоп", "дозатор",
            "концентратор кислород", "весы медицин", "холодильник медицин", "термоконтейнер", "кровать функционал",
            "кровать медицин", "кушетк", "стоматологическ", "дентальн", "хирургическ", "стол операционн",
            "светильник операционн", "перчат", "шприц", "катетер", "канюл", "зонд", "бинт", "пластыр", "электрод",
            "реагент", "тест-систем", "тест-полоск", "экспресс-тест", "пробирк", "контейнер биологическ",
            "контейнер сбор", "издели медицинск", "медицинского назначения", "расходн медицинск", "имплант",
            "протез", "материал шовн", "игл", "скальпел", "насос инфузионн", "насос шприцев", "инфузомат",
            "гемодиализ", "диализатор");

    /** Общие слова: изделие, только если нет лекарственного вето (СК-Фармация). goszakup их не использует. */
    public static final List<String> DEVICE_WEAK = List.of(
            "аппарат", "установк", "монитор", "издели", "инструмент", "светильник", "насос", "помп", "кровать",
            "кресл", "весы", "система", "набор", "комплект", "стол", "тележк", "штатив", "бахил", "маск", "халат",
            "салфетк", "шпател", "пинцет", "зажим", "ножниц");

    /** Лот — услуга/работа/не-медицина независимо от терминов изделий. Ищется только в НАЗВАНИИ лота. */
    public static final List<String> SERVICE_STRONG = List.of(
            "услуг", "работы по", "работ по", "обучен", "утилизац", "отход", "ремонт", "обслуживан", "аренд",
            "страхован", "пошив", "стирк", "поверк", "метролог", "летательн", "беспилотн", "дрон");

    /** Маркер услуги, который проигрывает термину изделия в том же названии («Кушетка для осмотра»). */
    public static final List<String> SERVICE_WEAK = List.of("осмотр", "удален", "замер", "монтаж", "потолок");

    private static final Pattern MEDICINE = Pattern.compile("(?iU)"
            + "таблетк|ампул|капсул|мазь|сироп|инъекц|порошок|суспензи|инфузи|раствор для|флакон|драже|гранул"
            + "|свеч|суппозитор|аэрозол|настойк|вакцин|сыворотк|инсулин|антибиотик|\\bмг\\b|мг/мл|\\bме\\b|\\bмкг\\b");

    private static final String WORD_START = "(?<![\\p{L}\\p{N}])";

    private MedicalGoodsVocabulary() {}

    public static boolean hasDeviceStrong(String text) { return matchesAny(text, DEVICE_STRONG); }
    public static boolean hasDeviceWeak(String text)   { return matchesAny(text, DEVICE_WEAK); }
    public static boolean hasServiceStrong(String text) { return matchesAny(text, SERVICE_STRONG); }
    public static boolean hasServiceWeak(String text)  { return matchesAny(text, SERVICE_WEAK); }
    public static boolean hasMedicine(String text)     { return text != null && MEDICINE.matcher(text).find(); }

    public static boolean matchesAny(String text, List<String> terms) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            boolean all = true;
            for (String stem : term.split(" ")) {
                if (!Pattern.compile(WORD_START + Pattern.quote(stem), Pattern.UNICODE_CHARACTER_CLASS)
                        .matcher(t).find()) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }
}
```

Прекомпилировать паттерны стемов в статический `Map<String, Pattern>` (≈ 150 паттернов), а не компилировать на каждый вызов — корпус гоняет тысячи лотов. Многословный терм «работы по» — стемы «работы», «по»: «по» как начало слова встречается почти везде — поэтому сделать такие термины фразой: если терм содержит стем короче 3 символов, матчить его ЦЕЛИКОМ как подстроку с началом слова (`WORD_START + quote("работы по")`). Записать это правило в javadoc.

Run: `./gradlew test --tests '*MedicalGoodsVocabularyTest*'` → PASS.

- [ ] **Step 3: Тесты фильтра goszakup (падают)**

Перевести существующие тесты `MedicalRelevanceFilterTest` на `LotText` (смысл и ожидания не менять; строки, где раньше передавался склеенный «название описание», разнести на name/description). Добавить:

```java
@Test
void deadTwoWordStemsNowMatch() {   // ревью A2: 7 стемов «стем пробел стем» не совпадали никогда
    for (String name : List.of("Кушетка медицинская смотровая", "Операционный стол", "Светильник операционный",
            "Кислородный концентратор", "Шовный материал", "Расходные материалы для гемодиализа",
            "Монитор прикроватный")) {
        assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText(name, null))).as(name).isTrue();
    }
}
@Test
void serviceWordsInDescriptionDoNotKillDevice() {   // Review Focus 1
    assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Аппарат УЗИ",
            "Поставка, монтаж, пусконаладка и обучение персонала"))).isTrue();
}
@Test
void weakServiceLosesToDeviceInName_strongServiceAlwaysWins() {
    assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Кушетка для осмотра", null))).isTrue();
    assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Ремонт аппарата УЗИ", null))).isFalse();
    assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Услуги по стерилизации", null))).isFalse();
    assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Медицинский осмотр работников", null))).isFalse();
}
@Test
void deviceTermInDescriptionCounts() {
    assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Товар 1", "Анализатор гематологический"))).isTrue();
}
@Test
void hvacVentilationIsNotMedical() {   // «вентиляц» ловил приточно-вытяжную вентиляцию
    assertThat(MedicalRelevanceFilter.isMedicalLot(new LotText("Приточно-вытяжная вентиляция", null))).isFalse();
}
```

Run → FAIL (сигнатуры нет).

- [ ] **Step 4: Фильтр goszakup**

```java
public final class MedicalRelevanceFilter {
    private static final Logger log = LoggerFactory.getLogger(MedicalRelevanceFilter.class);
    private MedicalRelevanceFilter() {}

    /** Тендер релевантен, если ≥1 лот — медтовар. Пустые лоты (сеть/404) → судим по имени объявления. */
    public static boolean isRelevant(String announcementName, List<LotText> lots) {
        if (lots != null && !lots.isEmpty()) return lots.stream().anyMatch(MedicalRelevanceFilter::isMedicalLot);
        return isMedicalLot(new LotText(announcementName, null));
    }

    /**
     * Маркеры услуг — только в НАЗВАНИИ лота: описание поставки изделия обычно перечисляет «монтаж, обучение
     * персонала» и раньше убивало лот. Термины изделий — в названии и описании. Сильный маркер услуги
     * отсекает всегда, слабый — только без термина изделия в названии.
     */
    public static boolean isMedicalLot(LotText lot) {
        String name = lot.name() == null ? "" : lot.name();
        String descr = lot.description() == null ? "" : lot.description();
        if (MedicalGoodsVocabulary.hasServiceStrong(name)) {
            if (MedicalGoodsVocabulary.hasDeviceStrong(name)) log.info("goszakup: лот «{}» — изделие и услуга, считаем услугой", name);
            return false;
        }
        boolean deviceInName = MedicalGoodsVocabulary.hasDeviceStrong(name);
        if (MedicalGoodsVocabulary.hasServiceWeak(name) && !deviceInName) return false;
        if (deviceInName && MedicalGoodsVocabulary.hasServiceWeak(name)) log.info("goszakup: лот «{}» — изделие и слабый маркер услуги, считаем изделием", name);
        return deviceInName || MedicalGoodsVocabulary.hasDeviceStrong(descr);
    }
}
```

В `GoszakupImportService.importOne` заменить сборку `lotTexts` на `List<LotText> lotTexts = lots.stream().map(l -> new LotText(l.getNameRu(), l.getDescriptionRu())).toList();`.

Run: `./gradlew test --tests '*MedicalRelevanceFilterTest*' --tests '*MedicalGoodsVocabularyTest*' --tests '*GoszakupImportServiceTest*'` → PASS. Если падает прежний тест реальных лотов ЗКО (`keepsRealZkoDevicesAndDropsMedicinesFoodHousehold`) — дополнить словарь термином, а не менять ожидание; ожидание менять только если прежнее было ошибкой (обосновать в отчёте задачи).

- [ ] **Step 5: Golden и отчёт по goszakup**

В `ImportCorpusReportTest` снять временный адаптер goszakup → `MedicalRelevanceFilter.isMedicalLot`. Прогнать `report` (временно включив `golden` локально): по корпусу ЗКО выписать ВСЕ лоты, где старый и новый вердикт расходятся (`LegacyRelevanceFilters.goszakupLot` vs `isMedicalLot`), вручную разметить и добавить в `golden-lots.tsv` (правила разметки — Task 1, Step 4). Добиться 0 расхождений golden для goszakup правкой словаря. Записать строку `REPORT GOSZAKUP …` в progress.md («после Task 2»).

Run: `./gradlew cleanTest test --tests '*ImportCorpusReportTest*' --tests '*MedicalRelevanceFilterTest*'` → PASS (`golden` всё ещё `@Disabled` из-за СК — проверять его локально, сняв аннотацию на время прогона).

- [ ] **Step 6: Мутация**

Вернуть в `isMedicalLot` проверку `hasServiceStrong(name + " " + descr)` → `serviceWordsInDescriptionDoNotKillDevice` краснеет. Откатить копией, `./gradlew compileJava`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/integration src/test/java/com/vladoose/nir/integration src/test/resources/import-corpus/golden-lots.tsv
git commit -m "fix(import): фильтр goszakup — двухсловные термины, услуги только по названию лота (A2)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Фильтр СК-Фармации (A1)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/integration/skpharmacy/SkPharmacyRelevanceFilter.java`
- Test: `src/test/java/com/vladoose/nir/integration/skpharmacy/SkPharmacyRelevanceFilterTest.java`
- Modify: `ImportCorpusReportTest` (снять `@Disabled` с `golden`), `golden-lots.tsv`

**Interfaces:**
- Consumes: `MedicalGoodsVocabulary` (Task 2).
- Produces: прежние сигнатуры `nameCandidate(String)`, `isDeviceLot(String)`, `isRelevant(String, List<String>)` — потребитель `SkPharmacyImportService` не меняется.

- [ ] **Step 1: Тесты (падают)**

```java
@Test
void strongDevicesRecognizedDespiteMedicineWords() {   // ревью A1
    for (String n : List.of("Анализатор биохимический автоматический", "Гастроскоп", "Бронхоскоп",
            "Микроскоп бинокулярный", "Центрифуга лабораторная", "Маммограф цифровой", "Насос инфузионный",
            "Капсула эндоскопическая", "Тест-полоски для глюкометра", "Экспресс-тест для определения тропонина",
            "Реагент для определения глюкозы в сыворотке крови", "Шприц инъекционный 5 мл")) {
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot(n)).as(n).isTrue();
    }
}
@Test
void medicinesStayOut() {
    for (String n : List.of("Раствор для инфузий натрия хлорида 0,9%", "Таблетки 8 мг", "Вакцина против гриппа",
            "Аэрозол для ингаляций", "Капсулы 20 мг")) {
        assertThat(SkPharmacyRelevanceFilter.isDeviceLot(n)).as(n).isFalse();
    }
}
@Test
void weakDeviceWordLosesToMedicine() {
    assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Набор для инфузий")).isFalse();      // набор — слабое, инфузи — вето
    assertThat(SkPharmacyRelevanceFilter.isDeviceLot("Набор хирургических инструментов")).isTrue(); // хирургическ — сильное
}
@Test
void announcementNamedMedicalEquipmentIsRelevantWhateverLots() {
    assertThat(SkPharmacyRelevanceFilter.isRelevant("Закуп медицинской техники", List.of("Ларингоскоп", "Прочее"))).isTrue();
    assertThat(SkPharmacyRelevanceFilter.isRelevant("Закуп медицинских изделий на 2026 год", List.of("Х"))).isTrue();
}
```

Run → FAIL.

- [ ] **Step 2: Реализация**

```java
/** Имя объявления, которого достаточно: «…медицинской техники», «…медицинских изделий». */
private static final List<String> MEDICAL_PROCUREMENT = List.of("медицинск техник", "медицинск издели");

public static boolean isDeviceLot(String lotName) {
    String n = lotName == null ? "" : lotName;
    if (MedicalGoodsVocabulary.hasDeviceStrong(n)) return true;          // сильное изделие перебивает лекарственное вето
    if (MedicalGoodsVocabulary.hasMedicine(n)) return false;
    return MedicalGoodsVocabulary.hasDeviceWeak(n);
}

public static boolean isRelevant(String announcementName, List<String> lotNames) {
    if (MedicalGoodsVocabulary.matchesAny(announcementName, MEDICAL_PROCUREMENT)) return true;
    if (lotNames == null || lotNames.isEmpty()) return nameCandidate(announcementName);
    return lotNames.stream().anyMatch(SkPharmacyRelevanceFilter::isDeviceLot);
}
```

Удалить локальные паттерны `DEVICE`/`MEDICINE` (словарь — единственный источник); `MED_NAME`/`EQUIP_HINT`/`nameCandidate` — без изменений, но с флагом `(?iU)`.

Run: `./gradlew test --tests '*SkPharmacyRelevanceFilterTest*' --tests '*SkPharmacyImportServiceTest*'` → PASS.

- [ ] **Step 3: Golden по СК, включение golden в гейт**

Как Task 2 Step 5, но для `sk-first5.jsonl.gz`: расхождения старого и нового вердикта по лотам — разметить, добавить в golden, довести до 0 расхождений правкой словаря. Снять `@Disabled` с `golden()`. Записать `REPORT SK_PHARMACY …` в progress.md. Ожидаемое направление: профильных тендеров/лотов СК больше, чем «было», лекарства — нет.

Run: `./gradlew cleanTest test --tests '*ImportCorpusReportTest*' --tests '*RelevanceFilter*'` → PASS.

- [ ] **Step 4: Мутация** — в `isDeviceLot` поставить вето лекарства ПЕРЕД сильным изделием → `strongDevicesRecognizedDespiteMedicineWords` красный. Откат копией, `compileJava`.

- [ ] **Step 5: Commit**

```bash
git commit -am "fix(import): фильтр СК-Фармации на общем словаре, лекарственное вето не бьёт сильные изделия (A1)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
(новые файлы — `git add` явно, если появились.)

---

### Task 4: HTTP-слой импорта: дедлайн, предел тела, повторы

**Files:**
- Move: `integration/whatsapp/LimitedBytes.java`, `integration/whatsapp/FileTooLargeException.java` → `integration/http/` (обновить импорты во всех потребителях: `grep -rln "whatsapp.LimitedBytes\|whatsapp.FileTooLargeException\|LimitedBytes\|FileTooLargeException" src` — классы в том же пакете whatsapp сейчас без импорта, им нужен явный `import com.vladoose.nir.integration.http.…`)
- Create: `integration/http/UpstreamHttp.java`, `UpstreamIoException.java`, `RetryableFailure.java`, `UpstreamRetry.java`
- Test: `src/test/java/com/vladoose/nir/integration/http/UpstreamHttpTest.java`, `UpstreamRetryTest.java`

**Interfaces:**
- Produces:
  - `UpstreamHttp.exchange(HttpClient http, HttpRequest req, long maxBytes, Duration deadline) throws UpstreamIoException → HttpResponse<byte[]>` — дедлайн на ВЕСЬ обмен (тело включительно), обрыв на пределе тела.
  - `UpstreamHttp.newClient(HttpClient.Redirect redirect) → HttpClient` — HTTP/1.1, connectTimeout 15 с.
  - `UpstreamIoException extends Exception`: `enum Kind { TIMEOUT, IO, TOO_LARGE, INTERRUPTED }`, `Kind kind()`, `boolean retryable()` (TIMEOUT, IO → true), `getMessage()` — человеческий текст: TIMEOUT «нет ответа за N с», TOO_LARGE «ответ больше N МБ», IO — `ErrorText.of(cause)`, INTERRUPTED «прервано».
  - `interface RetryableFailure { boolean retryable(); }`
  - `UpstreamRetry`: `interface Sleeper { void sleep(long ms) throws InterruptedException; }`; `static final Sleeper REAL = Thread::sleep;`; `static <T> T call(Sleeper sleeper, Supplier<T> call)` — 3 попытки, паузы 1000 и 3000 мс; повторяет только `RuntimeException`, реализующие `RetryableFailure` с `retryable()==true`; прерывание сна — немедленно бросить последнее исключение с восстановленным флагом; `static boolean retryableStatus(int status)` → `status >= 500 || status == 429`.

- [ ] **Step 1: Тесты (падают)**

```java
class UpstreamHttpTest {
    static HttpServer server;
    @BeforeAll static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/stall", ex -> {          // заголовки пришли, тело встало
            ex.sendResponseHeaders(200, 1000);
            ex.getResponseBody().write(new byte[10]); ex.getResponseBody().flush();
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}
            ex.close();
        });
        server.createContext("/big", ex -> {
            byte[] b = new byte[2048]; ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        server.createContext("/ok", ex -> {
            byte[] b = "hi".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }
    @AfterAll static void stop() { server.stop(0); }
    URI uri(String p) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + p); }
    HttpClient http = UpstreamHttp.newClient(HttpClient.Redirect.NEVER);

    @Test void stalledBody_endsByDeadline_retryable() {
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> UpstreamHttp.exchange(http, HttpRequest.newBuilder(uri("/stall")).build(), 1_000_000, Duration.ofSeconds(1)))
            .isInstanceOfSatisfying(UpstreamIoException.class, e -> {
                assertThat(e.kind()).isEqualTo(UpstreamIoException.Kind.TIMEOUT);
                assertThat(e.retryable()).isTrue();
                assertThat(e.getMessage()).isEqualTo("нет ответа за 1 с");
            });
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    }
    @Test void bodyOverLimit_tooLarge_notRetryable() {
        assertThatThrownBy(() -> UpstreamHttp.exchange(http, HttpRequest.newBuilder(uri("/big")).build(), 1024, Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(UpstreamIoException.class, e -> {
                assertThat(e.kind()).isEqualTo(UpstreamIoException.Kind.TOO_LARGE);
                assertThat(e.retryable()).isFalse();
            });
    }
    @Test void ok_returnsBody() throws Exception {
        assertThat(new String(UpstreamHttp.exchange(http, HttpRequest.newBuilder(uri("/ok")).build(), 1024, Duration.ofSeconds(5)).body())).isEqualTo("hi");
    }
    @Test void refusedConnection_ioWithClassName() throws Exception {
        int closed; try (var s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { closed = s.getLocalPort(); }
        assertThatThrownBy(() -> UpstreamHttp.exchange(http, HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closed + "/")).build(), 1024, Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(UpstreamIoException.class, e -> {
                assertThat(e.kind()).isEqualTo(UpstreamIoException.Kind.IO);
                assertThat(e.getMessage()).isEqualTo("ConnectException");
            });
    }
}
```

```java
class UpstreamRetryTest {
    static class Fail extends RuntimeException implements RetryableFailure {
        final boolean r; Fail(boolean r) { super("x"); this.r = r; } public boolean retryable() { return r; }
    }
    final List<Long> slept = new ArrayList<>();
    final UpstreamRetry.Sleeper rec = slept::add;

    @Test void retryableFailure_retriedWithPauses_thenSucceeds() {
        AtomicInteger n = new AtomicInteger();
        String r = UpstreamRetry.call(rec, () -> { if (n.incrementAndGet() < 3) throw new Fail(true); return "ok"; });
        assertThat(r).isEqualTo("ok"); assertThat(n.get()).isEqualTo(3); assertThat(slept).containsExactly(1000L, 3000L);
    }
    @Test void nonRetryable_thrownImmediately() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> UpstreamRetry.call(rec, () -> { n.incrementAndGet(); throw new Fail(false); })).isInstanceOf(Fail.class);
        assertThat(n.get()).isEqualTo(1); assertThat(slept).isEmpty();
    }
    @Test void plainRuntimeException_notRetried() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> UpstreamRetry.call(rec, () -> { n.incrementAndGet(); throw new IllegalStateException("x"); }));
        assertThat(n.get()).isEqualTo(1);
    }
    @Test void exhausted_lastFailureThrown() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> UpstreamRetry.call(rec, () -> { n.incrementAndGet(); throw new Fail(true); })).isInstanceOf(Fail.class);
        assertThat(n.get()).isEqualTo(3);
    }
    @Test void status_classification() {
        assertThat(UpstreamRetry.retryableStatus(503)).isTrue(); assertThat(UpstreamRetry.retryableStatus(429)).isTrue();
        assertThat(UpstreamRetry.retryableStatus(400)).isFalse(); assertThat(UpstreamRetry.retryableStatus(403)).isFalse();
    }
}
```

Run → FAIL (компиляция).

- [ ] **Step 2: Перенос `LimitedBytes`/`FileTooLargeException` + реализация**

```java
package com.vladoose.nir.integration.http;

/**
 * Обмен с внешней площадкой импорта с дедлайном на ВЕСЬ ответ: {@code HttpRequest.timeout} в JDK 17 снимается
 * после заголовков, и вставшее тело держало бы поток импорта вечно (CLAUDE.md §14). Тело — {@link LimitedBytes}
 * с обрывом на пределе внутри обмена. В отличие от whatsapp.GatewayHttp текст сбоя несёт ПРИЧИНУ
 * ({@code ErrorText.of}): у импорта токен только в заголовке, а «PKIX path building failed» — ровно то, по чему
 * нашли поломку СК-Фармации.
 */
public final class UpstreamHttp {
    private UpstreamHttp() {}

    public static HttpClient newClient(HttpClient.Redirect redirect) {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15)).followRedirects(redirect).build();
    }

    public static HttpResponse<byte[]> exchange(HttpClient http, HttpRequest req, long maxBytes, Duration deadline)
            throws UpstreamIoException {
        CompletableFuture<HttpResponse<byte[]>> f;
        try {
            f = http.sendAsync(req, info -> new LimitedBytes(maxBytes));
        } catch (RuntimeException e) {
            throw new UpstreamIoException(UpstreamIoException.Kind.IO, ErrorText.of(e), e);
        }
        try {
            return f.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new UpstreamIoException(UpstreamIoException.Kind.TIMEOUT, "нет ответа за " + deadline.toSeconds() + " с", e);
        } catch (ExecutionException e) {
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                if (c instanceof FileTooLargeException) {
                    throw new UpstreamIoException(UpstreamIoException.Kind.TOO_LARGE,
                            "ответ больше " + (maxBytes / (1024 * 1024)) + " МБ", c);
                }
            }
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new UpstreamIoException(UpstreamIoException.Kind.IO, ErrorText.of(c), c);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new UpstreamIoException(UpstreamIoException.Kind.INTERRUPTED, "прервано", e);
        }
    }
}
```

`FileTooLargeException` сообщение «файл больше N МБ» оставить (его читает WhatsApp). Для «ответ больше 1024 байт» в тесте MB = 0 — нормально, тест текст не проверяет.

`UpstreamRetry.call` — цикл 3 попытки, паузы `{1000, 3000}`; `catch (RuntimeException e) { if (!(e instanceof RetryableFailure r) || !r.retryable() || i == 2) throw e; try { sleeper.sleep(PAUSES[i]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; } }`.

Run: `./gradlew test --tests '*UpstreamHttpTest*' --tests '*UpstreamRetryTest*' --tests '*Waha*' --tests '*GreenApi*' --tests '*Whatsapp*'` → PASS.

- [ ] **Step 3: Мутация** — в `exchange` заменить `f.get(deadline…)` на `f.get()` → `stalledBody_endsByDeadline_retryable` красный (виснет до 10 с сервера и даёт не тот исход). Откат, `compileJava`.

- [ ] **Step 4: Commit**

```bash
git add -A src/main/java/com/vladoose/nir/integration src/test/java/com/vladoose/nir/integration/http
git commit -m "feat(import): HTTP-слой импорта — дедлайн на весь ответ, предел тела, повторы только повторяемого

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Клиент goszakup — новый HTTP-слой и все страницы лотов (C1, A4)

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupCallException.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java`
- Delete: `GoszakupRetry.java`, `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupRetryTest.java`
- Modify: javadoc `src/main/java/com/vladoose/nir/service/TechSpecBackfillScheduler.java:82` (ссылка на `GoszakupRetry` → `UpstreamRetry`)
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClientTest.java`

**Interfaces:**
- Consumes: `UpstreamHttp`, `UpstreamIoException`, `RetryableFailure`, `UpstreamRetry` (Task 4).
- Produces: `GoszakupCallException(String message, boolean retryable, Throwable cause)` extends `IllegalStateException` implements `RetryableFailure`. `fetchLots(anno)` возвращает ВСЕ лоты (или бросает `GoszakupCallException(retryable=false)` «лоты объявления N: получено M из T»). Тексты прежние: «goszakup API недоступно: <причина>», «goszakup API <код> на <путь без хоста>»; 404 → `GoszakupNotFoundException` как раньше.

- [ ] **Step 1: Тесты (падают)**

Стаб `GoszakupHttpClientTest` научить отвечать по пути+query (карта `path?query → (status, body)` вместо одного `nextBody`; старые тесты продолжают работать через ответ по умолчанию). Добавить:

```java
@Test
void fetchLots_followsNextPage_untilTotal() {
    respond("/v2/lots/number-anno/17737448-1", "limit=500", 200, """
        {"total":3,"limit":2,"next_page":"/v2/lots/number-anno/17737448-1?page=next&search_after=43500954",
         "items":[{"lot_number":"1","name_ru":"A","count":1,"amount":10},{"lot_number":"2","name_ru":"B","count":1,"amount":10}]}""");
    respond("/v2/lots/number-anno/17737448-1", "page=next&search_after=43500954", 200, """
        {"total":3,"limit":2,"next_page":"","items":[{"lot_number":"3","name_ru":"C","count":1,"amount":10}]}""");
    assertThat(client.fetchLots("17737448-1")).extracting(LotDto::getNameRu).containsExactly("A", "B", "C");
}
@Test
void fetchLots_lessThanTotal_failsNotRetryable() {
    respond("/v2/lots/number-anno/1-1", "limit=500", 200, """
        {"total":5,"limit":500,"next_page":"","items":[{"lot_number":"1","name_ru":"A","count":1,"amount":10}]}""");
    assertThatThrownBy(() -> client.fetchLots("1-1"))
        .isInstanceOfSatisfying(GoszakupCallException.class, e -> {
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).isEqualTo("goszakup: лоты объявления 1-1 — получено 1 из 5");
        });
}
@Test
void serverError_retryable_clientError_not() {
    respond("/v2/subject/biin/111", "", 503, "{}");
    assertThatThrownBy(() -> client.fetchSubject("111"))
        .isInstanceOfSatisfying(GoszakupCallException.class, e -> assertThat(e.retryable()).isTrue());
    respond("/v2/subject/biin/222", "", 403, "{}");
    assertThatThrownBy(() -> client.fetchSubject("222"))
        .isInstanceOfSatisfying(GoszakupCallException.class, e -> assertThat(e.retryable()).isFalse());
}
@Test
void redirectNotFollowed_tokenStaysHome() {   // C5 не ухудшаем: 302 → ошибка, а не запрос на чужой хост
    respondRedirect("/v2/subject/biin/333", "http://127.0.0.1:1/steal");
    assertThatThrownBy(() -> client.fetchSubject("333")).isInstanceOf(GoszakupCallException.class);
}
```
Существующий `unreachableApi_namesTheFailureInsteadOfNull` (`hasMessage("goszakup API недоступно: ConnectException")`) должен остаться зелёным без правки.

Run → FAIL.

- [ ] **Step 2: Реализация**

- `http = UpstreamHttp.newClient(HttpClient.Redirect.NEVER)`.
- `raw(...)`: `HttpResponse<byte[]> resp = UpstreamHttp.exchange(http, req, MAX_JSON_BYTES /* 10 МБ */, Duration.ofSeconds(60))`; `catch (UpstreamIoException e) → throw new GoszakupCallException("goszakup API недоступно: " + e.getMessage(), e.retryable(), e)`; статус 404 → `GoszakupNotFoundException` (как было); иной не-2xx → `new GoszakupCallException("goszakup API " + code + " на " + pathOf(url), UpstreamRetry.retryableStatus(code), null)` где `pathOf` — путь без хоста (`URI.create(url).getPath()`); 3xx попадает сюда же (не повторяемо).
- `downloadFile`: `UpstreamHttp.exchange(..., MAX_FILE_BYTES /* 30 МБ */, Duration.ofSeconds(120))`, ретраи — `UpstreamRetry.call(UpstreamRetry.REAL, …)`; 404 → null как было; текст «goszakup download недоступен: …» / «goszakup download <код>».
- `fetchLotTechSpec` — `UpstreamRetry.call(UpstreamRetry.REAL, …)` вместо `GoszakupRetry`; ошибки GraphQL/JSON → `GoszakupCallException(…, false, e)`.
- `postTrdBuyV3`, `parse`: ошибки разбора — `GoszakupCallException(…, false, e)`.
- `fetchLots`:

```java
@Override
public List<LotDto> fetchLots(String numberAnno) {
    // живой API (2026-10-07): страница {total, limit, next_page, items}; limit=500 принимается, next_page
    // ведёт без limit (страницы по 50) — идём по нему до конца и сверяем с total: неполный ответ = сбой
    List<LotDto> all = new ArrayList<>();
    String url = baseUrl + "/lots/number-anno/" + enc(numberAnno) + "?limit=500";
    Integer total = null;
    for (int page = 0; page < MAX_LOT_PAGES /* 40 */ && url != null; page++) {
        LotsPage p = get(url, LotsPage.class);
        if (p == null) break;
        if (total == null) total = p.total;
        if (p.items != null) all.addAll(p.items);
        url = (p.nextPage == null || p.nextPage.isBlank()) ? null : origin() + p.nextPage;
    }
    if (total != null && all.size() < total) {
        throw new GoszakupCallException("goszakup: лоты объявления " + numberAnno + " — получено " + all.size()
                + " из " + total, false, null);
    }
    return all;
}
/** Страница лотов v2. */
static class LotsPage {
    public Integer total;
    @JsonProperty("next_page") public String nextPage;
    public List<LotDto> items;
}
```
`TypeRefPage` удалить, если больше не используется. Удалить `GoszakupRetry` и его тест.

Run: `./gradlew test --tests '*Goszakup*' --tests '*TechSpec*'` → PASS.

- [ ] **Step 3: Мутация** — убрать сверку с `total` → `fetchLots_lessThanTotal_failsNotRetryable` красный. Откат, `compileJava`.

- [ ] **Step 4: Commit**

```bash
git add -A src/main/java/com/vladoose/nir/integration/goszakup src/main/java/com/vladoose/nir/service/TechSpecBackfillScheduler.java src/test/java/com/vladoose/nir/integration/goszakup
git commit -m "fix(import): goszakup — все страницы лотов со сверкой total, дедлайн на весь ответ, без редиректов (A4, C1)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Клиенты СК-Фармации — новый HTTP-слой (C1)

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/skpharmacy/SkCallException.java`
- Modify: `SkPharmacyHttpClient.java`, `SkTechSpecHttpClient.java`
- Test: `src/test/java/com/vladoose/nir/integration/skpharmacy/SkPharmacyHttpClientTest.java`

**Interfaces:**
- Consumes: Task 4.
- Produces: `SkCallException(String message, boolean retryable)` extends `UpstreamException` implements `RetryableFailure` (наследование от `UpstreamException` сохраняет 502 у кнопки «ТЗ»). Тексты прежние: «Сеть fms.ecc.kz: <причина>», «fms.ecc.kz вернул <код> для <путь>» (без хоста), «Прервано при запросе к fms.ecc.kz».

- [ ] **Step 1: Тесты (падают)**

В `SkPharmacyHttpClientTest` поднять JDK `HttpServer` на 127.0.0.1 и создавать клиентов с `baseUrl` на него:

```java
@Test void stalledBody_failsWithReason_retryable() {   // заголовки пришли, тело встало
    // сервер /ru/searchanno: sendResponseHeaders(200, 1000), пишет 10 байт, спит 10 с
    SkPharmacyHttpClient c = new SkPharmacyHttpClient(base(), Duration.ofSeconds(1));
    assertThatThrownBy(() -> c.searchPage(1)).isInstanceOfSatisfying(SkCallException.class, e -> {
        assertThat(e.retryable()).isTrue();
        assertThat(e.getMessage()).isEqualTo("Сеть fms.ecc.kz: нет ответа за 1 с");
    });
}
@Test void http500_retryable_404_not() { … }
@Test void redirectFollowed() {   // портал отдаёт 302 на ту же страницу с другим путём — идём (NORMAL)
    // /ru/searchanno → 302 Location: /ru/searchanno2 → 200 "<html>ok</html>"
    assertThat(new SkPharmacyHttpClient(base(), Duration.ofSeconds(5)).searchPage(1)).contains("ok");
}
@Test void pdfOverLimit_notRetryable() { … SkTechSpecHttpClient.downloadPdf … 30 МБ+1 → SkCallException retryable=false, текст «Сеть fms.ecc.kz: ответ больше 30 МБ» }
```
Для теста дедлайна добавить пакетный конструктор `SkPharmacyHttpClient(String baseUrl, Duration deadline)`; Spring-конструктор делегирует с `Duration.ofSeconds(60)`. То же у `SkTechSpecHttpClient(String baseUrl, Duration htmlDeadline, Duration pdfDeadline)` (боевые 60 с / 120 с). Для предела PDF допустим пакетный конструктор с `maxPdfBytes`, чтобы не гнать 30 МБ в тесте (тогда текст проверять по фактическому пределу).

Run → FAIL.

- [ ] **Step 2: Реализация**

Оба клиента: `UpstreamHttp.newClient(HttpClient.Redirect.NORMAL)`; HTML — `exchange(..., 10 МБ, deadline)` и `new String(body, UTF_8)`; PDF — 30 МБ, 120 с. Сбой: `catch (UpstreamIoException e)` → INTERRUPTED: `new SkCallException("Прервано при запросе к fms.ecc.kz", false)`; иначе `new SkCallException("Сеть fms.ecc.kz: " + e.getMessage(), e.retryable())`. Статус ≠ 200 → `new SkCallException("fms.ecc.kz вернул " + code + " для " + URI.create(url).getPath(), UpstreamRetry.retryableStatus(code))` (у `SkTechSpecHttpClient` сохранить его прежние особые ветки статусов, например 404 у PDF, если они есть — прочитать файл целиком перед правкой).

Run: `./gradlew test --tests '*SkPharmacy*' --tests '*SkTechSpec*' --tests '*TechSpecService*'` → PASS.

- [ ] **Step 3: Commit**

```bash
git add -A src/main/java/com/vladoose/nir/integration/skpharmacy src/test/java/com/vladoose/nir/integration/skpharmacy
git commit -m "fix(import): СК-Фармация — дедлайн на весь ответ, предел тела, редиректы, признак повтора (C1)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Повторы в прогонах + subject после фильтра (A5, D2-часть)

**Files:**
- Modify: `GoszakupImportService.java`, `GoszakupTenderWriter.java`, `SkPharmacyImportService.java`
- Modify: `src/test/java/com/vladoose/nir/integration/goszakup/FakeGoszakupClient.java` (счётчики вызовов + сценарий «первый вызов падает»)
- Test: `GoszakupImportServiceTest.java`, `SkPharmacyImportServiceTest.java`

**Interfaces:**
- Consumes: `UpstreamRetry`, `GoszakupCallException`, `SkCallException`, `MedicalRelevanceFilter.isRelevant(String, List<LotText>)`.
- Produces: `GoszakupImportService` — новый конструктор-перегрузка с `UpstreamRetry.Sleeper` (Spring-конструктор передаёт `UpstreamRetry.REAL`; тесты — `ms -> {}`); `SkPharmacyImportService` — то же (сеттер `void setSleeper(UpstreamRetry.Sleeper)` пакетного уровня допустим, чтобы не плодить контекст с `@TestPropertySource`). `GoszakupTenderWriter.upsertOne(TrdBuyDto, SubjectDto /*nullable*/, List<LotDto>, String)` — при `subj == null` у СУЩЕСТВУЮЩЕГО тендера `customerName`/`regionKato`/`deliveryAddress`/`region` (кроме `regionOverride`) не трогаются.

- [ ] **Step 1: Тесты (падают)**

`FakeGoszakupClient`: добавить `Map<String,Integer> orgFailuresLeft`, `Map<String,Integer> lotsFailuresLeft`, `Map<String,Integer> subjectFailuresLeft` (сколько раз подряд бросить `new GoszakupCallException("goszakup API недоступно: header parser received no bytes", true, null)`), `Set<String> forbiddenOrgBins` (бросать `GoszakupCallException("goszakup API 403 на /v3/graphql", false, null)`), счётчики `orgCalls`, `lotsCalls`, `subjectCalls` (Map по ключу). Существующие `failingOrgBins`/`failingSubjectBins` оставить.

```java
@Test
void transientFeedFailure_retried_orgImported() {          // ревью A5: 4 из 29 больниц выпадали
    hospital("Больница Р", "BINR");
    fake.orgPage("BINR", FakeGoszakupClient.buy("200-1", "Закуп", 230, "BINR", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
    fake.lotsByAnno.put("200-1", List.of(lot("Аппарат УЗИ", null)));
    fake.orgFailuresLeft.put("BINR", 1);
    fake.lotsFailuresLeft.put("200-1", 1);
    ImportSummary s = service.importMedicalTenders(REGION);
    assertThat(s.getErrors()).isZero();
    assertThat(s.getCreated()).isEqualTo(1);
}
@Test
void clientErrorNotRetried() {                               // Review Focus 3
    hospital("Больница Ф", "BINF");
    fake.forbiddenOrgBins.add("BINF");
    ImportSummary s = service.importMedicalTenders(REGION);
    assertThat(s.getErrors()).isEqualTo(1);
    assertThat(fake.orgCalls.get("BINF")).isEqualTo(1);
}
@Test
void subjectFetchedOnlyForRelevant_andOncePerBin() {
    hospital("Больница С", "BINS");
    fake.orgPage("BINS",
        FakeGoszakupClient.buy("301-1", "Закуп", 230, "BINS", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
        FakeGoszakupClient.buy("302-1", "Закуп", 230, "BINS", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
        FakeGoszakupClient.buy("303-1", "Закуп", 230, "BINS", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
    fake.lotsByAnno.put("301-1", List.of(lot("Аппарат УЗИ", null)));
    fake.lotsByAnno.put("302-1", List.of(lot("Монитор пациента", null)));
    fake.lotsByAnno.put("303-1", List.of(lot("Бумага офисная", null)));
    service.importMedicalTenders(REGION);
    assertThat(fake.subjectCalls.get("BINS")).isEqualTo(1);
}
@Test
void subjectDownAfterRetries_existingTenderKeepsCustomer() {   // Review Focus 4
    hospital("Больница К", "BINK");
    fake.orgPage("BINK", FakeGoszakupClient.buy("400-1", "Закуп", 230, "BINK", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
    fake.lotsByAnno.put("400-1", List.of(lot("Аппарат УЗИ", null)));
    SubjectDto subj = new SubjectDto(); subj.setNameRu("ГКП Больница К"); /* + адрес/КАТО как в соседних тестах */
    fake.subjectsByBin.put("BINK", subj);
    service.importMedicalTenders(REGION);                       // первый прогон — заказчик записан
    fake.subjectFailuresLeft.put("BINK", 99);                   // дальше subject лежит
    ImportSummary s = service.importMedicalTenders(REGION);
    assertThat(s.getErrors()).isZero();
    assertThat(s.getUpdated()).isEqualTo(1);
    Tender t = tenderRepository.findBySourceExtId("400-1").orElseThrow();
    assertThat(t.getCustomerName()).isEqualTo("ГКП Больница К");
}
```
`FakeGoszakupClient.orgPage(bin, TrdBuyDto...)` — если сейчас принимает один элемент, сделать varargs.

СК (`SkPharmacyImportServiceTest`): `when(client.searchPage(1)).thenThrow(new SkCallException("Сеть fms.ecc.kz: нет ответа за 60 с", true)).thenReturn(fixture("search.html"))` (страница 2 → `""`) и аналогично первый `lotsPage` падает → `errors == 0`, тендер создан; `searchPage` 403 (`retryable=false`) → вызван ровно 1 раз, `errors == 1`. Сон — `importService.setSleeper(ms -> {})`.

Run → FAIL.

- [ ] **Step 2: Реализация goszakup**

```java
private void fetchOrgFeed(String orgBin, String region, LocalDate cutoff, ImportSummary sum, Map<String, Optional<SubjectDto>> subjects) {
    Long after = null; int pagesRead = 0;
    do {
        Long a = after;
        var page = UpstreamRetry.call(sleeper, () -> client.fetchTrdBuyPageByOrgBin(orgBin, a));
        …
```

`importOne`: сначала `lots = UpstreamRetry.call(sleeper, () -> client.fetchLots(anno))`, фильтр по `LotText`, затем `SubjectDto subj = subjectOf(d.effectiveBin(), subjects)`:

```java
/** subject — после фильтра (≈ 95 % объявлений не профильные) и один раз на БИН за прогон; сбой не роняет тендер. */
private SubjectDto subjectOf(String bin, Map<String, Optional<SubjectDto>> cache) {
    if (bin == null || bin.isBlank()) return null;
    return cache.computeIfAbsent(bin, b -> {
        try {
            return Optional.ofNullable(UpstreamRetry.call(sleeper, () -> client.fetchSubject(b)));
        } catch (RuntimeException e) {
            log.warn("goszakup: subject {} недоступен, тендер пишем без него: {}", b, ErrorText.of(e));
            return Optional.empty();
        }
    }).orElse(null);
}
```
Кеш — `new HashMap<>()` на прогон в `fillImport`, передаётся вниз. ⚠️ Кешировать и неудачу (`Optional.empty()`) — иначе каждое объявление той же больницы повторит три попытки.

`GoszakupTenderWriter.applyRegion`: `if (subj == null) return;` — но для НОВОГО тендера с `subj == null` регион всё равно должен прийти из `regionOverride` (уже делается после `applyRegion`), а `customerName` остаётся пустым — допустимо. Javadoc: «subject null — нет данных (сбой или неизвестный БИН): поля заказчика не трогаем».

- [ ] **Step 3: Реализация СК**

В `SkPharmacyImportService`: `client.searchPage(page)`, `client.lotsPage(...)`, `client.generalPage(...)` — через `UpstreamRetry.call(sleeper, …)`. `sleeper` по умолчанию `UpstreamRetry.REAL`, пакетный `setSleeper`.

Run: `./gradlew test --tests '*GoszakupImportServiceTest*' --tests '*SkPharmacyImportServiceTest*' --tests '*Import*'` → PASS.

- [ ] **Step 4: Мутация** — в `applyRegion` убрать `if (subj == null) return;` → `subjectDownAfterRetries_existingTenderKeepsCustomer` красный. Откат, `compileJava`.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/vladoose/nir/integration src/test/java/com/vladoose/nir/integration
git commit -m "fix(import): повтор сетевых сбоев ленты, лотов и subject; subject после фильтра и раз на БИН (A5)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Полнота лотов СК-Фармации (A3)

**Files:**
- Modify: `SkPharmacyImportService.java`, `SkPharmacyTenderWriter.java`, `src/main/resources/application.yaml` (`skpharmacy.import.max-lot-pages: ${SKPHARMACY_MAX_LOT_PAGES:200}`), `SkPharmacyImportService` `@Value` дефолт 20 → 200
- Test: `SkPharmacyImportServiceTest.java`, тест райтера (`src/test/java/com/vladoose/nir/integration/ImportPreservesLotWorkTest.java` — добавить кейс, или новый `SkPharmacyTenderWriterTest` под `@SpringBootTest @Transactional` без особых свойств)

**Interfaces:**
- Consumes: `SkAnnounce.lotsCount()` (Integer, может быть null).
- Produces: `SkPharmacyTenderWriter.upsert(SkAnnounce a, List<SkLot> lots, SkGeneral general, boolean complete)`; старый 3-аргументный метод удалить (все вызовы — в сервисе и тестах — обновить).

- [ ] **Step 0: Живая сверка `lotsCount` с вкладкой лотов (до кода)**

`lotsCount` из ленты ещё ни разу не сверялся с вкладкой. Снять живьём для 3 объявлений из корпуса Task 1 (разные вёрстки, одно — многостраничное): число лотов из ленты против числа уникальных кодов со всех страниц вкладки (те же `parseSearch`/`parseLots`, можно `jshell --class-path build/classes/java/main:<jsoup.jar>`). Результат записать в progress.md. Если совпадает — делаем сверку, как ниже. Если у части объявлений лента считает иначе (например, вместе со снятыми лотами) — сверку применять только как «получено МЕНЬШЕ» с ошибкой, никогда не «больше»; если расхождение в обе стороны — остановиться и спросить контроллера.

- [ ] **Step 1: Тесты (падают)**

Райтер:
```java
@Test
void incompleteList_keepsUnmatchedExistingLots() {   // Review Focus 2
    // существующий SK-тендер с лотами A1, A2, A3 (коды), у A3 requiredSpec «разобранное ТЗ»
    writer.upsert(ann("900-1", 3), List.of(lot("900-1-Т1", "A"), lot("900-1-Т2", "B"), lot("900-1-Т3", "C")), null, true);
    writer.upsert(ann("900-1", 3), List.of(lot("900-1-Т1", "A"), lot("900-1-Т2", "B")), null, false);
    Tender t = tenderRepository.findBySourceExtId("900-1").orElseThrow();
    assertThat(t.getLots()).extracting(TenderLot::getSourceLotCode).containsExactlyInAnyOrder("900-1-Т1", "900-1-Т2", "900-1-Т3");
}
@Test
void completeList_stillRemovesVanishedLots() {
    writer.upsert(ann("901-1", 2), List.of(lot("901-1-Т1", "A"), lot("901-1-Т2", "B")), null, true);
    writer.upsert(ann("901-1", 1), List.of(lot("901-1-Т1", "A")), null, true);
    assertThat(tenderRepository.findBySourceExtId("901-1").orElseThrow().getLots()).hasSize(1);
}
```
(перед каждым — `tenderRepository.findBySourceExtId(...).ifPresent(tenderRepository::delete)`; `flush` + `em.clear()` между вызовами, чтобы проверять записанное.)

Сервис (моки клиента, HTML-фикстуры):
- `lotsPage` стр. 1 — `lots.html`, стр. 2 — страница без таблицы (`"<html><body>Too many requests</body></html>"`), а в фикстуре стр. 1 пейджер обещает стр. 2 (взять фикстуру с пейджером: `lots-ed-order.html` + `lots-ed-order-last.html` уже есть — подставить «битую» вторую), `lotsCount` из ленты больше полученного → тендер записан, `errors == 1`, `lastError` содержит «на площадке K лотов, получено M — лишние лоты не удалялись».
- упор в `max-lot-pages` (сервис с пределом 1 и многостраничной фикстурой) → та же ошибка.
- 0 лотов при `lotsCount > 0` и не услуги → `errors == 1` («лоты не разобраны: на площадке K, получено 0»), тендер не создан.
- полный список (`lots.html`, `lotsCount` = числу лотов фикстуры) → `errors == 0`.

`lotsCount` подставлять своей строкой ленты: построить `search.html`-подобную фикстуру нельзя дёшево — поэтому мокать `searchPage` реальной `search.html` и брать `lotsCount` объявления оттуда (первое = 12, см. `SkPharmacyHtmlParserTest`); если в фикстуре лотов число не совпадает — выбрать объявление и фикстуру, где совпадает, или скорректировать ожидание явно в тесте с комментарием.

Run → FAIL.

- [ ] **Step 2: Реализация**

`FetchedLots` дополнить: `record FetchedLots(List<SkLot> lots, boolean services, boolean truncated)`; `truncated = true`, если цикл вышел по `page > maxLotPages` при живой ссылке «вперёд» (упор) ИЛИ страница N>1 разобралась в 0 лотов при обещанной следующей странице (прервавшийся список).

В `fillImport` после фильтров:

```java
Integer expected = a.lotsCount();
int got = lots.size();
if (got == 0 && expected != null && expected > 0) {
    sum.addError("объявление " + a.numberAnno() + ": лоты не разобраны — на площадке " + expected + ", получено 0");
    continue;
}
boolean complete = !fetched.truncated() && (expected == null || got >= expected);
… writer.upsert(a, lots, general, complete) …
if (!complete) {
    sum.addError("объявление " + a.numberAnno() + ": на площадке " + (expected == null ? "больше" : expected)
            + " лотов, получено " + got + " — лишние лоты не удалялись");
}
```
⚠️ Проверка «0 лотов» — ДО фильтра релевантности по лотам (иначе пустой список уходит в фолбэк по имени объявления и тихо пишется тендер без лотов), но ПОСЛЕ проверки «услуги».

`SkPharmacyTenderWriter.rebuildLots(t, lots, complete)`: при `complete == false` — после сборки `result` добавить к нему все существующие лоты, которые не попали в `matched` (сравнение по ссылке), сохранив их как есть (номер `lotNumber` — продолжить нумерацию после новых), и только потом `clear()+addAll()`.

Run: `./gradlew test --tests '*SkPharmacy*' --tests '*ImportPreservesLotWork*' --tests '*ImportLotMergeFlush*'` → PASS.

- [ ] **Step 3: Мутация** — в `rebuildLots` игнорировать `complete` → `incompleteList_keepsUnmatchedExistingLots` красный. Откат, `compileJava`.

- [ ] **Step 4: Commit**

```bash
git add -A src/main/java/com/vladoose/nir/integration/skpharmacy src/main/resources/application.yaml src/test/java/com/vladoose/nir/integration
git commit -m "fix(import): СК-Фармация — лоты до 200 страниц, сверка с лентой, неполный список не удаляет лоты (A3)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Количество и сумма лота (B3)

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/ImportQuantity.java`
- Modify: `dto/LotDto.java` (`count` → `BigDecimal`), `GoszakupTenderWriter.java`, `SkLot.java` (+ `String rawQuantity`), `SkPharmacyHtmlParser.java` (количество), `SkPharmacyTenderWriter.java`; все места, создающие `SkLot`/`LotDto.setCount(int)` в тестах и `FakeGoszakupClient`
- Test: `src/test/java/com/vladoose/nir/integration/ImportQuantityTest.java`, `SkPharmacyHtmlParserTest.java`, `GoszakupImportServiceTest.java` (или тест райтера), `GoszakupDtoJsonTest.java`

**Interfaces:**
- Produces:
  - `ImportQuantity.wholePositiveOrNull(BigDecimal q) → Integer` — целое > 0 (в т.ч. «1.00») → int; дробное, ≤ 0, null, > `Integer.MAX_VALUE` → null.
  - `ImportQuantity.parse(String raw) → BigDecimal` — убирает пробелы/неразрывные пробелы-тысячи, запятую → точку; мусор/пусто → null.
  - `ImportQuantity.note(String raw) → String` — `"Количество на площадке: " + raw.trim()` (запятая/точка как на площадке).
  - `ImportQuantity.positiveMoneyOrNull(BigDecimal p) → BigDecimal` — null/≤0/целая часть > 13 цифр → null.
  - `ImportQuantity.withNote(String note, String text) → String` — note == null → text; text пуст → note; уже начинается с note → text; иначе `note + "\n" + text`.
  - `record SkLot(String code, String name, BigDecimal unitPrice, Integer quantity, String description, String rawQuantity)` — `rawQuantity` непуст ТОЛЬКО когда количество на площадке было, но не стало целым > 0.

- [ ] **Step 1: Тесты (падают)**

```java
class ImportQuantityTest {
    @Test void whole() {
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("1.00"))).isEqualTo(1);
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("487"))).isEqualTo(487);
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("2.5"))).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("0.5"))).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(BigDecimal.ZERO)).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(new BigDecimal("-3"))).isNull();
        assertThat(ImportQuantity.wholePositiveOrNull(null)).isNull();
    }
    @Test void parse() {
        assertThat(ImportQuantity.parse("1 000")).isEqualByComparingTo("1000");
        assertThat(ImportQuantity.parse("1 000.00")).isEqualByComparingTo("1000");
        assertThat(ImportQuantity.parse("2,5")).isEqualByComparingTo("2.5");
        assertThat(ImportQuantity.parse("шт")).isNull();
    }
    @Test void money() {
        assertThat(ImportQuantity.positiveMoneyOrNull(BigDecimal.ZERO)).isNull();
        assertThat(ImportQuantity.positiveMoneyOrNull(new BigDecimal("12345678901234"))).isNull();
        assertThat(ImportQuantity.positiveMoneyOrNull(new BigDecimal("180.50"))).isEqualByComparingTo("180.50");
    }
    @Test void withNote_idempotent() {
        String once = ImportQuantity.withNote("Количество на площадке: 2.5", "Описание");
        assertThat(ImportQuantity.withNote("Количество на площадке: 2.5", once)).isEqualTo(once);
    }
}
```
Парсер (`SkPharmacyHtmlParserTest`): существующий `parseLots_decimalQuantity_readAsWholeUnits` («1.00» → 1) остаётся зелёным (Review Focus 5); новый — HTML-строка таблицы с «2.5» и «0» → `quantity == null`, `rawQuantity` = «2.5» / «0»; «1.00» → `rawQuantity == null`.
goszakup JSON (`GoszakupDtoJsonTest`): `"count": 0.5` разбирается в `BigDecimal` 0.5 (раньше Jackson молча делал 0).
Импорт (`GoszakupImportServiceTest`): лот `count = 0.5`, `amount = 0` → тендер записан (`errors == 0`), у лота `quantity == null`, `maxCost == null`, `requiredSpec` начинается с «Количество на площадке: 0.5». СК (`SkPharmacyImportServiceTest` или тест райтера): лот с `rawQuantity = "2.5"` и пустым описанием → `requiredSpec == "Количество на площадке: 2.5"`.

Run → FAIL.

- [ ] **Step 2: Реализация**

- `LotDto.count` → `BigDecimal`; `GoszakupTenderWriter.rebuildLots`: `lot.setQuantity(ImportQuantity.wholePositiveOrNull(d.getCount()))`, `lot.setMaxCost(ImportQuantity.positiveMoneyOrNull(d.getAmount()))`; пометка — когда `d.getCount() != null && quantity == null`: `note = ImportQuantity.note(d.getCount().stripTrailingZeros().toPlainString())`; в ветке «ТЗ не разобрано» `lot.setRequiredSpec(ImportQuantity.withNote(note, d.getDescriptionRu()))`.
- `SkPharmacyHtmlParser`: вместо `intOrNull(tds, cols.qty())` — `String raw = txt(tds, cols.qty()); BigDecimal q = ImportQuantity.parse(raw); Integer qty = ImportQuantity.wholePositiveOrNull(q); String rawQty = (!raw.isBlank() && qty == null) ? raw : null;`. `intOrNull` для `lotsCount` ленты — оставить.
- `SkPharmacyTenderWriter.applyPortalDescription(lot, description, rawQuantity)`: пишем, только если поле пустое (как сейчас), текстом `ImportQuantity.withNote(rawQuantity == null ? null : ImportQuantity.note(rawQuantity), description)`; `priceOrNull` → `ImportQuantity.positiveMoneyOrNull` (локальный метод удалить).

Run: `./gradlew test --tests '*ImportQuantity*' --tests '*SkPharmacy*' --tests '*Goszakup*' --tests '*Import*'` → PASS.

- [ ] **Step 3: Мутация** — в парсере СК вернуть отрезание дробной части («2.5» → 2) → новый тест парсера красный. Откат, `compileJava`.

- [ ] **Step 4: Commit**

```bash
git add -A src/main/java/com/vladoose/nir/integration src/test/java/com/vladoose/nir/integration
git commit -m "fix(import): дробное и нулевое количество, нулевая сумма — пусто с пометкой, а не отказ записи (B3)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Замер «после», живой прогон, документация

**Files:**
- Modify: `docs/PROGRESS.md` (раздел «▶ Последняя задача»), `CLAUDE.md` (§5 «Госзакуп РК» — классификация, §8/§16 — пакет 1 сделан, §13 — число тестов, §14 — уроки, если появились), `docs/reviews/2026-10-06-import-review.md` (отметить A1–A5, B3, C1 сделанными)

- [ ] **Step 1: Гейт**

```bash
lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill
./gradlew cleanTest test
./gradlew cleanTest test --tests '*ImportCorpusReportTest*' -i | grep REPORT
```
Expected: 0 падений; строки `REPORT` — записать «после» рядом с «до» в progress.md.

- [ ] **Step 2: Живой прогон локально (приёмка ревью)**

```bash
GOSZAKUP_TOKEN="$(cat ~/.config/ais/goszakup.token)" JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun   # в фоне, sandbox off
```
+ `cd frontend && npm start`. До старта записать в progress.md: `select platform, count(*), (select count(*) from tender_lot l where l.tender_id in (select id from tender where platform=t.platform)) from tender t where platform is not null group by platform;`.
В браузере (Playwright; `localhost:4200`, admin / пароль из `~/.config/ais/ais-admin.pass` или dev `admin/admin`, `localStorage['ais.market']='KZ'`): «/tenders» → «Обновить тендеры» (без фильтра площадки — обе). Дождаться итога. Проверить:
- goszakup: в итоге нет «header parser received no bytes» (повторы сработали), «ошибок» = 0 или только неповторяемые с понятной причиной;
- СК-Фармация: нет ошибок «получено M из …» у тендеров, где на площадке лотов меньше 4000; тендеры, у которых было ровно 400 лотов, после прогона — с полным числом (SQL: `select t.tender_number, count(l.id) from tender t join tender_lot l on l.tender_id=t.id where t.platform='SK_PHARMACY' group by 1 having count(l.id) >= 400;`);
- запрос на тендер с > 50 лотов goszakup (если есть в ЗКО) — число лотов = `total` API;
- тост итога — по цвету (синий/красный/зелёный) соответствует исходу.
Записать «после» тем же SQL. Остановить бэкенд и фронт (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`, то же 4200).

- [ ] **Step 3: Документация**

PROGRESS «▶ Последняя задача»: что сделано, замер до/после (корпус + живой прогон), что ждёт оператора (push в main → после деплоя «Обновить тендеры» на проде; проверка SQL «ровно 400»), следующий пакет — 2 (автозапуск + Telegram). CLAUDE.md: в §5 обновить абзац «Классификация «медтовар» — по ЛОТАМ» (словарь `MedicalGoodsVocabulary`, услуги только по названию, сильные/слабые маркеры); в §16 ревью импорта — пакет 1 сделан; в §13 — новое число тестов; в §14 — урок, если всплыл. В ревью-документе — пометки «✔ сделано 2026-10-0X» у A1–A5, B3, C1.

- [ ] **Step 4: Commit**

```bash
git add docs/PROGRESS.md CLAUDE.md docs/reviews/2026-10-06-import-review.md
git commit -m "docs(import): пакет 1 «Не терять тендеры» — замер до/после, приёмка, что дальше

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
