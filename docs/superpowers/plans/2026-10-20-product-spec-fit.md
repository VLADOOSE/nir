# Сверка ТЗ с нашими товарами — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **В этом проекте — superpowers:executing-plans, всё в основной сессии, без субагентов** (CLAUDE.md §2, решение оператора 2026-10-18).

**Goal:** По каждому тендеру с «нашим видом» изделия АИС сама сверяет требования ТЗ с характеристиками наших товаров (сайт westmed.kz + карточка РУ на ndda.kz) и ставит вердикт «✓ подходит наш X / подходит по ТЗ, но РУ истекло / ◐ похоже / ✗ не подходит: причина» с пометкой «ТЗ под чужой бренд»; в Telegram (тема 95) — только «подходит» и «★»; в карточке тендера — блок «Сверка с нашим товаром» и «Требования ТЗ» списком; вкладка «Наша продукция → Товары» с РУ; «📋 Для ИИ» — готовый текст по тендерам для Claude / Gemini.

**Architecture:** Чистые функции в новом пакете `service/productwatch/fit`: разбор ТЗ на требования (`SpecRequirements` + `SpecNumbers` + `SpecUnit`), факты товара (`ProductCards`), сверка и вердикт (`SpecFit`), чужой бренд (`ForeignBrand`). Данные о РУ — новая таблица `product_registration` (V39), её наполняет `ProductRegistrationSync` (живой ndda.kz, свой поток каталога) после загрузки каталога. Снимок `CatalogSnapshot` получает карточки товаров с фактами и статусом РУ; `ProductMatchService.evaluate` считает сверку у отметок групп вида и пишет её в отметку (новые колонки V39); уровень `FITS` встаёт между `TYPE` и `NAMED`. Уведомитель и составитель сообщений — новый раздел «✅ Подходит наш товар»; очередь ТЗ — порог суммы наших лотов. Фронт: компонент `lot-fit` под `@defer` в карточке тендера, вкладка «Товары», кнопки «📋 Для ИИ».

**Tech Stack:** Java 17, Spring Boot 3.5.6 (Data JPA, Hibernate 6 JSONB, method security), Flyway, PostgreSQL 17, JUnit 5 + AssertJ, PDFBox (фикстуры ТЗ); Angular 21 (standalone, инлайн-шаблоны, `@defer`).

**Spec:** `docs/superpowers/specs/2026-10-20-product-spec-fit-design.md` (§16 — решения при планировании, дописываются задачей 1; §17 — при реализации).

## Global Constraints

- Миграция — **V39** (`V39__product_spec_fit.sql`); старые миграции не трогать.
- Только рынок **KZ**; фоновые прогоны — в исполнителе `product-watch` (`ProductWatchScheduler.runTask` уже ставит `MarketContext.set(Market.KZ)` и `clear()`).
- Сеть (ndda.kz, goszakup) — вне транзакции; запись — отдельным бином с `@Transactional` (не self-invoke).
- ndda.kz: пауза **300 мс** между номерами РУ; сбой номера — прежние данные остаются, в итоге «ошибок N»; при копировании «Для ИИ» живых запросов в ndda.kz нет.
- Без LLM. Числа требований и фактов — **по модулю** (знак не хранится).
- Уровни: `TYPE` < `FITS` < `NAMED` < `COPIED`; «★ точно наше» (`exact()`) — `NAMED`, `COPIED`; в Telegram (`alert()`, режим STAR) — `FITS`, `NAMED`, `COPIED`. Порог суммы наших лотов и тема 95 — `ProductNotifyPolicy` / `TELEGRAM_PRODUCTS_THREAD_ID` (в проде с 2026-10-20 07:52 UTC, не менять).
- Вердикт `FITS`: ни одного ✗, ✓ ≥ 50 % требований лота и ✓ ≥ 3, РУ товара `ACTIVE`; РУ не `ACTIVE` — `FITS_NO_RU`. Допуск точного значения ±15 %, признак — ≥ 60 % слов, требований меньше 2 — `NO_REQUIREMENTS`. Константы — рядом с замерами, калибровка — задача 14.
- Запись — `@PreAuthorize("hasRole('ADMIN')")`; чтение — любой вошедший; гард рынка тендера — `TenderService.findPublic` (404).
- «Для ИИ»: не больше **15** тендеров, ТЗ — до **4000** знаков на лот в общем списке, весь текст — до **120 000** знаков.
- Фронт: блок сверки — отдельный standalone-компонент под `@defer` (страница «Тендеры» в начальном бандле: 1,42 МБ при пределе 1,5 МБ); токены UI-kit, обе темы, цели 40 px при ≤ 900 px; классы с приставкой `lf-` / `pc-`, не совпадают с классами UI-kit (`styles.scss`); результаты промисов — `.then`, не нативный `await` (§14).
- `./gradlew` — с `dangerouslyDisableSandbox: true`; перед полным прогоном глушить `bootRun` и `devMail` (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`, `lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`); полный прогон — `./gradlew cleanTest test`.
- Ветка `feature/product-spec-fit`; каждый коммит кончается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Мутация — откат копией файла (`cp файл /tmp/fit-mut && … && cp /tmp/fit-mut файл && diff -q`), не `git checkout --`; серию мутаций заканчивать `./gradlew compileJava`.
- Запись на westmed.kz (характеристики Фотек и Ориона) — **только после явного «да» оператора на показанную таблицу**; токен goszakup общий с продом — живые запросы малыми порциями.

## Review Focus

1. **Несколько чисел в одном пункте ТЗ** («Габаритные размеры 295×295×100 мм, масса не более 4,5 кг»; «Питание 220 В, 50 Гц») — каждое число — своё требование со своим именем → тест `severalNumbersInOneClause` (задача 2).
2. **Кривые числа** («1 000 Вт» с пробелом, «0,5 л», «220±22 В», «−80 кПа», «от 30 до 300 уд/мин») — разбираются верно → тест `oddNumbers` (задача 2).
3. **Единица — в названии характеристики сайта** («Мощность, Вт» → «30»; «Срок службы, лет» → «5») — факт с единицей → тест `unitFromAttributeName` (задача 3).
4. **Наш бренд и чужой в одном ТЗ** («BOWA ARC 303 или эквивалент, например ФОТЕК ЭХВЧ-350») — чужой бренд «BOWA», наш не считается чужим → тест `oursAndForeignTogether` (задача 5).
5. **Лот без разобранного ТЗ, но с длинным описанием площадки с числами** (лоты СК-Фармации: колонка «Характеристика») — требования берутся из описания, вердикт считается → тест `descriptionOnlyLot_fitComputed` (задача 7).

## Решения при планировании (задача 1 переносит их в спеку §16)

1. Сверка — со **всеми** товарами группы, лучший — по вердикту (`SimilarProducts` отсекал бы родовые названия лотов: «Электрокоагулятор» по словам не похож на «ВЧ электрохирургический блок …»); варианты «ещё: …» — до 2 следующих с ✓ > 0.
2. «Обновить» на вкладке «Товары» — существующий `POST /api/site-catalog/sync` (`ProductWatchScheduler.startCatalog` теперь после каталога проверяет РУ и ставит пересчёт); отдельного `/refresh` нет.
3. «Для ИИ» списком — `POST /api/tenders/ai-brief {ids}`: отбор делает экран (тот же фильтр «Наш товар», что видит оператор), сервер проверяет рынок и вид каждого id (`findPublic`), лишние сверх 15 отбрасывает.
4. Числа — по модулю: «−80 кПа» = 80 (разрежение); температуры со знаком в требованиях редки — принято.
5. «г» без «р» / «рамм» — не граммы (путается с «2025 г.»); масса — «кг», «гр», «грамм».
6. Блок сверки и список требований в карточке тендера — standalone-компонент `LotFitComponent` под `@defer`.
7. В отметке — `fit_candidates` (JSONB): лучший товар + до 2 вариантов со строками сверки (≤ 80 строк на товар, текст ≤ 160 знаков) — переключение «ещё: …» без запроса к серверу.
8. Статус РУ товара: `ACTIVE` / `EXPIRED` / `NOT_FOUND` (в реестре нет) / `NOT_SPECIFIED` (на сайте нет №) / `UNKNOWN` (не проверено или сбой без прежних данных); не `ACTIVE` → `FITS_NO_RU` с текстом «РУ истекло / не найдено / не указано / не проверено».
9. Чужой бренд ищется в русской части ТЗ (`required_spec` хранит только её): поле «Өндіруші» казахской части недоступно — BOWA ловится по «BOWA ARC 303» (слово латиницей с кодом модели рядом).
10. В JSON сверки отметки — имена `OK` / `FAIL` / `DIFF` / `UNKNOWN`; фронт рисует ✓ / ✗ / ≈ / ?.
11. У лота в API — `fit`, `foreignBrand` и `candidates` (лучший первым, у каждого — вердикт, счёт, РУ, ссылка и строки сверки) вместо `fitProduct` / `fitMet` / `fitTotal` / `fitReason` / `fitRows` / `alternatives` спеки §11: та же информация одной формой, «ещё: …» берёт строки варианта без запроса. «Требования ТЗ» — списком строк `requirements` у каждого лота с разобранным ТЗ; экран показывает его там, где сверки нет.
12. Буфер обмена «Для ИИ» — `navigator.clipboard.write` с `ClipboardItem`, которому отдаётся обещание текста (Safari на iPhone не пускает запись после сетевого ответа, а `writeText` из нажатия получил бы текст только после запроса); нет `ClipboardItem` — `writeText`; отказ — окно с текстом «выделите и скопируйте».
13. «Лот № …» в тексте «Для ИИ» — номер лота площадки (`source_lot_code`, «87197521-ОИ2»: по нему лот ищут на портале), нет — порядковый.
14. Текст сверки (тело ТЗ или описание площадки) — один помощник `ProductMatchService.fitText` / `fitFor`: его зовут отметка, «Требования ТЗ», «Для ИИ» и гейт — качество проверяется тем же кодом, что работает (CLAUDE.md §14).

## Карта файлов

Сервер (`src/main/java/com/vladoose/nir/…`):

| Файл | Что |
|---|---|
| `resources/db/migration/V39__product_spec_fit.sql` | `product_registration`; колонки сверки у `tender_lot_product_match` |
| `service/productwatch/fit/SpecUnit.java` | единицы, семейства, перевод к базовой |
| `service/productwatch/fit/SpecNumbers.java` | числа с условием и единицей в тексте (общий для ТЗ и товара) |
| `service/productwatch/fit/SpecRequirement.java`, `SpecRequirements.java` | требования ТЗ: тело ТЗ, склейка строк, пункты, «не требования» |
| `service/productwatch/fit/FitText.java` | стемы и синонимы параметров |
| `service/productwatch/fit/ProductFact.java`, `ProductCard.java`, `ProductCards.java`, `RegistrationInfo.java`; `entity/RegStatus.java` | карточка товара: факты и РУ |
| `service/productwatch/fit/SpecFit.java`, `LotFit.java`; `entity/FitVerdict.java`, `FitMark.java`, `FitRow.java`, `FitCandidate.java` (лежат в JSONB отметки) | сверка и вердикт |
| `service/productwatch/fit/ForeignBrand.java` | чужой бренд |
| `service/productwatch/fit/RegNumbers.java` | нормализация № РУ |
| `entity/ProductRegistration.java`, `entity/RegistrationComponent.java` | РУ товара сайта (JSONB комплектности) |
| `repository/ProductRegistrationRepository.java` | |
| `integration/ndda/NddaRegistration.java` (+ поля `NddaListItemDto`, метод `NddaClient.findRegistration`) | РУ по номеру: действующие, потом недействующие |
| `service/productwatch/ProductRegistrationSync.java`, `ProductRegistrationWriter.java` | проверка РУ товаров сайта |
| изменяются `entity/ProductMatchLevel.java`, `entity/TenderLotProductMatch.java` | `FITS`, `alert()`; поля сверки |
| изменяются `service/productwatch/CatalogSnapshot.java`, `ProductCatalog.java`, `ProductMatchService.java`, `ProductSpecChecker.java`, `ProductWatchNotifier.java`, `ProductWatchComposer.java`, `ProductMatchQuery.java`, `ProductWatchScheduler.java`, `ProductNotifyPolicy.java` | встраивание |
| `service/productwatch/OurLots.java` | сумма наших лотов (из `ProductWatchNotifier.ourLotsSum`) |
| изменяются `repository/TenderLotProductMatchRepository.java`, `repository/MedRegistryRepository.java` | порядок уровней в SQL; производители реестра |
| изменяются `dto/response/LotProductMatchDto.java`, `dto/response/TenderLotResponse.java`, `controller/TenderController.java`, `controller/ProductWatchController.java` | API |
| `dto/response/ProductCatalogResponse.java`, `dto/response/AiBriefResponse.java`, `dto/request/AiBriefRequest.java` | |
| `service/productwatch/ProductCatalogQuery.java` | вкладка «Товары» |
| `service/productwatch/ProductAiBrief.java` | текст «Для ИИ» |

Тесты (`src/test/java/com/vladoose/nir/…`): `service/productwatch/fit/SpecNumbersTest`, `SpecRequirementsTest`, `ProductCardsTest`, `SpecFitTest`, `ForeignBrandTest`, `RegNumbersTest`; `productwatch/ProductRegistrationSyncTest`, `ProductFitEvaluateTest`, `ProductSpecCheckerThresholdTest`, `ProductFitNotifyTest`, `ProductCatalogApiTest`, `ProductAiBriefTest`, `ProductFitQualityTest`; ресурсы `product-watch/fit-golden.tsv`, `product-watch/registrations.json`.

Фронт (`frontend/src/app/…`): `shared/product-watch.ts` (типы), `pages/tenders/lot-fit.component.ts` (новый), `pages/tenders/tender-lots.component.ts`, `pages/tenders/tenders.component.ts`, `pages/product-groups/product-groups.component.ts`, `pages/product-groups/product-catalog.component.ts` (новый), `shared/ai-brief-button.component.ts` (кнопка «📋 Для ИИ», буфер обмена), `services/api.service.ts`.

Данные: `docs/research/2026-10-20-fotek-orion-attributes.md` + `.json` (задача 1).

---

### Task 1: Характеристики Фотек и Ориона — таблица для оператора; решения в спеку

Не код АИС: собрать характеристики основных аппаратов, у которых на сайте их нет, **только с официальных сайтов производителей**, и показать оператору таблицей. Запись на сайт — задача 15, после «да».

**Files:**
- Create: `docs/research/2026-10-20-fotek-orion-attributes.md`, `docs/research/2026-10-20-fotek-orion-attributes.json`
- Modify: `docs/superpowers/specs/2026-10-20-product-spec-fit-design.md` (§16)

- [ ] **Step 1: Ветка и список товаров без характеристик**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout -b feature/product-spec-fit
python3 - <<'EOF'
import json
items=json.load(open('src/test/resources/product-watch/site-catalog-full.json'))
for p in items:
    if p['brand'] in ('Фотек','Орион') and not p.get('attributes'):
        print(p['brand'],'|',p['id'],'|',p['name'][:110],'|',p.get('registrationCert'))
EOF
```

Expected: 77 строк Фотек (из 84) и 14 строк Ориона. Основные аппараты Фотек — ЭХВЧ-80-03, ЭХВЧ-350-01 / 02 / 03, ЭХВЧа-140-02, генераторы ONYX / ONYX-A, блок управления АСД, наборы АСД; остальное — принадлежности (кабели, педали, электроды, стойки, держатели): у них берём только то, что указано у производителя (длина кабеля, размер электрода, площадь).

- [ ] **Step 2: Официальные источники**

Сайты производителей — из исследования V28 (`.superpowers/research/2026-10-12-westmed-manufacturers.md`: ФОТЕК, Орион-Си). Для каждого аппарата — страница товара или паспорт / руководство на сайте производителя (WebFetch). Правило (CLAUDE.md, V9): только то, что написано у производителя; число — с единицей, как у производителя; ничего не выводить и не округлять.

- [ ] **Step 3: Таблица**

`docs/research/2026-10-20-fotek-orion-attributes.json` — массив:

```json
[
  {
    "siteId": "UUID товара на сайте",
    "name": "ВЧ электрохирургический блок для аппарата ЭХВЧ-350-03-\"ФОТЕК\"",
    "source": "https://… (страница производителя)",
    "attributes": [
      {"name": "Максимальная выходная мощность (резание)", "value": "350 Вт"},
      {"name": "Рабочая частота", "value": "440 кГц"}
    ]
  }
]
```

(значения в примере — форма, не данные: в файл идут только числа со страницы производителя). `docs/research/2026-10-20-fotek-orion-attributes.md` — то же для чтения: по товару — ссылка на источник и таблица «характеристика | значение»; в начале — сколько товаров и характеристик, что не нашлось.

- [ ] **Step 4: Решения при планировании → спека §16**

В `docs/superpowers/specs/2026-10-20-product-spec-fit-design.md` заменить «(пусто)» в §16 на список «Решения при планировании» из этого плана (все пункты, дословно).

- [ ] **Step 5: Коммит и показ оператору**

```bash
git add docs/research/2026-10-20-fotek-orion-attributes.* docs/superpowers/specs/2026-10-20-product-spec-fit-design.md
git commit -m "docs(research): характеристики Фотек и Ориона с сайтов производителей — таблица для оператора; решения при планировании

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

Оператору в чат: сколько товаров и характеристик собрано, ссылка на `.md`, вопрос «вносим на westmed.kz?». Ответ не ждать — дальше задачи 2–14; запись на сайт — задача 15.

---

### Task 2: Требования ТЗ — числа, единицы, пункты

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/productwatch/fit/SpecUnit.java`, `SpecNumbers.java`, `SpecRequirement.java`, `SpecRequirements.java`
- Test: `src/test/java/com/vladoose/nir/service/productwatch/fit/SpecNumbersTest.java`, `SpecRequirementsTest.java`

**Interfaces:**
- Produces:
  - `enum SpecUnit { …; String family(); double toBase(double v); String label() }`, `static SpecUnit SpecUnit.parseAt(String normalized, int from)` → единица, начинающаяся в позиции `from` (после пробелов), или `null`; `static int SpecUnit.lastEnd()` не нужен — `parseAt` возвращает `SpecUnit.Hit(SpecUnit unit, int end)`.
  - `enum SpecCmp { MIN, MAX, RANGE, UPTO, APPROX }`
  - `record SpecNumbers.Hit(SpecCmp cmp, double lo, double hi, SpecUnit unit, List<Double> dims, int start, int end)` — `lo` / `hi` в базовых единицах; `dims` — три (или два) числа по убыванию, иначе пусто; `start` — начало условия или числа, `end` — конец единицы или числа.
  - `static List<SpecNumbers.Hit> SpecNumbers.find(String normalized)` — все числа с единицами и счётные «— 12» в конце пункта.
  - `record SpecRequirement(String text, String name, Kind kind, SpecCmp cmp, double lo, double hi, SpecUnit unit, List<Double> dims)`, `enum Kind { NUMBER, COUNT, DIMS, FEATURE }`.
  - `static String SpecRequirements.body(String requiredSpec)` — русская часть, после «характеристики закупаемых товаров», строки склеены.
  - `static List<SpecRequirement> SpecRequirements.extract(String body)` — до 150 требований.

- [ ] **Step 1: Тест чисел (красный)**

```java
package com.vladoose.nir.service.productwatch.fit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Числа требований и характеристик: условие, единица, диапазон, габариты (задача 2 плана 2026-10-20-product-spec-fit). */
class SpecNumbersTest {

    private static SpecNumbers.Hit one(String text) {
        List<SpecNumbers.Hit> hits = SpecNumbers.find(text);
        assertThat(hits).as(text).hasSize(1);
        return hits.get(0);
    }

    @Test
    void minMaxRangeUpto() {
        SpecNumbers.Hit min = one("производительность не менее 15 л/мин");
        assertThat(min.cmp()).isEqualTo(SpecCmp.MIN);
        assertThat(min.lo()).isEqualTo(15.0);
        assertThat(min.unit()).isEqualTo(SpecUnit.L_MIN);

        SpecNumbers.Hit max = one("масса не более 4,5 кг");
        assertThat(max.cmp()).isEqualTo(SpecCmp.MAX);
        assertThat(max.hi()).isEqualTo(4500.0);          // база массы — граммы
        assertThat(max.unit()).isEqualTo(SpecUnit.KG);

        SpecNumbers.Hit range = one("производительность отсасывания: примерно 15–30 л/мин");
        assertThat(range.cmp()).isEqualTo(SpecCmp.RANGE);
        assertThat(range.lo()).isEqualTo(15.0);
        assertThat(range.hi()).isEqualTo(30.0);

        SpecNumbers.Hit upto = one("уровень вакуума: до 75 кпа");
        assertThat(upto.cmp()).isEqualTo(SpecCmp.UPTO);
        assertThat(upto.hi()).isEqualTo(75.0);
    }

    /** Review Focus #2: «1 000», «0,5», «220±22», «−80», «от 30 до 300». */
    @Test
    void oddNumbers() {
        assertThat(one("мощность 1 000 вт").lo()).isEqualTo(1000.0);
        assertThat(one("объем 0,5 л").lo()).isEqualTo(500.0);
        SpecNumbers.Hit pm = one("напряжение 220±22 в");
        assertThat(pm.cmp()).isEqualTo(SpecCmp.RANGE);
        assertThat(pm.lo()).isEqualTo(198.0);
        assertThat(pm.hi()).isEqualTo(242.0);
        SpecNumbers.Hit neg = one("разрежение от −5 до −80 кпа");
        assertThat(neg.cmp()).isEqualTo(SpecCmp.RANGE);
        assertThat(neg.lo()).isEqualTo(5.0);
        assertThat(neg.hi()).isEqualTo(80.0);
        SpecNumbers.Hit from = one("частота от 30 до 300 гц");
        assertThat(from.lo()).isEqualTo(30.0);
        assertThat(from.hi()).isEqualTo(300.0);
    }

    @Test
    void dimensionsSortedDescending() {
        SpecNumbers.Hit d = one("габаритные размеры не более 295х100х295 мм");
        assertThat(d.cmp()).isEqualTo(SpecCmp.MAX);
        assertThat(d.dims()).containsExactly(295.0, 295.0, 100.0);
        assertThat(d.unit()).isEqualTo(SpecUnit.MM);
    }

    @Test
    void bareNumberIsApprox_trailingCountIsCount() {
        SpecNumbers.Hit w = one("мощность лампы - 20 вт");
        assertThat(w.cmp()).isEqualTo(SpecCmp.APPROX);
        assertThat(w.lo()).isEqualTo(20.0);
        SpecNumbers.Hit count = one("количество ламп - 1");
        assertThat(count.unit()).isNull();
        assertThat(count.lo()).isEqualTo(1.0);
    }

    /** «2025 г.» — не граммы; «г» без «р» / «рамм» единицей не считается. */
    @Test
    void yearIsNotGrams() {
        assertThat(SpecNumbers.find("год выпуска не ранее 2025 г.")).isEmpty();
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.SpecNumbersTest'` (sandbox off)
Expected: FAIL — ошибка компиляции «cannot find symbol SpecNumbers».

- [ ] **Step 3: `SpecUnit`**

```java
package com.vladoose.nir.service.productwatch.fit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Единица требования ТЗ или характеристики товара (спека 2026-10-20-product-spec-fit §5.2): семейство — что с чем
 * сравнимо, множитель — к базовой единице семейства. Текст — уже нормализованный ({@code ProductText.normalize}).
 */
public enum SpecUnit {
    W("мощность", 1, "Вт"), KW("мощность", 1000, "кВт"), VA("полная мощность", 1, "ВА"),
    HZ("частота", 1, "Гц"), KHZ("частота", 1e3, "кГц"), MHZ("частота", 1e6, "МГц"),
    MM("длина", 1, "мм"), CM("длина", 10, "см"), M("длина", 1000, "м"),
    G("масса", 1, "г"), KG("масса", 1000, "кг"),
    ML("объём", 1, "мл"), L("объём", 1000, "л"),
    L_MIN("поток", 1, "л/мин"), M3_H("поток воздуха", 1, "м³/ч"),
    KPA("давление", 1, "кПа"), MMHG("давление", 0.1333224, "мм рт. ст."),
    V("напряжение", 1, "В"), UV("малое напряжение", 1, "мкВ"), MV("малое напряжение", 1000, "мВ"),
    MA("ток", 1, "мА"), A("ток", 1000, "А"),
    OHM("сопротивление", 1, "Ом"), KOHM("сопротивление", 1e3, "кОм"), MOHM("сопротивление", 1e6, "МОм"),
    S("время", 1, "с"), MIN("время", 60, "мин"), H("время", 3600, "ч"),
    YEAR("срок", 12, "лет"), MONTH("срок", 1, "мес"),
    PCT("доля", 1, "%"), DB("звук", 1, "дБ"), M2("площадь", 1, "м²"), W_M2("облучённость", 1, "Вт/м²"),
    CEL("температура", 1, "°C"), CHANNEL("каналы", 1, "кан."), PCS("штуки", 1, "шт");

    private final String family;
    private final double factor;
    private final String label;

    SpecUnit(String family, double factor, String label) {
        this.family = family; this.factor = factor; this.label = label;
    }

    public String family() { return family; }
    public double toBase(double v) { return v * factor; }
    public double fromBase(double v) { return v / factor; }
    public String label() { return label; }

    /** Единица в позиции from: её конец (позиция за ней). */
    public record Hit(SpecUnit unit, int end) {}

    /** Написания (нормализованный текст), длинные — раньше: «л/мин» раньше «л», «мм рт. ст.» раньше «мм». */
    private static final List<Map.Entry<String, SpecUnit>> FORMS;
    static {
        List<Map.Entry<String, SpecUnit>> f = new ArrayList<>(List.of(
                Map.entry("л/мин", L_MIN), Map.entry("л / мин", L_MIN), Map.entry("литров в минуту", L_MIN),
                Map.entry("м3/ч", M3_H), Map.entry("м³/ч", M3_H), Map.entry("м3/час", M3_H), Map.entry("куб.м/час", M3_H),
                Map.entry("куб. м/час", M3_H), Map.entry("куб.м/ч", M3_H), Map.entry("куб. м/ч", M3_H),
                Map.entry("мм рт. ст.", MMHG), Map.entry("мм рт.ст.", MMHG), Map.entry("мм рт ст", MMHG), Map.entry("мм.рт.ст.", MMHG),
                Map.entry("вт/м2", W_M2), Map.entry("вт/м²", W_M2), Map.entry("вт/кв.м", W_M2),
                Map.entry("кв.м", M2), Map.entry("кв. м", M2), Map.entry("м2", M2), Map.entry("м²", M2),
                Map.entry("кпа", KPA), Map.entry("квт", KW), Map.entry("вт", W), Map.entry("ва", VA),
                Map.entry("кгц", KHZ), Map.entry("мгц", MHZ), Map.entry("гц", HZ),
                Map.entry("мкв", UV), Map.entry("мв", MV), Map.entry("мом", MOHM), Map.entry("ком", KOHM), Map.entry("ом", OHM),
                Map.entry("ма", MA), Map.entry("ампер", A),
                Map.entry("мм", MM), Map.entry("см", CM), Map.entry("мл", ML), Map.entry("кг", KG), Map.entry("грамм", G),
                Map.entry("гр", G), Map.entry("дб", DB), Map.entry("%", PCT), Map.entry("°c", CEL), Map.entry("°с", CEL),
                Map.entry("градусов", CEL), Map.entry("град", CEL),
                Map.entry("часов", H), Map.entry("часа", H), Map.entry("час", H), Map.entry("ч", H),
                Map.entry("минут", MIN), Map.entry("мин", MIN), Map.entry("секунд", S), Map.entry("сек", S), Map.entry("с", S),
                Map.entry("месяцев", MONTH), Map.entry("месяца", MONTH), Map.entry("мес", MONTH),
                Map.entry("лет", YEAR), Map.entry("года", YEAR), Map.entry("год", YEAR),
                Map.entry("-канальный", CHANNEL), Map.entry("канальный", CHANNEL), Map.entry("каналов", CHANNEL),
                Map.entry("канала", CHANNEL), Map.entry("канал", CHANNEL),
                Map.entry("штук", PCS), Map.entry("шт", PCS), Map.entry("л", L), Map.entry("м", M)));
        f.sort(Comparator.comparingInt((Map.Entry<String, SpecUnit> e) -> -e.getKey().length()));
        FORMS = List.copyOf(f);
    }

    /** Единица, начинающаяся в from (пробелы пропускаются); за ней — не буква и не цифра. null — единицы нет. */
    public static Hit parseAt(String t, int from) {
        int i = from;
        while (i < t.length() && (t.charAt(i) == ' ' || t.charAt(i) == ' ')) i++;
        for (Map.Entry<String, SpecUnit> e : FORMS) {
            String form = e.getKey();
            if (!t.startsWith(form, i)) continue;
            int end = i + form.length();
            if (end < t.length() && Character.isLetterOrDigit(t.charAt(end)) && !form.endsWith(".")) continue;
            return new Hit(e.getValue(), end);
        }
        return null;
    }
}
```

- [ ] **Step 4: `SpecCmp` и `SpecNumbers`**

`SpecCmp.java`:

```java
package com.vladoose.nir.service.productwatch.fit;

/** Условие числа: «не менее», «не более», диапазон, «до» (смысл — по параметру, §5.2 спеки), число без условия. */
public enum SpecCmp { MIN, MAX, RANGE, UPTO, APPROX }
```

`SpecNumbers.java`:

```java
package com.vladoose.nir.service.productwatch.fit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Числа с условием и единицей в нормализованном тексте (спека §5.2) — общий разбор для требований ТЗ и характеристик
 * товара. Числа — по модулю (решение 4 плана): «−80 кПа» = 80.
 */
public final class SpecNumbers {

    public record Hit(SpecCmp cmp, double lo, double hi, SpecUnit unit, List<Double> dims, int start, int end) {}

    private static final String NUM = "(\\d{1,3}(?:[ \\u00a0]\\d{3})+|\\d+)(?:[.,](\\d+))?";
    /** Габариты: 2–3 числа через «х / x / × / *». */
    private static final Pattern DIMS = Pattern.compile(NUM + "\\s*[хx×*]\\s*" + NUM + "(?:\\s*[хx×*]\\s*" + NUM + ")?");
    /** Диапазон: «от A до B», «A–B», «A - B», «A...B», «A±D». */
    private static final Pattern RANGE = Pattern.compile("(?:от\\s*)?[-−–]?\\s*" + NUM
            + "\\s*(?:до|[-−–—]|\\.\\.\\.?|…)\\s*(?:до\\s*)?[-−–]?\\s*" + NUM);
    private static final Pattern PLUS_MINUS = Pattern.compile(NUM + "\\s*±\\s*" + NUM);
    private static final Pattern SINGLE = Pattern.compile("[-−–]?\\s*" + NUM);
    /** Счётное без единицы: «… - 1», «…: 12» в конце пункта. */
    private static final Pattern TRAILING_COUNT = Pattern.compile("(?:\\s[-–—]|:)\\s*(\\d{1,3})\\s*\\.?\\s*$");

    private static final String[] MIN_WORDS = {"не менее", "не ниже", "не меньше", "минимум", "мин.", "≥", ">=", "более", "свыше", "больше"};
    private static final String[] MAX_WORDS = {"не более", "не выше", "не больше", "максимум", "макс.", "≤", "<=", "менее", "меньше"};
    private static final String[] APPROX_WORDS = {"примерно", "около", "порядка", "~"};

    private SpecNumbers() {}

    public static List<Hit> find(String t) {
        List<Hit> out = new ArrayList<>();
        if (t == null || t.isBlank()) return out;
        boolean[] used = new boolean[t.length()];
        // 1) габариты
        Matcher d = DIMS.matcher(t);
        while (d.find()) {
            SpecUnit.Hit u = SpecUnit.parseAt(t, d.end());
            List<Double> dims = new ArrayList<>();
            dims.add(num(d.group(1), d.group(2)));
            dims.add(num(d.group(3), d.group(4)));
            if (d.group(5) != null) dims.add(num(d.group(5), d.group(6)));
            SpecUnit unit = u == null ? SpecUnit.MM : u.unit();
            if (!unit.family().equals("длина")) continue;
            dims.replaceAll(unit::toBase);
            dims.sort((a, b) -> Double.compare(b, a));
            int start = cmpStart(t, d.start());
            SpecCmp cmp = cmpBefore(t, d.start());
            out.add(new Hit(cmp == SpecCmp.UPTO ? SpecCmp.MAX : cmp, dims.get(dims.size() - 1), dims.get(0), unit, List.copyOf(dims),
                    start, u == null ? d.end() : u.end()));
            mark(used, d.start(), u == null ? d.end() : u.end());
        }
        // 2) «A±D»
        Matcher pm = PLUS_MINUS.matcher(t);
        while (pm.find()) {
            if (used[pm.start()]) continue;
            SpecUnit.Hit u = SpecUnit.parseAt(t, pm.end());
            if (u == null) continue;
            double c = num(pm.group(1), pm.group(2)), dv = num(pm.group(3), pm.group(4));
            out.add(new Hit(SpecCmp.RANGE, u.unit().toBase(c - dv), u.unit().toBase(c + dv), u.unit(), List.of(),
                    pm.start(), u.end()));
            mark(used, pm.start(), u.end());
        }
        // 3) диапазоны
        Matcher r = RANGE.matcher(t);
        while (r.find()) {
            if (used[r.start()] || used[Math.max(r.start(), r.end() - 1)]) continue;
            SpecUnit.Hit u = SpecUnit.parseAt(t, r.end());
            if (u == null) continue;
            double a = num(r.group(1), r.group(2)), b = num(r.group(3), r.group(4));
            if (a > b) { double x = a; a = b; b = x; }
            out.add(new Hit(SpecCmp.RANGE, u.unit().toBase(a), u.unit().toBase(b), u.unit(), List.of(), cmpStart(t, r.start()), u.end()));
            mark(used, r.start(), u.end());
        }
        // 4) одиночные числа с единицей
        Matcher s = SINGLE.matcher(t);
        while (s.find()) {
            int numStart = s.start(1);
            if (used[numStart]) continue;
            if (numStart > 0 && Character.isLetterOrDigit(t.charAt(numStart - 1))) continue;   // «ЭК12Т», «SpO2»
            SpecUnit.Hit u = SpecUnit.parseAt(t, s.end());
            if (u == null) continue;
            double raw = num(s.group(1), s.group(2));
            if (u.unit() == SpecUnit.YEAR && raw >= 1990 && raw <= 2100) continue;   // «2025 года» — дата, не срок
            double v = u.unit().toBase(raw);
            SpecCmp cmp = cmpBefore(t, s.start());
            double lo = cmp == SpecCmp.MAX || cmp == SpecCmp.UPTO ? 0 : v;
            double hi = cmp == SpecCmp.MIN ? Double.POSITIVE_INFINITY : v;
            out.add(new Hit(cmp, cmp == SpecCmp.APPROX ? v : lo, cmp == SpecCmp.APPROX ? v : hi, u.unit(), List.of(),
                    cmpStart(t, s.start()), u.end()));
            mark(used, s.start(), u.end());
        }
        // 5) счётное в конце пункта без единицы
        Matcher c = TRAILING_COUNT.matcher(t);
        if (c.find() && !used[c.start(1)]) {
            double v = Double.parseDouble(c.group(1));
            out.add(new Hit(SpecCmp.APPROX, v, v, null, List.of(), c.start(), t.length()));
        }
        out.sort((x, y) -> Integer.compare(x.start(), y.start()));
        return out;
    }

    /** Слова условий; «не более» / «не менее» заменены метками до поиска — иначе «более» внутри них давало бы обратное. */
    private record CmpWord(String w, SpecCmp cmp) {}
    private static final List<CmpWord> CMP_WORDS = List.of(
            new CmpWord("‹min›", SpecCmp.MIN), new CmpWord("минимум", SpecCmp.MIN), new CmpWord("мин.", SpecCmp.MIN),
            new CmpWord("≥", SpecCmp.MIN), new CmpWord(">=", SpecCmp.MIN), new CmpWord(" более ", SpecCmp.MIN),
            new CmpWord(" свыше ", SpecCmp.MIN), new CmpWord(" больше ", SpecCmp.MIN), new CmpWord(" от ", SpecCmp.MIN),
            new CmpWord("‹max›", SpecCmp.MAX), new CmpWord("максимум", SpecCmp.MAX), new CmpWord("макс.", SpecCmp.MAX),
            new CmpWord("≤", SpecCmp.MAX), new CmpWord("<=", SpecCmp.MAX), new CmpWord(" менее ", SpecCmp.MAX),
            new CmpWord(" меньше ", SpecCmp.MAX),
            new CmpWord("примерно", SpecCmp.APPROX), new CmpWord("около", SpecCmp.APPROX), new CmpWord("порядка", SpecCmp.APPROX),
            new CmpWord("~", SpecCmp.APPROX), new CmpWord(" до ", SpecCmp.UPTO));

    /** Голова перед числом: до 30 символов, но не дальше предыдущей «, » / «;» — условие соседнего числа не наше. */
    static String head(String t, int at) {
        String h = t.substring(Math.max(0, at - 30), at);
        int cut = Math.max(h.lastIndexOf(", "), h.lastIndexOf(';'));
        return cut >= 0 ? h.substring(cut + 1) : h;
    }

    /** Условие перед числом (спека §5.2); нет слова условия — точное значение (APPROX). */
    static SpecCmp cmpBefore(String t, int at) {
        String h = (" " + head(t, at).toLowerCase(Locale.ROOT) + " ")
                .replace("не менее", "‹min›").replace("не ниже", "‹min›").replace("не меньше", "‹min›")
                .replace("не более", "‹max›").replace("не выше", "‹max›").replace("не больше", "‹max›");
        int best = -1;
        SpecCmp cmp = SpecCmp.APPROX;
        for (CmpWord w : CMP_WORDS) {
            int i = h.lastIndexOf(w.w());
            if (i > best) { best = i; cmp = w.cmp(); }
        }
        return cmp;
    }

    /** Начало условия перед числом (для текста требования): само число, если условия нет. */
    private static int cmpStart(String t, int at) {
        String h = head(t, at);
        int off = at - h.length();
        int best = at;
        for (String w : new String[]{"не менее", "не ниже", "не меньше", "не более", "не выше", "не больше", "примерно", "около", "от ", "до "}) {
            int i = h.lastIndexOf(w);
            if (i >= 0 && off + i < best && at - (off + i) <= 14) best = off + i;
        }
        return best;
    }

    private static double num(String whole, String frac) {
        String w = whole.replace(" ", "").replace(" ", "");
        return Double.parseDouble(frac == null ? w : w + "." + frac);
    }

    private static void mark(boolean[] used, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(used.length, to); i++) used[i] = true;
    }
}
```

- [ ] **Step 5: Прогнать числа — зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.SpecNumbersTest'`
Expected: PASS (5 tests). Красное — править разбор, не тест (ожидания выведены руками из живых ТЗ §3 спеки).

- [ ] **Step 6: Тест требований (красный)**

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.productwatch.ProductFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Требования ТЗ из настоящих ТЗ набора (спека §5): склейка строк, «;» и столбик, «не требования». */
class SpecRequirementsTest {

    private static List<SpecRequirement> of(String file) {
        return SpecRequirements.extract(SpecRequirements.body(ProductFixtures.spec(file)));
    }

    /** Аспиратор Мангистау 17729514-1: требования через «;», строки PDF разорваны посередине. */
    @Test
    void aspirator_semicolonsAndBrokenLines() {
        List<SpecRequirement> r = of("17729514-1_87973751-ОИ4.pdf");
        assertThat(r).extracting(SpecRequirement::kind).contains(SpecRequirement.Kind.NUMBER, SpecRequirement.Kind.FEATURE);
        SpecRequirement flow = r.stream().filter(x -> x.unit() == SpecUnit.L_MIN).findFirst().orElseThrow();
        assertThat(flow.cmp()).isEqualTo(SpecCmp.RANGE);
        assertThat(flow.lo()).isEqualTo(15.0);
        assertThat(flow.hi()).isEqualTo(30.0);
        SpecRequirement vacuum = r.stream().filter(x -> x.unit() == SpecUnit.KPA).findFirst().orElseThrow();
        assertThat(vacuum.hi()).isEqualTo(75.0);
        assertThat(r).extracting(SpecRequirement::text).anyMatch(t -> t.contains("защиты от переполнения"));
        // назначение и область применения — не требования
        assertThat(r).extracting(SpecRequirement::text).noneMatch(t -> t.toLowerCase().contains("предназначен"));
        assertThat(r).extracting(SpecRequirement::text).noneMatch(t -> t.toLowerCase().contains("может применяться"));
    }

    /** Облучатель Абай 17822225-1: столбик «параметр — значение»; счётное «Количество ламп — 1». */
    @Test
    void irradiator_columnsAndCount() {
        List<SpecRequirement> r = of("17822225-1_88296583-ЗЦП1.pdf");
        SpecRequirement lamps = r.stream().filter(x -> x.name().contains("количество ламп")).findFirst().orElseThrow();
        assertThat(lamps.kind()).isEqualTo(SpecRequirement.Kind.COUNT);
        assertThat(lamps.lo()).isEqualTo(1.0);
        SpecRequirement power = r.stream().filter(x -> x.unit() == SpecUnit.W).findFirst().orElseThrow();
        assertThat(power.name()).contains("мощность лампы");
        assertThat(power.cmp()).isEqualTo(SpecCmp.APPROX);
        assertThat(r).extracting(SpecRequirement::text).anyMatch(t -> t.contains("настенно-потолочный"));
        assertThat(r).extracting(SpecRequirement::text).noneMatch(t -> t.toLowerCase().contains("места поставки"));
    }

    /** Review Focus #1: несколько чисел в одном пункте — каждое своё требование со своим именем. */
    @Test
    void severalNumbersInOneClause() {
        List<SpecRequirement> r = SpecRequirements.extract("Габаритные размеры блока не более 295х295х100 мм, масса не более 4,5 кг; "
                + "Питание 220 В, 50 Гц");
        assertThat(r).hasSize(4);
        assertThat(r.get(0).kind()).isEqualTo(SpecRequirement.Kind.DIMS);
        assertThat(r.get(1).name()).contains("масса");
        assertThat(r.get(1).unit()).isEqualTo(SpecUnit.KG);
        assertThat(r.get(2).unit()).isEqualTo(SpecUnit.V);
        assertThat(r.get(3).unit()).isEqualTo(SpecUnit.HZ);
        assertThat(r.get(3).name()).contains("питание");    // имя — от начала пункта, если перед числом пусто
    }

    /** Заголовок «Основные технические характеристики:» перед пунктом — не часть признака. */
    @Test
    void headerPrefixDropped() {
        List<SpecRequirement> r = SpecRequirements.extract("Основные технические характеристики: работа от электрической сети");
        assertThat(r).singleElement().satisfies(x -> {
            assertThat(x.kind()).isEqualTo(SpecRequirement.Kind.FEATURE);
            assertThat(x.text()).isEqualTo("работа от электрической сети");
        });
    }

    @Test
    void emptyAndTooShort() {
        assertThat(SpecRequirements.extract("")).isEmpty();
        assertThat(SpecRequirements.extract("медицинский")).isEmpty();
        assertThat(SpecRequirements.body(null)).isEmpty();
    }
}
```

- [ ] **Step 7: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.SpecRequirementsTest'`
Expected: FAIL — «cannot find symbol SpecRequirements».

- [ ] **Step 8: `SpecRequirement` и `SpecRequirements`**

`SpecRequirement.java`:

```java
package com.vladoose.nir.service.productwatch.fit;

import java.util.List;

/**
 * Требование ТЗ (спека §5): текст пункта (для экрана), имя параметра (нормализованное), вид, условие, границы в базовых
 * единицах, единица (null — счётное или признак), габариты по убыванию.
 */
public record SpecRequirement(String text, String name, Kind kind, SpecCmp cmp, double lo, double hi, SpecUnit unit,
                              List<Double> dims) {
    public enum Kind { NUMBER, COUNT, DIMS, FEATURE }
}
```

`SpecRequirements.java`:

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.service.productwatch.ProductText;
import com.vladoose.nir.util.TechSpecExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Требования из ТЗ лота (спека §5): русская часть, раздел характеристик, строки PDF склеены, пункты — через «;»,
 * с новой строки, «-» / «•», конец предложения; «не требования» (назначение, поставка, гарантия, документы) — мимо.
 */
public final class SpecRequirements {

    /** Больше — не сверяем (ТЗ томографа на 30 тыс. знаков): берём первые. */
    public static final int MAX = 150;

    private static final Pattern ANCHOR = Pattern.compile("характеристики\\s+закупаемых\\s+товаров[^\\n:]*:?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SPLIT = Pattern.compile("[;\\n•]|(?<=[\\p{Ll}\\d)»])\\.\\s+(?=\\p{Lu})");
    private static final Pattern HEADER = Pattern.compile("^[^:]{0,60}(?:характеристик|параметр|требовани|комплектац|состав)[^:]{0,40}:\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Признаки «не требования» (спека §5.4) — пункт целиком мимо. */
    private static final String[] SKIP = {"предназнач", "применя", "область", "используется", "позволяет", "поможет",
            "избавит", "сэкономит", "наименование", "номер закупки", "номер лота", "количество:", "единица измерения",
            "мест поставки", "места поставки", "место поставки", "срок поставки", "гаранти", "регистрац", "сертификат",
            "декларац", "паспорт", "инструкц", "обучени", "монтаж", "ввод в эксплуатац", "поставщик", "доставк", "год выпуска"};
    /** Слова признака (спека §5.3): пункт без числа, но с ними или «параметр — значение». */
    private static final String[] FEATURE_WORDS = {"наличие", "возможност", "режим", "функци", "тип", "систем", "защит",
            "индикац", "регулиров", "работа от", "комплект", "конструкц", "материал", "цвет", "дисплей", "экран", "память"};

    private SpecRequirements() {}

    /** Текст для разбора: русская часть, после «характеристики закупаемых товаров» (если есть), строки склеены. */
    public static String body(String requiredSpec) {
        if (requiredSpec == null || requiredSpec.isBlank()) return "";
        String ru = TechSpecExtractor.russianSection(requiredSpec);
        String text = ru == null ? requiredSpec : ru;
        Matcher m = ANCHOR.matcher(text);
        return reflow(m.find() ? text.substring(m.end()) : text);
    }

    /** Строка без «;» / «:» / «.» в конце, а следующая — со строчной, цифры или единицы, — продолжение. */
    static String reflow(String text) {
        StringBuilder out = new StringBuilder();
        for (String raw : text.replace('\t', ' ').replace('\r', '\n').split("\n")) {
            String l = raw.replaceAll(" {2,}", " ").strip();
            if (l.isEmpty()) continue;
            boolean cont = out.length() > 0 && !endsClause(out) && startsContinuation(l);
            out.append(cont ? " " : (out.length() > 0 ? "\n" : "")).append(l);
        }
        return out.toString();
    }

    private static boolean endsClause(StringBuilder s) {
        char c = s.charAt(s.length() - 1);
        return c == ';' || c == ':' || c == '.';
    }

    private static boolean startsContinuation(String l) {
        char c = l.charAt(0);
        if (Character.isDigit(c) || c == '(' || c == ',') return true;
        if (!Character.isLetter(c) || !Character.isLowerCase(c)) return false;
        return true;
    }

    public static List<SpecRequirement> extract(String body) {
        List<SpecRequirement> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        for (String raw : SPLIT.split(body)) {
            String clause = raw.strip().replaceFirst("^[-–—•*·\\d.)\\s]{1,6}(?=\\p{L})", "").strip();
            clause = HEADER.matcher(clause).replaceFirst("");
            if (clause.length() < 3) continue;
            String norm = ProductText.normalize(clause);
            if (skip(norm)) continue;
            List<SpecNumbers.Hit> hits = SpecNumbers.find(norm);
            if (hits.isEmpty()) {
                if (feature(norm)) out.add(new SpecRequirement(cut(clause, 160), cut(norm, 120), SpecRequirement.Kind.FEATURE,
                        SpecCmp.APPROX, 0, 0, null, List.of()));
            } else {
                int prevEnd = 0;
                String lastName = name(norm.substring(0, hits.get(0).start()));
                for (SpecNumbers.Hit h : hits) {
                    String own = name(norm.substring(Math.min(prevEnd, h.start()), h.start()));
                    String nm = own.length() >= 3 ? own : (lastName.length() >= 3 ? lastName : name(norm));
                    lastName = nm;
                    String text = cut((nm + " " + clause.substring(Math.min(h.start(), clause.length()), Math.min(h.end(), clause.length()))).strip(), 160);
                    SpecRequirement.Kind kind = !h.dims().isEmpty() ? SpecRequirement.Kind.DIMS
                            : h.unit() == null ? SpecRequirement.Kind.COUNT : SpecRequirement.Kind.NUMBER;
                    out.add(new SpecRequirement(text, nm, kind, h.cmp(), h.lo(), h.hi(), h.unit(), h.dims()));
                    prevEnd = h.end();
                }
            }
            if (out.size() >= MAX) return out.subList(0, MAX);
        }
        return out;
    }

    private static boolean skip(String norm) {
        for (String s : SKIP) if (norm.contains(s)) return true;
        return false;
    }

    /** Признак: слово признака или «параметр — значение», и не меньше двух значимых слов. */
    private static boolean feature(String norm) {
        long significant = ProductText.words(norm).stream().filter(ProductText::significant).count();
        if (significant < 2) return false;
        for (String w : FEATURE_WORDS) if (norm.contains(w)) return true;
        return norm.matches(".{3,60}\\s[-–—:]\\s.+");
    }

    /** Имя параметра: хвост текста перед числом без связок и знаков, ≤ 80. */
    private static String name(String head) {
        String n = head.replaceAll("[:\\-–—,(]+\\s*$", "").replaceAll("^[,;.\\s]+", "").strip();
        n = n.replaceAll("(?:не менее|не более|не ниже|не выше|не меньше|не больше|примерно|около|от|до)\\s*$", "").strip();
        n = n.replaceAll("[:\\-–—,(]+\\s*$", "").strip();
        return cut(n.toLowerCase(Locale.ROOT), 80);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
```

- [ ] **Step 9: Прогнать оба теста — зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.*'`
Expected: PASS (10 tests). Красное по живым ТЗ — смотреть вывод `SpecRequirements.extract(body)` (временный `System.out` в тесте, убрать до коммита) и чинить разбор; ожидания тестов не ослаблять.

- [ ] **Step 10: Мутации**

По одной, откат копией: (1) в `SpecRequirements.skip` вернуть `false` → красный `aspirator_semicolonsAndBrokenLines` (назначение) и `irradiator_columnsAndCount` (места поставки); (2) в `reflow` всегда «\n» вместо склейки → красный `aspirator…` (уровень вакуума — единица на следующей строке); (3) в `SpecNumbers.cmpBefore` убрать ветку «до» → красный `minMaxRangeUpto`; (4) в `SpecNumbers.find` убрать шаг 5 (счётное) → красный `bareNumberIsApprox_trailingCountIsCount` и `irradiator…`; (5) единица «г» в `FORMS` → красный `yearIsNotGrams`. После серии — `./gradlew compileJava`.

- [ ] **Step 11: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/productwatch/fit/SpecUnit.java src/main/java/com/vladoose/nir/service/productwatch/fit/SpecCmp.java \
  src/main/java/com/vladoose/nir/service/productwatch/fit/SpecNumbers.java src/main/java/com/vladoose/nir/service/productwatch/fit/SpecRequirement.java \
  src/main/java/com/vladoose/nir/service/productwatch/fit/SpecRequirements.java src/test/java/com/vladoose/nir/service/productwatch/fit/
git commit -m "feat(product-fit): требования ТЗ — числа, единицы, пункты, «не требования»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

`ProductFixtures.spec` лежит в пакете `com.vladoose.nir.productwatch` и `public` — тест пакета `service.productwatch.fit` берёт его импортом.

---

### Task 3: Карточка нашего товара — факты, стемы, статус РУ

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/RegStatus.java`, `src/main/java/com/vladoose/nir/service/productwatch/fit/FitText.java`, `ProductFact.java`, `ProductCard.java`, `RegistrationInfo.java`, `ProductCards.java`, `RegNumbers.java`
- Test: `src/test/java/com/vladoose/nir/service/productwatch/fit/ProductCardsTest.java`, `RegNumbersTest.java`

**Interfaces:**
- Consumes (задача 2): `SpecNumbers.find`, `SpecRequirements.extract`, `SpecRequirement`, `SpecCmp`, `SpecUnit`.
- Produces:
  - `enum RegStatus { ACTIVE, EXPIRED, NOT_FOUND, NOT_SPECIFIED, UNKNOWN; String noRuText() }` (пакет `entity` — его же хранит `product_registration`).
  - `record ProductFact(String name, SpecCmp cmp, double lo, double hi, SpecUnit unit, List<Double> dims, String source, String text)`; источники — `ProductCards.SITE` «сайт», `REG` «РУ», `DESCRIPTION` «описание».
  - `record RegistrationInfo(String regNumber, RegStatus status, LocalDate expires, boolean unlimited, String purpose, String useArea, String techChars, List<String> components)`.
  - `record ProductCard(long productId, String name, String brand, String slug, String regNumber, RegStatus regStatus, LocalDate regExpires, boolean regUnlimited, List<ProductFact> facts, Set<String> stems)`; `boolean regActive()`.
  - `static ProductCard ProductCards.of(SiteProduct p, RegistrationInfo reg)` — `reg == null`: статус `NOT_SPECIFIED`, если у товара нет № РУ, иначе `UNKNOWN`.
  - `FitText.stem(String)`, `FitText.stems(String text) → Set<String>`, `FitText.overlap(Set<String> need, Set<String> have) → double` (доля `need`, найденная в `have`, с синонимами и правилом начала слова).
  - `RegNumbers.normalize(String) → String` (null — пусто).

- [ ] **Step 1: Тест (красный)**

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.SiteProductAttribute;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Карточка нашего товара: факты с сайта, из карточки РУ и описания; стемы для признаков (спека §4.3). */
class ProductCardsTest {

    static SiteProduct product(String name, String description, String reg, SiteProductAttribute... attrs) {
        return SiteProduct.builder().id(7L).market(Market.KZ).siteId(UUID.randomUUID()).name(name).brand("Аксион")
                .slug("omh").description(description).registrationCert(reg).attributes(new java.util.ArrayList<>(List.of(attrs)))
                .syncedAt(OffsetDateTime.now()).build();
    }

    /** ОМХ-5/80 «Аксион» — характеристики сайта как в админ-API (снимок 2026-10-20). */
    @Test
    void siteAttributes_numbersRangesAndCounts() {
        ProductCard c = ProductCards.of(product("Отсасыватель медицинский хирургический ОМХ-5/80–01 «Аксион»", null, "РК-МТ-5№022493",
                new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа"),
                new SiteProductAttribute("Количество ламп", "6")), null);
        ProductFact flow = c.facts().stream().filter(f -> f.unit() == SpecUnit.L_MIN).findFirst().orElseThrow();
        assertThat(flow.cmp()).isEqualTo(SpecCmp.MIN);
        assertThat(flow.lo()).isEqualTo(15.0);
        assertThat(flow.source()).isEqualTo(ProductCards.SITE);
        assertThat(flow.text()).isEqualTo("Производительность по воздуху: не менее 15 л/мин");
        ProductFact vac = c.facts().stream().filter(f -> f.unit() == SpecUnit.KPA).findFirst().orElseThrow();
        assertThat(vac.lo()).isEqualTo(5.0);
        assertThat(vac.hi()).isEqualTo(80.0);
        ProductFact lamps = c.facts().stream().filter(f -> f.unit() == null).findFirst().orElseThrow();
        assertThat(lamps.lo()).isEqualTo(6.0);
        assertThat(c.regStatus()).isEqualTo(RegStatus.UNKNOWN);   // № РУ есть, проверки ещё не было
    }

    /** Review Focus #3: единица — в названии характеристики («Мощность, Вт» → «30»), «не более» в названии — условие. */
    @Test
    void unitFromAttributeName() {
        ProductCard c = ProductCards.of(product("Бактерицидный облучатель ОБПЕ-450", null, null,
                new SiteProductAttribute("Мощность, Вт", "30"),
                new SiteProductAttribute("Потребляемая мощность, не более, ВА", "300"),
                new SiteProductAttribute("Средний срок службы, лет", "5")), null);
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.W);
            assertThat(f.lo()).isEqualTo(30.0);
            assertThat(f.cmp()).isEqualTo(SpecCmp.APPROX);
        });
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.VA);
            assertThat(f.cmp()).isEqualTo(SpecCmp.MAX);
            assertThat(f.hi()).isEqualTo(300.0);
        });
        assertThat(c.facts()).anySatisfy(f -> assertThat(f.unit()).isEqualTo(SpecUnit.YEAR));
        assertThat(c.regStatus()).isEqualTo(RegStatus.NOT_SPECIFIED);
    }

    @Test
    void registrationTechCharsAreFacts_andStemsCoverDescriptionAndPurpose() {
        RegistrationInfo reg = new RegistrationInfo("РК-МТ-7№010893", RegStatus.ACTIVE, null, true,
                "резания, монополярной и биполярной коагуляции биологических тканей", "Общая хирургия",
                "Масса составных частей аппарата (не более): Блок 4,5 кг; Габаритные размеры блока (не более): 295/295/100 мм",
                List.of("Педаль двухклавишная"));
        ProductCard c = ProductCards.of(product("ВЧ электрохирургический блок ЭХВЧ-80-03", "Аппарат с режимом биполярной коагуляции.",
                "РК-МТ-7№010893"), reg);
        assertThat(c.regActive()).isTrue();
        assertThat(c.facts()).anySatisfy(f -> {
            assertThat(f.unit()).isEqualTo(SpecUnit.KG);
            assertThat(f.source()).isEqualTo(ProductCards.REG);
        });
        assertThat(FitText.overlap(FitText.stems("биполярная коагуляция"), c.stems())).isEqualTo(1.0);
        assertThat(FitText.overlap(FitText.stems("педаль"), c.stems())).isEqualTo(1.0);
    }

    @Test
    void synonymsAndPrefixes() {
        assertThat(FitText.overlap(FitText.stems("уровень вакуума"), FitText.stems("устанавливаемое разрежение"))).isEqualTo(0.5);
        assertThat(FitText.overlap(FitText.stems("количество ламп"), FitText.stems("лампы, количество"))).isEqualTo(1.0);
        assertThat(FitText.overlap(FitText.stems("тип крепления"), FitText.stems("способ размещения"))).isEqualTo(1.0);
        assertThat(FitText.overlap(FitText.stems("габариты"), FitText.stems("размещение"))).isZero();   // «размер» ≠ «размещ»
        assertThat(FitText.stems("наличие таймера")).containsExactly("таймер");                    // «наличие» — служебное
    }

    @Test
    void expiredRegistration_notActive() {
        RegistrationInfo reg = new RegistrationInfo("РК-МТ-5№022493", RegStatus.EXPIRED, LocalDate.of(2026, 6, 28), false,
                null, null, null, List.of());
        ProductCard c = ProductCards.of(product("ОМХ-5/80", null, "РК-МТ-5№022493"), reg);
        assertThat(c.regActive()).isFalse();
        assertThat(c.regExpires()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(RegStatus.EXPIRED.noRuText()).isEqualTo("РУ истекло");
    }
}
```

`RegNumbersTest`:

```java
package com.vladoose.nir.service.productwatch.fit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegNumbersTest {
    @Test
    void normalize() {
        assertThat(RegNumbers.normalize("  РК-МТ-5№022493 ")).isEqualTo("РК-МТ-5№022493");
        assertThat(RegNumbers.normalize("РК МИ  (МТ)-0№023841")).isEqualTo("РК МИ (МТ)-0№023841");
        assertThat(RegNumbers.normalize("РК МИ (МТ)-0№023841")).isEqualTo("РК МИ (МТ)-0№023841");
        assertThat(RegNumbers.normalize("  ")).isNull();
        assertThat(RegNumbers.normalize(null)).isNull();
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.ProductCardsTest' --tests 'com.vladoose.nir.service.productwatch.fit.RegNumbersTest'`
Expected: FAIL — компиляция (`ProductCards`, `RegStatus`, `RegNumbers` нет).

- [ ] **Step 3: Реализация**

`entity/RegStatus.java`:

```java
package com.vladoose.nir.entity;

/** Статус РУ нашего товара (спека 2026-10-20-product-spec-fit §4.2, решение 8 плана). */
public enum RegStatus {
    ACTIVE, EXPIRED, NOT_FOUND, NOT_SPECIFIED, UNKNOWN;

    /** Хвост «подходит по ТЗ, но …» — для чипа и сообщения. */
    public String noRuText() {
        return switch (this) {
            case ACTIVE -> "";
            case EXPIRED -> "РУ истекло";
            case NOT_FOUND -> "РУ не найдено в реестре";
            case NOT_SPECIFIED -> "РУ не указано на сайте";
            case UNKNOWN -> "РУ не проверено";
        };
    }
}
```

`fit/RegNumbers.java`:

```java
package com.vladoose.nir.service.productwatch.fit;

import java.util.Locale;

/** № РУ сайта → вид для запроса в ndda.kz и ключа `product_registration`. */
public final class RegNumbers {
    private RegNumbers() {}

    public static String normalize(String s) {
        if (s == null) return null;
        String t = s.replace(' ', ' ').replaceAll("\\s+", " ").strip();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }
}
```

`fit/FitText.java`:

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.service.productwatch.ProductText;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Слова для сверки (спека §5.3): стем — первые 6 букв (5 у ProductText мало: «размер» и «размещение» совпали бы);
 * два стема совпадают, если один — начало другого и короче не меньше 4 букв («ламп» / «лампы»); синонимы параметров.
 */
public final class FitText {

    private static final List<List<String>> SYNONYMS = List.of(
            List.of("вакуум", "разреж"), List.of("произв", "поток", "расход"), List.of("крепле", "размещ", "монтаж"),
            List.of("емкост", "объем", "вмести"), List.of("масса", "вес"), List.of("габари", "размер"),
            List.of("количе", "число"), List.of("шум", "звук"));

    /** Служебные слова требований («наличие», «возможность», «должен») — не признак товара. */
    private static final List<String> STOP = List.of("наличи", "возмож", "функци", "должн", "необхо", "обязат", "требуе");

    private FitText() {}

    public static String stem(String w) {
        return w.length() <= 6 ? w : w.substring(0, 6);
    }

    public static Set<String> stems(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) return out;
        for (String w : ProductText.words(ProductText.normalize(text))) {
            if (!ProductText.significant(w) || ProductText.digits(w) > 0) continue;
            String st = stem(w);
            if (STOP.stream().anyMatch(st::startsWith)) continue;
            out.add(canon(st));
        }
        return out;
    }

    static String canon(String s) {
        for (List<String> g : SYNONYMS) for (String m : g) if (same(s, m)) return g.get(0);
        return s;
    }

    static boolean same(String a, String b) {
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b, longer = shorter == a ? b : a;
        return shorter.length() >= 4 && longer.startsWith(shorter);
    }

    /** Доля стемов need, найденных в have. need пуст — 0. */
    public static double overlap(Set<String> need, Set<String> have) {
        if (need.isEmpty()) return 0;
        int hit = 0;
        for (String n : need) {
            for (String h : have) if (same(n, h)) { hit++; break; }
        }
        return (double) hit / need.size();
    }
}
```

`ProductText.digits` — `static` пакетный; сделать его `public` (одно слово в `ProductText.java`).

`fit/ProductFact.java`, `fit/RegistrationInfo.java`, `fit/ProductCard.java`:

```java
package com.vladoose.nir.service.productwatch.fit;

import java.util.List;

/** Факт нашего товара: имя параметра (нормализованное), условие и границы в базовых единицах, откуда, исходный текст. */
public record ProductFact(String name, SpecCmp cmp, double lo, double hi, SpecUnit unit, List<Double> dims, String source,
                          String text) {}
```

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.RegStatus;

import java.time.LocalDate;
import java.util.List;

/** РУ товара из `product_registration` — без JPA (снимок правил). */
public record RegistrationInfo(String regNumber, RegStatus status, LocalDate expires, boolean unlimited, String purpose,
                               String useArea, String techChars, List<String> components) {}
```

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.RegStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Наш товар для сверки (спека §4): факты и стемы текста, статус РУ. */
public record ProductCard(long productId, String name, String brand, String slug, String regNumber, RegStatus regStatus,
                          LocalDate regExpires, boolean regUnlimited, List<ProductFact> facts, Set<String> stems) {
    public boolean regActive() { return regStatus == RegStatus.ACTIVE; }
}
```

`fit/ProductCards.java`:

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.SiteProductAttribute;
import com.vladoose.nir.service.productwatch.ProductText;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Карточка товара из товара сайта и его РУ (спека §4.3): факты — сайт, карточка РУ, описание; стемы — для признаков. */
public final class ProductCards {

    public static final String SITE = "сайт", REG = "РУ", DESCRIPTION = "описание";

    /** «Мощность, Вт», «Срок службы (лет)», «Потребляемая мощность, не более, ВА» — единица в конце названия. */
    private static final Pattern NAME_UNIT = Pattern.compile("[,(]\\s*([^,()]{1,14})\\)?\\s*$");
    private static final Pattern PLAIN = Pattern.compile("^(\\d+(?:[.,]\\d+)?)$");

    private ProductCards() {}

    public static ProductCard of(SiteProduct p, RegistrationInfo reg) {
        List<ProductFact> facts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        append(text, p.getName(), p.getShortDescription(), p.getDescription());
        for (SiteProductAttribute a : p.getAttributes() == null ? List.<SiteProductAttribute>of() : p.getAttributes()) {
            if (a == null || a.name() == null || a.value() == null) continue;
            append(text, a.name(), a.value());
            facts.addAll(attributeFacts(a));
        }
        if (reg != null) {
            append(text, reg.purpose(), reg.useArea(), reg.techChars());
            reg.components().forEach(c -> append(text, c));
            facts.addAll(textFacts(reg.techChars(), REG));
        }
        facts.addAll(textFacts(p.getDescription(), DESCRIPTION));
        RegStatus status = reg != null ? reg.status()
                : RegNumbers.normalize(p.getRegistrationCert()) == null ? RegStatus.NOT_SPECIFIED : RegStatus.UNKNOWN;
        return new ProductCard(p.getId(), p.getName(), p.getBrand(), p.getSlug(), RegNumbers.normalize(p.getRegistrationCert()),
                status, reg == null ? null : reg.expires(), reg != null && reg.unlimited(), List.copyOf(facts),
                Set.copyOf(FitText.stems(text.toString())));
    }

    static List<ProductFact> attributeFacts(SiteProductAttribute a) {
        String name = ProductText.normalize(a.name()).strip();
        String value = ProductText.normalize(a.value()).strip();
        String text = a.name().strip() + ": " + a.value().strip();
        List<ProductFact> out = new ArrayList<>();
        List<SpecNumbers.Hit> hits = SpecNumbers.find(value);
        SpecCmp nameCmp = name.contains("не более") || name.contains("не выше") ? SpecCmp.MAX
                : name.contains("не менее") || name.contains("не ниже") ? SpecCmp.MIN : null;
        if (!hits.isEmpty()) {
            for (SpecNumbers.Hit h : hits) out.add(fact(cleanName(name), h, nameCmp, SITE, text));
            return out;
        }
        Matcher plain = PLAIN.matcher(value);
        if (!plain.matches()) return out;
        double v = Double.parseDouble(plain.group(1).replace(',', '.'));
        SpecUnit unit = unitInName(name);
        double base = unit == null ? v : unit.toBase(v);
        SpecCmp cmp = nameCmp == null ? SpecCmp.APPROX : nameCmp;
        double lo = cmp == SpecCmp.MAX ? 0 : base, hi = cmp == SpecCmp.MIN ? Double.POSITIVE_INFINITY : base;
        out.add(new ProductFact(cleanName(name), cmp, lo, hi, unit, List.of(), SITE, text));
        return out;
    }

    /** Числа текста (карточка РУ, описание) — через разбор требований: те же имена и условия. */
    static List<ProductFact> textFacts(String raw, String source) {
        List<ProductFact> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (SpecRequirement r : SpecRequirements.extract(SpecRequirements.reflowText(raw))) {
            if (r.kind() == SpecRequirement.Kind.FEATURE) continue;
            out.add(new ProductFact(r.name(), r.cmp(), r.lo(), r.hi(), r.unit(), r.dims(), source, r.text()));
        }
        return out;
    }

    private static ProductFact fact(String name, SpecNumbers.Hit h, SpecCmp nameCmp, String source, String text) {
        SpecCmp cmp = h.cmp() == SpecCmp.APPROX && nameCmp != null ? nameCmp : h.cmp();
        double lo = h.lo(), hi = h.hi();
        if (cmp == SpecCmp.MAX && h.cmp() == SpecCmp.APPROX) lo = 0;
        if (cmp == SpecCmp.MIN && h.cmp() == SpecCmp.APPROX) hi = Double.POSITIVE_INFINITY;
        return new ProductFact(name, cmp, lo, hi, h.unit(), h.dims(), source, text);
    }

    static SpecUnit unitInName(String normalizedName) {
        Matcher m = NAME_UNIT.matcher(normalizedName);
        if (!m.find()) return null;
        SpecUnit.Hit u = SpecUnit.parseAt(m.group(1).strip(), 0);
        return u == null ? null : u.unit();
    }

    /** Имя без хвоста единицы и «не более»: «потребляемая мощность, не более, ва» → «потребляемая мощность». */
    private static String cleanName(String name) {
        String n = name.replaceAll("[,(]\\s*(не более|не менее|не выше|не ниже)\\s*[,)]?", ",");
        n = NAME_UNIT.matcher(n).replaceFirst("");
        return n.replaceAll("[,\\s]+$", "").strip();
    }

    private static void append(StringBuilder b, String... parts) {
        for (String s : parts) if (s != null && !s.isBlank()) b.append(' ').append(s);
    }
}
```

В `SpecRequirements` — открыть склейку строк для текста без раздела ТЗ: `public static String reflowText(String raw) { return reflow(raw); }`.

- [ ] **Step 4: Прогнать — зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.*'`
Expected: PASS (все тесты пакета, 17).

- [ ] **Step 5: Мутации**

(1) `FitText.stem` — 5 букв вместо 6 → красный `synonymsAndPrefixes` («размер» = «размещ»); (2) в `attributeFacts` не читать `unitInName` → красный `unitFromAttributeName`; (3) в `ProductCards.of` не добавлять `reg.components()` в текст → красный `registrationTechCharsAreFacts…` (педаль); (4) `nameCmp` не применять → красный `unitFromAttributeName` (ВА — MAX). Потом `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/entity/RegStatus.java src/main/java/com/vladoose/nir/service/productwatch/fit/ \
  src/main/java/com/vladoose/nir/service/productwatch/ProductText.java src/test/java/com/vladoose/nir/service/productwatch/fit/
git commit -m "feat(product-fit): карточка нашего товара — факты сайта, РУ и описания, стемы, статус РУ

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Сверка и вердикт

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/FitMark.java`, `FitVerdict.java`, `FitRow.java`, `FitCandidate.java` (лежат в JSONB отметки — пакет `entity`, как `SimilarProductRef`); `src/main/java/com/vladoose/nir/service/productwatch/fit/LotFit.java`, `SpecFit.java`
- Test: `src/test/java/com/vladoose/nir/service/productwatch/fit/SpecFitTest.java`

**Interfaces:**
- Consumes: `SpecRequirement`, `ProductCard`, `ProductFact`, `FitText`, `RegStatus`.
- Produces:
  - (пакет `entity`) `enum FitMark { OK, FAIL, DIFF, UNKNOWN }`; `enum FitVerdict { FITS, FITS_NO_RU, PARTIAL, NOT_FIT, NO_REQUIREMENTS; int rank() }`.
  - `record FitRow(FitMark mark, String text, String ours, String source)`.
  - `record FitCandidate(long productId, String name, String slug, FitVerdict verdict, int met, int failed, int total, int unknown, String reason, String reasonShort, String regStatus, String regNumber, List<FitRow> rows)` — `@JsonIgnoreProperties(ignoreUnknown = true)` (лежит в JSONB).
  - `record LotFit(FitVerdict verdict, List<FitCandidate> candidates, String foreignBrand)`; `FitCandidate best()` — первый или null.
  - `static FitCandidate SpecFit.compare(List<SpecRequirement> reqs, ProductCard card)`.
  - `static LotFit SpecFit.evaluate(List<SpecRequirement> reqs, List<ProductCard> cards, String foreignBrand)`.
  - Константы: `FITS_SHARE = 0.5`, `FITS_MIN_MET = 3`, `APPROX_TOLERANCE = 0.15`, `UPTO_TOLERANCE = 0.05`, `FEATURE_SHARE = 0.6`, `MIN_REQUIREMENTS = 2`, `MAX_ROWS = 80`.

- [ ] **Step 1: Тест (красный)**

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.FitMark;
import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.SiteProductAttribute;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Сверка «требования ТЗ ↔ наш товар» и вердикт (спека §6). Ожидания — руками по тексту ТЗ и характеристикам. */
class SpecFitTest {

    static ProductCard card(long id, String name, RegStatus reg, SiteProductAttribute... attrs) {
        SiteProduct p = SiteProduct.builder().id(id).market(Market.KZ).siteId(UUID.randomUUID()).name(name).slug("p" + id)
                .registrationCert("РК-1").attributes(new ArrayList<>(List.of(attrs))).syncedAt(OffsetDateTime.now()).build();
        return ProductCards.of(p, new RegistrationInfo("РК-1", reg, null, true, null, null, null, List.of()));
    }

    static List<SpecRequirement> reqs(String body) { return SpecRequirements.extract(body); }

    static final String ASPIRATOR = "работа от электрической сети; вакуумная система отсасывания; "
            + "производительность отсасывания: примерно 15–30 л/мин; уровень вакуума: до 0–75 кПа; "
            + "наличие ёмкости для сбора отсасываемой жидкости; система защиты от переполнения";

    static ProductCard omh(RegStatus reg) {
        return card(1, "Отсасыватель медицинский хирургический ОМХ-5/80 от электрической сети, вакуумная система, ёмкость для сбора жидкости",
                reg,
                new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа"));
    }

    @Test
    void aspirator_fits_withActiveRu() {
        FitCandidate c = SpecFit.compare(reqs(ASPIRATOR), omh(RegStatus.ACTIVE));
        assertThat(c.failed()).isZero();
        assertThat(c.met()).isGreaterThanOrEqualTo(3);
        assertThat(c.verdict()).isEqualTo(FitVerdict.FITS);
        assertThat(c.rows()).anySatisfy(r -> {
            assertThat(r.mark()).isEqualTo(FitMark.OK);
            assertThat(r.text()).contains("15–30");
            assertThat(r.ours()).isEqualTo("Производительность по воздуху: не менее 15 л/мин");
            assertThat(r.source()).isEqualTo(ProductCards.SITE);
        });
        assertThat(c.rows()).anySatisfy(r -> {                  // «защита от переполнения» — о ней нигде не сказано
            assertThat(r.text()).contains("переполнения");
            assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN);
        });
    }

    /** РУ истекло — «подходит по ТЗ, но РУ истекло» (ОМХ-5/80: РК-МТ-5№022493 истекло 28.06.2026). */
    @Test
    void expiredRu_fitsNoRu() {
        assertThat(SpecFit.compare(reqs(ASPIRATOR), omh(RegStatus.EXPIRED)).verdict()).isEqualTo(FitVerdict.FITS_NO_RU);
    }

    /** Облучатель Абай: «количество ламп — 1» против шестилампового — провал по счётному; ✗ сверху строк. */
    @Test
    void irradiator_lampCountFails_failRowFirst() {
        ProductCard obpe = card(2, "Бактерицидный облучатель ОБПЕ-450 шестиламповый передвижной", RegStatus.ACTIVE,
                new SiteProductAttribute("Количество ламп", "6"), new SiteProductAttribute("Мощность, Вт", "30"),
                new SiteProductAttribute("Сеть питания", "220 В 50 Гц"));
        FitCandidate c = SpecFit.compare(reqs("Количество ламп - 1\nМощность лампы - 20 Вт\nНапряжение - 220 В\nЧастота - 50 Гц"), obpe);
        assertThat(c.verdict()).isEqualTo(FitVerdict.NOT_FIT);
        assertThat(c.rows().get(0).mark()).isEqualTo(FitMark.FAIL);
        assertThat(c.reasonShort()).isEqualTo("количество ламп");
        assertThat(c.reason()).contains("у нас").contains("6");
        assertThat(c.rows()).anySatisfy(r -> {                  // 20 Вт против 30 Вт без условия — «отличается», не провал
            assertThat(r.text()).contains("20");
            assertThat(r.mark()).isEqualTo(FitMark.DIFF);
        });
    }

    /** «до X»: масса — «не более», вакуум — «дотянуться до X». */
    @Test
    void uptoBySense() {
        ProductCard heavy = card(3, "Аппарат", RegStatus.ACTIVE, new SiteProductAttribute("Масса", "12 кг"),
                new SiteProductAttribute("Разрежение", "до -60 кПа"));
        FitCandidate c = SpecFit.compare(reqs("масса до 10 кг; разрежение до 75 кПа; работа от сети"), heavy);
        assertThat(c.rows()).filteredOn(r -> r.text().contains("масса")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.FAIL));
        assertThat(c.rows()).filteredOn(r -> r.text().contains("разрежение")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.FAIL));
    }

    /** «не менее 20» при нашем «не менее 15» — не доказано (нет данных), а не провал. */
    @Test
    void minAgainstOurMin_unknown() {
        FitCandidate c = SpecFit.compare(reqs("производительность не менее 20 л/мин; работа от сети"), omh(RegStatus.ACTIVE));
        assertThat(c.rows()).filteredOn(r -> r.text().contains("20")).singleElement()
                .satisfies(r -> assertThat(r.mark()).isEqualTo(FitMark.UNKNOWN));
        assertThat(c.failed()).isZero();
    }

    @Test
    void fewRequirements_noRequirements_andPartial() {
        assertThat(SpecFit.evaluate(reqs("медицинский"), List.of(omh(RegStatus.ACTIVE)), null).verdict())
                .isEqualTo(FitVerdict.NO_REQUIREMENTS);
        // 3 требования, ✓ 2 — меньше трёх подтверждённых: «похоже» (FITS_MIN_MET)
        FitCandidate two = SpecFit.compare(reqs("работа от электрической сети; вакуумная система отсасывания; цветной дисплей"),
                omh(RegStatus.ACTIVE));
        assertThat(two.met()).isEqualTo(2);
        assertThat(two.verdict()).isEqualTo(FitVerdict.PARTIAL);
        // 7 требований, ✓ 3 — меньше половины: «похоже» (FITS_SHARE)
        FitCandidate share = SpecFit.compare(reqs("работа от электрической сети; вакуумная система отсасывания; "
                + "производительность отсасывания: примерно 15–30 л/мин; цветной дисплей; режим мониторирования; "
                + "индикация разряда аккумулятора; наличие встроенного термопринтера"), omh(RegStatus.ACTIVE));
        assertThat(share.total()).isEqualTo(7);
        assertThat(share.met()).isEqualTo(3);
        assertThat(share.verdict()).isEqualTo(FitVerdict.PARTIAL);
    }

    /** Лучший — по вердикту; варианты — до 2 со ✓ > 0; «ТЗ под X» — в итоге лота. */
    @Test
    void evaluate_bestFirstAndAlternatives() {
        ProductCard empty = card(9, "Кабель", RegStatus.ACTIVE);
        ProductCard expired = omh(RegStatus.EXPIRED);
        ProductCard active = card(5, "Отсасыватель ОМП-5/80 от электрической сети, вакуумная система, ёмкость для сбора жидкости",
                RegStatus.ACTIVE, new SiteProductAttribute("Производительность", "не менее 20 л/мин"),
                new SiteProductAttribute("Разрежение", "от 5 до 80 кПа"));
        LotFit fit = SpecFit.evaluate(reqs(ASPIRATOR), List.of(empty, expired, active), "BOWA");
        assertThat(fit.verdict()).isEqualTo(FitVerdict.FITS);
        assertThat(fit.best().productId()).isEqualTo(5);
        assertThat(fit.candidates()).extracting(FitCandidate::productId).containsExactly(5L, 1L);   // «Кабель» без ✓ — не вариант
        assertThat(fit.foreignBrand()).isEqualTo("BOWA");
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.SpecFitTest'`
Expected: FAIL — компиляция (`SpecFit` нет).

- [ ] **Step 3: Реализация**

```java
package com.vladoose.nir.entity;

/** Отметка требования: ✓ проходит, ✗ не проходит (только числа), ≈ отличается (число без условия), ? нет данных. */
public enum FitMark { OK, FAIL, DIFF, UNKNOWN }
```

```java
package com.vladoose.nir.entity;

/** Вердикт лота × товара (спека §6.3); rank — для выбора лучшего товара. */
public enum FitVerdict {
    FITS(5), FITS_NO_RU(4), PARTIAL(3), NOT_FIT(2), NO_REQUIREMENTS(1);

    private final int rank;
    FitVerdict(int rank) { this.rank = rank; }
    public int rank() { return rank; }
}
```

```java
package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Строка сверки для экрана: отметка, пункт ТЗ, наше значение (null — нет данных) и откуда оно. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FitRow(FitMark mark, String text, String ours, String source) {}
```

```java
package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Сверка лота с одним нашим товаром (лежит в JSONB отметки — `fit_candidates`). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FitCandidate(long productId, String name, String slug, FitVerdict verdict, int met, int failed, int total,
                           int unknown, String reason, String reasonShort, String regStatus, String regNumber, List<FitRow> rows) {}
```

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.FitVerdict;

import java.util.List;

/** Итог сверки лота: вердикт лучшего товара, лучший + до 2 вариантов, чужой бренд ТЗ. */
public record LotFit(FitVerdict verdict, List<FitCandidate> candidates, String foreignBrand) {
    public FitCandidate best() { return candidates.isEmpty() ? null : candidates.get(0); }
}
```

`SpecFit.java`:

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.FitMark;
import com.vladoose.nir.entity.FitRow;
import com.vladoose.nir.entity.FitVerdict;

import java.util.*;

/**
 * Сверка требований ТЗ с нашим товаром (спека §6; чистая функция). Пороги — калибровка задачи 14 плана
 * 2026-10-20-product-spec-fit (замеры — в javadoc рядом после калибровки).
 */
public final class SpecFit {

    public static final double FITS_SHARE = 0.5;
    public static final int FITS_MIN_MET = 3;
    public static final double APPROX_TOLERANCE = 0.15;
    public static final double UPTO_TOLERANCE = 0.05;
    public static final double FEATURE_SHARE = 0.6;
    public static final int MIN_REQUIREMENTS = 2;
    public static final int MAX_ROWS = 80;
    private static final int MAX_ALTERNATIVES = 2;
    /** «До X» у этих параметров — «не более X» (спека §5.2). */
    private static final List<String> SMALLER_BETTER = List.of("масса", "вес", "габари", "размер", "шум", "потреб");

    public static final Comparator<FitCandidate> RANKING = Comparator
            .comparingInt((FitCandidate c) -> -c.verdict().rank())
            .thenComparingInt(c -> -c.met())
            .thenComparingInt(FitCandidate::failed)
            .thenComparingInt(FitCandidate::unknown)
            .thenComparingInt(c -> c.name() == null ? 0 : c.name().length())
            .thenComparingLong(FitCandidate::productId);

    private SpecFit() {}

    public static LotFit evaluate(List<SpecRequirement> reqs, List<ProductCard> cards, String foreignBrand) {
        if (reqs.size() < MIN_REQUIREMENTS || cards.isEmpty()) return new LotFit(FitVerdict.NO_REQUIREMENTS, List.of(), foreignBrand);
        List<FitCandidate> all = cards.stream().map(c -> compare(reqs, c)).sorted(RANKING).toList();
        List<FitCandidate> out = new ArrayList<>();
        out.add(all.get(0));
        for (FitCandidate c : all.subList(1, all.size())) {
            if (out.size() > MAX_ALTERNATIVES) break;
            if (c.met() > 0) out.add(c);
        }
        return new LotFit(all.get(0).verdict(), List.copyOf(out), foreignBrand);
    }

    public static FitCandidate compare(List<SpecRequirement> reqs, ProductCard card) {
        List<FitRow> rows = new ArrayList<>();
        String reason = null, reasonShort = null;
        for (SpecRequirement r : reqs) {
            FitRow row;
            if (r.kind() == SpecRequirement.Kind.FEATURE) {
                boolean ok = FitText.overlap(FitText.stems(r.name()), card.stems()) >= FEATURE_SHARE;
                row = new FitRow(ok ? FitMark.OK : FitMark.UNKNOWN, r.text(), ok ? "есть в описании товара" : null, ok ? ProductCards.SITE : null);
            } else {
                ProductFact f = factFor(r, card.facts());
                FitMark m = f == null ? FitMark.UNKNOWN : check(r, f);
                row = new FitRow(m, r.text(), f == null ? null : cut(f.text(), 120), f == null ? null : f.source());
                if (m == FitMark.FAIL && reason == null) {
                    reason = cut(r.text() + " — у нас: " + f.text(), 300);
                    reasonShort = cut(r.name(), 40);
                }
            }
            rows.add(row);
        }
        int total = rows.size();
        int ok = (int) rows.stream().filter(x -> x.mark() == FitMark.OK).count();
        int fail = (int) rows.stream().filter(x -> x.mark() == FitMark.FAIL).count();
        int unknown = (int) rows.stream().filter(x -> x.mark() == FitMark.UNKNOWN).count();
        FitVerdict v;
        if (total < MIN_REQUIREMENTS) v = FitVerdict.NO_REQUIREMENTS;
        else if (fail > 0) v = FitVerdict.NOT_FIT;
        else if (ok >= FITS_MIN_MET && ok >= FITS_SHARE * total) v = card.regActive() ? FitVerdict.FITS : FitVerdict.FITS_NO_RU;
        else v = FitVerdict.PARTIAL;
        List<FitRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(x -> order(x.mark())));
        return new FitCandidate(card.productId(), card.name(), card.slug(), v, ok, fail, total, unknown, reason, reasonShort,
                card.regStatus().name(), card.regNumber(), List.copyOf(sorted.subList(0, Math.min(MAX_ROWS, sorted.size()))));
    }

    private static int order(FitMark m) {
        return switch (m) { case FAIL -> 0; case DIFF -> 1; case OK -> 2; case UNKNOWN -> 3; };
    }

    /** Факт для требования: та же единица (счётные — со счётными), наибольшее совпадение имени; единственный факт семейства — и без него. */
    static ProductFact factFor(SpecRequirement r, List<ProductFact> facts) {
        List<ProductFact> same = facts.stream().filter(f -> compatible(r, f)).toList();
        if (same.isEmpty()) return null;
        Set<String> rn = FitText.stems(r.name());
        ProductFact best = null;
        double bestScore = 0;
        for (ProductFact f : same) {
            Set<String> fn = FitText.stems(f.name());
            double s = FitText.overlap(rn, fn) + 0.5 * FitText.overlap(fn, rn);
            if (s > bestScore) { bestScore = s; best = f; }
        }
        if (best != null) return best;
        return same.size() == 1 && !countLike(r.unit()) ? same.get(0) : null;
    }

    private static boolean compatible(SpecRequirement r, ProductFact f) {
        if (r.kind() == SpecRequirement.Kind.DIMS) return !f.dims().isEmpty();
        if (countLike(r.unit())) return countLike(f.unit()) && (r.unit() != SpecUnit.CHANNEL || f.unit() != SpecUnit.PCS);
        return f.unit() != null && f.dims().isEmpty() && f.unit().family().equals(r.unit().family());
    }

    private static boolean countLike(SpecUnit u) {
        return u == null || u == SpecUnit.CHANNEL || u == SpecUnit.PCS;
    }

    static FitMark check(SpecRequirement r, ProductFact f) {
        if (r.kind() == SpecRequirement.Kind.DIMS) return dims(r, f);
        boolean count = countLike(r.unit());
        boolean smaller = SMALLER_BETTER.stream().anyMatch(w -> r.name().contains(w));
        SpecCmp rc = r.cmp() == SpecCmp.UPTO && smaller ? SpecCmp.MAX : r.cmp();
        SpecCmp fc = f.cmp() == SpecCmp.UPTO ? SpecCmp.RANGE : f.cmp();
        double fLo = f.cmp() == SpecCmp.UPTO ? 0 : f.lo(), fHi = f.hi();
        switch (rc) {
            case MIN: case UPTO: {
                double x = rc == SpecCmp.MIN ? r.lo() : r.hi() * (1 - UPTO_TOLERANCE);
                return switch (fc) {
                    case MIN -> fLo >= x ? FitMark.OK : FitMark.UNKNOWN;
                    case MAX -> fHi < x ? FitMark.FAIL : FitMark.UNKNOWN;
                    default -> fHi >= x ? FitMark.OK : FitMark.FAIL;
                };
            }
            case MAX: {
                double x = r.hi();
                return switch (fc) {
                    case MAX -> fHi <= x ? FitMark.OK : FitMark.UNKNOWN;
                    case MIN -> fLo > x ? FitMark.FAIL : FitMark.UNKNOWN;
                    default -> fLo <= x ? FitMark.OK : FitMark.FAIL;
                };
            }
            case RANGE: {
                double a = r.lo(), b = r.hi();
                return switch (fc) {
                    case MIN -> fLo <= b ? FitMark.OK : FitMark.FAIL;
                    case MAX -> fHi >= a ? FitMark.OK : FitMark.FAIL;
                    default -> fLo <= b * (1 + 1e-9) && fHi >= a * (1 - 1e-9) ? FitMark.OK : FitMark.FAIL;
                };
            }
            default: {   // APPROX: число без условия
                double x = r.lo();
                if (count) return fc == SpecCmp.APPROX ? (Math.abs(fLo - x) < 1e-9 ? FitMark.OK : FitMark.FAIL) : FitMark.UNKNOWN;
                return switch (fc) {
                    case APPROX -> Math.abs(fLo - x) <= APPROX_TOLERANCE * Math.abs(x) ? FitMark.OK : FitMark.DIFF;
                    case RANGE -> x >= fLo && x <= fHi ? FitMark.OK : FitMark.DIFF;
                    case MIN -> Math.abs(fLo - x) <= APPROX_TOLERANCE * Math.abs(x) ? FitMark.OK : FitMark.UNKNOWN;
                    case MAX -> Math.abs(fHi - x) <= APPROX_TOLERANCE * Math.abs(x) ? FitMark.OK : FitMark.UNKNOWN;
                    default -> FitMark.UNKNOWN;
                };
            }
        }
    }

    /** Габариты — по осям по убыванию; оси сверх меньшего из двух списков не сравниваются. */
    private static FitMark dims(SpecRequirement r, ProductFact f) {
        int n = Math.min(r.dims().size(), f.dims().size());
        for (int i = 0; i < n; i++) {
            double want = r.dims().get(i), have = f.dims().get(i);
            boolean ok = switch (r.cmp()) {
                case MIN -> have >= want;
                case APPROX, RANGE -> Math.abs(have - want) <= APPROX_TOLERANCE * want;
                default -> have <= want;
            };
            if (!ok) return r.cmp() == SpecCmp.APPROX ? FitMark.DIFF : FitMark.FAIL;
        }
        return FitMark.OK;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
```

- [ ] **Step 4: Прогнать — зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.*'`
Expected: PASS (все тесты пакета, 24).

- [ ] **Step 5: Мутации**

(1) `card.regActive() ? FITS : FITS_NO_RU` → всегда `FITS` → красный `expiredRu_fitsNoRu`; (2) в `check` у счётных `FAIL` → `DIFF` → красный `irradiator…` (вердикт); (3) убрать `smaller` (масса «до» как вакуум) → красный `uptoBySense`; (4) у `MIN` против нашего `MIN` вернуть `FAIL` вместо `UNKNOWN` → красный `minAgainstOurMin_unknown`; (5) `FITS_MIN_MET = 1` → красный `fewRequirements_…` (случай «✓ 2 из 3»); (6) `FITS_SHARE = 0.25` → красный `fewRequirements_…` (случай «✓ 3 из 7»); (7) в `evaluate` не отбрасывать варианты с `met == 0` → красный `evaluate_bestFirstAndAlternatives`; (8) убрать `STOP` в `FitText` → красный `ProductCardsTest.synonymsAndPrefixes` («наличие таймера»). Потом `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/entity/Fit*.java src/main/java/com/vladoose/nir/service/productwatch/fit/ src/test/java/com/vladoose/nir/service/productwatch/fit/SpecFitTest.java
git commit -m "feat(product-fit): сверка требований ТЗ с нашим товаром и вердикт

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Чужой бренд в ТЗ

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/productwatch/fit/ForeignBrand.java`
- Test: `src/test/java/com/vladoose/nir/service/productwatch/fit/ForeignBrandTest.java`

**Interfaces:**
- Produces:
  - `static String ForeignBrand.detect(String text, Set<String> ours, Set<String> known)` — чужой бренд (как написан в ТЗ) или страна («Германия»), иначе `null`.
  - `static Set<String> ForeignBrand.ours(Collection<String> productNames, Collection<String> siteBrands)` — наши бренды и серии (нижний регистр).
  - `static Set<String> ForeignBrand.registryBrands(Collection<String> producers)` — бренды из производителей реестра (нижний регистр).

- [ ] **Step 1: Тест (красный)**

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.productwatch.ProductFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** «ТЗ под чужой бренд» (спека §5.5) — на настоящих ТЗ набора и литералах. */
class ForeignBrandTest {

    static final Set<String> OURS = ForeignBrand.ours(List.of("Генератор ONYX", "Биполярные пинцеты, серия \"CLEANTips\"",
            "Кабель нейтрального электрода с разъемом типа Valleylab. Длина кабеля 5 м"), List.of("Фотек", "Аксион", "Завод ЭМА"));

    static String in(String file) {
        return ForeignBrand.detect(SpecRequirements.body(ProductFixtures.spec(file)), OURS, Set.of());
    }

    @Test
    void bowaByModelCode() {
        assertThat(in("17828560-1_88272401-ЗЦП1.pdf")).isEqualTo("BOWA");
    }

    @Test
    void zollRepeated() {
        assertThat(in("17723120-1_87797199-ОК2.pdf")).isEqualTo("ZOLL");
    }

    /** «Датчик SpO2 NELLCOR», «на пульсоксиметр Mindray» — совместимость, не ТЗ под бренд. */
    @Test
    void compatibilityIsNotForeign() {
        assertThat(in("10461874-1_26702147-ОЛ-ОК2.pdf")).isNull();
        assertThat(in("10066644-1_26037428-ОЛ-ОИ2.pdf")).isNull();
    }

    /** ТЗ Астаны списано с АСД-«ФОТЕК» — чужого бренда нет. */
    @Test
    void copiedFromOurs_noForeign() {
        assertThat(in("17779323-1.pdf")).isNull();
    }

    /** Ручка-чехол для генераторов Covidien (режим Valleylab): ТЗ под чужое оборудование. */
    @Test
    void covidienAccessory() {
        assertThat(in("17714835-1_87662104-ОИ2.pdf")).isIn("Covidien", "Valleylab");
    }

    /** Review Focus #4: наш бренд и чужой в одном ТЗ — чужой «BOWA», наш — нет. */
    @Test
    void oursAndForeignTogether() {
        assertThat(ForeignBrand.detect("Аппарат BOWA ARC 303 или эквивалент, например ФОТЕК ЭХВЧ-350; генератор ONYX", OURS, Set.of()))
                .isEqualTo("BOWA");
        assertThat(ForeignBrand.detect("Генератор ONYX, серия CLEANTips; аппарат ONYX-A", OURS, Set.of())).isNull();
    }

    @Test
    void fieldsAndCountry() {
        assertThat(ForeignBrand.detect("Производитель: Mindray или эквивалент", OURS, Set.of())).isEqualTo("Mindray");
        assertThat(ForeignBrand.detect("Страна производитель: Германия", OURS, Set.of())).isEqualTo("Германия");
        assertThat(ForeignBrand.detect("Страна производитель: Россия", OURS, Set.of())).isNull();
        assertThat(ForeignBrand.detect("Производитель: указать", OURS, Set.of())).isNull();
    }

    /** Бренд из реестра — и одиночный, без кода модели; служебные слова латиницей — нет. */
    @Test
    void knownRegistryBrand_andStopWords() {
        Set<String> known = ForeignBrand.registryBrands(List.of("Shenzhen Mindray Bio-Medical Electronics Co., Ltd", "Philips Medical Systems"));
        assertThat(known).contains("mindray", "philips").doesNotContain("shenzhen", "medical", "electronics", "systems");
        assertThat(ForeignBrand.detect("монитор аналог Philips", OURS, known)).isEqualTo("Philips");
        assertThat(ForeignBrand.detect("Дисплей TFT, интерфейс USB, датчик SpO2, поставка DDP", OURS, known)).isNull();
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.ForeignBrandTest'`
Expected: FAIL — компиляция (`ForeignBrand` нет).

- [ ] **Step 3: Реализация**

```java
package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.service.productwatch.BrandModels;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «ТЗ под чужой бренд» (спека §5.5): поля «Производитель / Өндіруші / Модель / Страна …», слово латиницей с кодом модели
 * рядом или повторённое, бренд из производителей реестра НЦЭЛС — кроме наших брендов и совместимости («типа X»,
 * «разъём X», «датчик X», «на пульсоксиметр X»). Калибровка — набор ТЗ (§3 спеки): BOWA, ZOLL — да; NELLCOR, Mindray-датчик — нет.
 */
public final class ForeignBrand {

    private static final Pattern FIELD = Pattern.compile(
            "(?iu)(производитель|изготовитель|өндіруші|модель|марка|страна[\\s-]+(?:производитель|производства|происхождения|изготовления))"
                    + "\\s*[:\\-–—]\\s*([\\p{L}][\\p{L}\\p{N}\\-]{1,40})");
    private static final Pattern LATIN = Pattern.compile("(?<![\\p{L}\\p{N}])([A-Z][A-Za-z]{3,}|[A-Z]{3,})(?![\\p{L}\\p{N}])");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP = Set.of("spo", "tft", "lcd", "led", "usb", "ddp", "iso", "iec", "aha", "qrs", "ecg",
            "ekg", "lan", "wlan", "ethernet", "wifi", "bluetooth", "dicom", "pdf", "nibp", "ibp", "abs", "pvc", "dehp", "oem", "mdd",
            "din", "eec", "non", "stick", "gold", "standby", "shuko", "cut", "coag", "edge", "bte", "ips", "oled", "agcl", "retina",
            "air", "plus", "mini", "pro", "max", "lite", "type", "class", "mode", "auto", "dvd", "ram", "ssd", "hdd", "cpu", "gpu",
            "ups", "gsm", "gps", "rfid", "wifi", "hdmi", "vga", "fda", "gmp", "aed", "cpr", "ivl", "uzi", "uhf", "vhf", "ntc", "ptc",
            "aureus", "officinalis", "latex", "nitrile", "luer", "lock", "luerlock", "pdf", "json", "xml", "html", "word", "excel");
    private static final Set<String> GENERIC = Set.of("medical", "medic", "healthcare", "health", "systems", "system", "technologies",
            "technology", "tech", "electronics", "electronic", "international", "group", "company", "corporation", "industries",
            "industry", "instruments", "instrument", "products", "product", "solutions", "devices", "device", "scientific",
            "biomedical", "bio", "life", "sciences", "science", "care", "gmbh", "ltd", "llc", "inc", "corp", "limited", "holding",
            "holdings", "manufacturing", "trading", "equipment", "supplies", "china", "germany", "korea", "japan", "italy", "france",
            "india", "turkey", "usa", "shenzhen", "shanghai", "beijing", "jiangsu", "zhejiang", "guangdong", "hangzhou", "suzhou",
            "wuhan", "ningbo", "tianjin", "qingdao", "changzhou", "xiamen", "chongqing", "guangzhou", "foshan", "zhuhai", "nanjing",
            "medizintechnik", "medizin", "technik", "pharma", "diagnostics", "diagnostic", "laboratories", "laboratory");
    private static final Set<String> COMPAT = Set.of("типа", "тип", "разъем", "разъема", "разъемом", "разъём", "разъёма", "разъёмом",
            "совместим", "совместимы", "совместимый", "совместимые", "совместимость", "коннектор", "коннектором", "стандарта",
            "стандарт", "датчик", "датчиком", "датчики", "на", "к", "режим", "режима", "режимом");
    private static final Set<String> OUR_COUNTRIES = Set.of("россия", "рф", "российская", "казахстан", "рк", "республика");
    /** Наши бренды латиницей (транслитерации), сверх {@link BrandModels#GLOBAL_BRANDS}. */
    private static final List<String> OUR_LATIN = List.of("fotek", "axion", "aksion", "emaled", "mitar", "actiton", "onyx",
            "termoshov", "orion");

    private ForeignBrand() {}

    public static String detect(String text, Set<String> ours, Set<String> known) {
        if (text == null || text.isBlank()) return null;
        Matcher f = FIELD.matcher(text);
        while (f.find()) {
            String label = f.group(1).toLowerCase(Locale.ROOT), v = f.group(2);
            String key = v.toLowerCase(Locale.ROOT);
            if (label.startsWith("страна")) {
                if (!OUR_COUNTRIES.contains(key) && key.length() >= 4) return capitalize(v);
                continue;
            }
            if (ours.contains(key) || STOP.contains(key)) continue;
            if (latin(v) && v.length() >= 3) return v;
            if (known.contains(key)) return v;
        }
        List<String> words = new ArrayList<>();
        List<Integer> wordStarts = new ArrayList<>();
        Matcher w = WORD.matcher(text);
        while (w.find()) { words.add(w.group()); wordStarts.add(w.start()); }
        Map<String, List<Integer>> occ = new LinkedHashMap<>();   // токен (нижний регистр) → номера слов
        Map<String, String> asWritten = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            String tok = words.get(i);
            if (!LATIN.matcher(tok).matches()) continue;
            String key = tok.toLowerCase(Locale.ROOT);
            if (STOP.contains(key) || GENERIC.contains(key) || ours.contains(key)) continue;
            occ.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            asWritten.putIfAbsent(key, tok);
        }
        for (Map.Entry<String, List<Integer>> e : occ.entrySet()) {
            List<Integer> at = e.getValue();
            if (at.stream().allMatch(i -> compat(words, i))) continue;
            boolean strong = known.contains(e.getKey()) || at.size() >= 2 || at.stream().anyMatch(i -> modelCodeAfter(words, i));
            if (strong) return asWritten.get(e.getKey());
        }
        return null;
    }

    /** Совместимость: одно из трёх слов перед токеном — «типа», «разъём», «датчик», «на» … */
    private static boolean compat(List<String> words, int i) {
        for (int k = Math.max(0, i - 3); k < i; k++) if (COMPAT.contains(words.get(k).toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** Код модели: в двух следующих словах есть цифра («BOWA ARC 303»). */
    private static boolean modelCodeAfter(List<String> words, int i) {
        for (int k = i + 1; k <= Math.min(words.size() - 1, i + 2); k++) {
            if (words.get(k).chars().anyMatch(Character::isDigit)) return true;
        }
        return false;
    }

    public static Set<String> ours(Collection<String> productNames, Collection<String> siteBrands) {
        Set<String> out = new HashSet<>(OUR_LATIN);
        for (String b : BrandModels.GLOBAL_BRANDS) out.add(b.toLowerCase(Locale.ROOT));
        for (String b : siteBrands) {
            if (b == null) continue;
            for (String t : b.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (t.length() >= 3 && !t.equals("завод") && !t.equals("нпп")) out.add(t);
        }
        for (String name : productNames) {
            if (name == null) continue;
            List<String> words = new ArrayList<>();
            Matcher m = WORD.matcher(name);
            while (m.find()) words.add(m.group());
            for (int i = 0; i < words.size(); i++) {
                if (LATIN.matcher(words.get(i)).matches() && !compat(words, i)) out.add(words.get(i).toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    public static Set<String> registryBrands(Collection<String> producers) {
        Set<String> out = new HashSet<>();
        for (String p : producers) {
            if (p == null) continue;
            Matcher m = LATIN.matcher(p);
            while (m.find()) {
                String key = m.group(1).toLowerCase(Locale.ROOT);
                if (key.length() >= 4 && !GENERIC.contains(key) && !STOP.contains(key)) out.add(key);
            }
        }
        return Set.copyOf(out);
    }

    private static boolean latin(String s) {
        return s.chars().anyMatch(c -> c < 128 && Character.isLetter(c)) && s.chars().noneMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Прогнать — зелёный; калибровка по набору**

Run: `./gradlew test --tests 'com.vladoose.nir.service.productwatch.fit.ForeignBrandTest'`
Expected: PASS (7). Если настоящий ТЗ даёт другое (слово латиницей из шапки, стоп-слово не в списке) — дописать стоп-слово / слово совместимости по факту текста, ожидания тестов не менять. Прогнать `detect` по всем 37 ТЗ набора разовым циклом (временный тест или `jshell`) и проверить глазами: ложные «чужие» на «не наше» допустимы, на «наш вид своими словами» — нет.

- [ ] **Step 5: Мутации**

(1) `compat` всегда `false` → красный `compatibilityIsNotForeign`; (2) без `modelCodeAfter` → красный `bowaByModelCode`; (3) не учитывать `ours` → красный `oursAndForeignTogether`; (4) `OUR_COUNTRIES` пуст → красный `fieldsAndCountry` (Россия). Потом `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/productwatch/fit/ForeignBrand.java src/test/java/com/vladoose/nir/service/productwatch/fit/ForeignBrandTest.java
git commit -m "feat(product-fit): «ТЗ под чужой бренд» — поля, латиница с кодом модели, бренды реестра, совместимость

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: V39 и проверка РУ наших товаров на ndda.kz

**Files:**
- Create: `src/main/resources/db/migration/V39__product_spec_fit.sql`, `src/main/java/com/vladoose/nir/entity/ProductRegistration.java`, `entity/RegistrationComponent.java`, `repository/ProductRegistrationRepository.java`, `integration/ndda/NddaRegistration.java`, `service/productwatch/ProductRegistrationSync.java`, `service/productwatch/ProductRegistrationWriter.java`
- Modify: `integration/ndda/dto/NddaListItemDto.java`, `integration/ndda/NddaClient.java`, `integration/ndda/NddaHttpClient.java`, `entity/TenderLotProductMatch.java`, `service/productwatch/ProductWatchScheduler.java`
- Test: `src/test/java/com/vladoose/nir/integration/ndda/NddaRegistrationClientTest.java`, `src/test/java/com/vladoose/nir/productwatch/ProductRegistrationSyncTest.java`, `ProductWatchSchedulerTest.java` (конструктор + порядок)

**Interfaces:**
- Consumes: `RegStatus` (задача 3), `RegNumbers.normalize` (задача 3), `FitVerdict`, `FitCandidate` (задача 4).
- Produces:
  - `record NddaRegistration(long id, String regNumber, String name, String producer, String country, LocalDate regDate, LocalDate expirationDate, boolean unlimited, boolean active)`; `NddaRegistration NddaClient.findRegistration(String regNumber)` — действующие (`regPeriod=1`), иначе недействующие (`regPeriod=2`), иначе `null`.
  - Сущность `ProductRegistration` (ключ — `regNumber`), `record RegistrationComponent(String component, String producer, String country)`; `ProductRegistrationRepository extends JpaRepository<ProductRegistration, String>` + `@Query("select max(r.checkedAt) from ProductRegistration r") OffsetDateTime lastChecked()`.
  - `ProductRegistrationSync.Result sync()` (`record Result(int numbers, int active, int expired, int notFound, int errors)`, `String text()`); в конце — `ProductCatalog.refresh()`.
  - Поля `TenderLotProductMatch`: `fit` (`FitVerdict`), `fitProductId`, `fitMet`, `fitTotal`, `fitReason`, `foreignBrand`, `fitCandidates` (`List<FitCandidate>`), `fitCheckedAt`.
  - Конструктор `ProductWatchScheduler(ProductWatchService, ProductSpecChecker, ProductRematchService, SiteCatalogSync, ProductRegistrationSync, ImportOutageNotifier, ProductWatchNotifier, ProcurementPlanService, ProcurementPlanDigest, boolean, String, String, String, String, String)` (новый пятый параметр).

- [ ] **Step 1: Миграция**

`V39__product_spec_fit.sql`:

```sql
-- Сверка ТЗ с нашими товарами (спека docs/superpowers/specs/2026-10-20-product-spec-fit-design.md §10, план 2026-10-20-product-spec-fit).
-- РУ товаров сайта по данным ndda.kz (не рыночная: реестр общий; строка на № РУ сайта).
CREATE TABLE product_registration (
    reg_number      VARCHAR(100)  PRIMARY KEY,
    status          VARCHAR(15)   NOT NULL,
    ndda_id         BIGINT,
    name            TEXT,
    producer        VARCHAR(500),
    country         VARCHAR(200),
    reg_date        DATE,
    expiration_date DATE,
    unlimited       BOOLEAN,
    purpose         TEXT,
    use_area        TEXT,
    tech_chars      TEXT,
    risk_class      VARCHAR(200),
    complect        JSONB         NOT NULL DEFAULT '[]',
    checked_at      TIMESTAMPTZ   NOT NULL,
    error           VARCHAR(300)
);

-- Сверка отметки «наш товар»: вердикт лучшего товара, «N из M», первая причина, чужой бренд ТЗ, строки сверки (лучший + варианты).
ALTER TABLE tender_lot_product_match ADD COLUMN fit            VARCHAR(20);
ALTER TABLE tender_lot_product_match ADD COLUMN fit_product_id BIGINT REFERENCES site_product (id) ON DELETE SET NULL;
ALTER TABLE tender_lot_product_match ADD COLUMN fit_met        SMALLINT;
ALTER TABLE tender_lot_product_match ADD COLUMN fit_total      SMALLINT;
ALTER TABLE tender_lot_product_match ADD COLUMN fit_reason     VARCHAR(300);
ALTER TABLE tender_lot_product_match ADD COLUMN foreign_brand  VARCHAR(100);
ALTER TABLE tender_lot_product_match ADD COLUMN fit_candidates JSONB NOT NULL DEFAULT '[]';
ALTER TABLE tender_lot_product_match ADD COLUMN fit_checked_at TIMESTAMPTZ;
```

- [ ] **Step 2: Тест клиента ndda (красный)**

```java
package com.vladoose.nir.integration.ndda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** РУ по номеру: сначала действующие (regPeriod 1), потом недействующие (regPeriod 2) — живые формы ответа 2026-10-20. */
class NddaRegistrationClientTest {

    HttpServer server;
    final List<String> bodies = new ArrayList<>();
    volatile String active = "[]", inactive = "[]";
    NddaHttpClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            byte[] b = (body.contains("\"regPeriod\":1") ? active : inactive).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        client = new NddaHttpClient(new ObjectMapper(), "http://localhost:" + server.getAddress().getPort() + "/register-backend");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void active_firstList() {
        active = """
            [{"id":153163,"reg_number":"РК-МТ-7№010893","name":"Аппарат электрохирургический ЭХВЧ-80","producerNameRu":"ФОТЕК",
              "countryNameRu":"РОССИЯ","reg_date":"2019-11-25T00:00:00","expiration_date":null,"unlimited_sign":true}]""";
        NddaRegistration r = client.findRegistration("РК-МТ-7№010893");
        assertThat(r.active()).isTrue();
        assertThat(r.id()).isEqualTo(153163L);
        assertThat(r.unlimited()).isTrue();
        assertThat(r.producer()).isEqualTo("ФОТЕК");
        assertThat(bodies).hasSize(1);
    }

    @Test
    void expired_secondList() {
        inactive = """
            [{"id":172316,"reg_number":"РК-МТ-5№022493","name":"Отсасыватель медицинский хирургический ОМХ-5/80-01 «АКСИОН»",
              "producerNameRu":"Концерн Аксион","countryNameRu":"РОССИЯ","reg_date":"2021-06-28T00:00:00",
              "expiration_date":"2026-06-28T00:00:00","unlimited_sign":false,"reg_term":5}]""";
        NddaRegistration r = client.findRegistration("РК-МТ-5№022493");
        assertThat(r.active()).isFalse();
        assertThat(r.expirationDate()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1)).contains("\"regPeriod\":2");
    }

    @Test
    void notFound_null_andOtherNumberIgnored() {
        inactive = "[{\"id\":1,\"reg_number\":\"РК-МТ-5№000001\"}]";   // list вернул чужой номер — не наш
        assertThat(client.findRegistration("РК-МТ-5№022493")).isNull();
    }
}
```

- [ ] **Step 3: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.integration.ndda.NddaRegistrationClientTest'`
Expected: FAIL — компиляция (`findRegistration`, `NddaRegistration` нет).

- [ ] **Step 4: Клиент**

`NddaListItemDto` — добавить поля (Jackson, `@JsonIgnoreProperties(ignoreUnknown = true)` уже есть):

```java
    private String name;
    private String producerNameRu;
    private String countryNameRu;
    @JsonProperty("reg_date")
    private String regDate;
    @JsonProperty("expiration_date")
    private String expirationDate;
    @JsonProperty("unlimited_sign")
    private Boolean unlimitedSign;
```

`NddaRegistration.java`:

```java
package com.vladoose.nir.integration.ndda;

import java.time.LocalDate;

/** РУ по точному номеру (RegisterService/list): действующее или нет — по тому, в каком списке нашлось. */
public record NddaRegistration(long id, String regNumber, String name, String producer, String country, LocalDate regDate,
                               LocalDate expirationDate, boolean unlimited, boolean active) {}
```

`NddaClient` — добавить:

```java
    /** РУ по точному номеру: действующие (regPeriod 1), иначе недействующие (regPeriod 2); null — нет ни там, ни там. */
    NddaRegistration findRegistration(String regNumber);
```

`NddaHttpClient` — общий `list(regNumber, period)` (тело и разбор — как в `resolveId`), `resolveId` через него, и:

```java
    @Override
    public Long resolveId(String regNumber) {
        return list(regNumber, 1).stream().filter(i -> regNumber.equals(i.getRegNumber()))
                .map(NddaListItemDto::getId).findFirst().orElse(null);
    }

    @Override
    public NddaRegistration findRegistration(String regNumber) {
        for (int period : new int[]{1, 2}) {
            NddaListItemDto i = list(regNumber, period).stream().filter(x -> regNumber.equals(x.getRegNumber())).findFirst().orElse(null);
            if (i != null) {
                return new NddaRegistration(i.getId(), i.getRegNumber(), i.getName(), i.getProducerNameRu(), i.getCountryNameRu(),
                        date(i.getRegDate()), date(i.getExpirationDate()), Boolean.TRUE.equals(i.getUnlimitedSign()), period == 1);
            }
        }
        return null;
    }

    private List<NddaListItemDto> list(String regNumber, int period) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("regTypeId", 2);       // тип «МИ»
        filter.put("regPeriod", period);  // 1 — действующие, 2 — недействующие (живая форма 2026-10-20)
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
            return objectMapper.readValue(json, new TypeReference<List<NddaListItemDto>>() {});
        } catch (Exception e) {
            throw new UpstreamException("НЦЭЛС: неожиданный ответ list: " + e.getMessage(), e);
        }
    }

    /** «2026-06-28T00:00:00» → дата; пусто или мусор — null. */
    private static LocalDate date(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
```

Тело `objectMapper.writeValueAsString` пишет `"regPeriod":1` без пробела — на этом держится заглушка теста.

- [ ] **Step 5: Прогнать клиент — зелёный; прежний `NddaHttpClientTest` — зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.integration.ndda.*'`
Expected: PASS.

- [ ] **Step 6: Сущность, хранилище, поля отметки**

`entity/RegistrationComponent.java`:

```java
package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Компонент комплектности РУ (JSONB `product_registration.complect`). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationComponent(String component, String producer, String country) {}
```

`entity/ProductRegistration.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** РУ товара сайта по данным ndda.kz (V39, спека 2026-10-20-product-spec-fit §4.2). Не рыночная: реестр общий. */
@Entity
@Table(name = "product_registration")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductRegistration {

    @Id
    @Column(name = "reg_number", length = 100)
    private String regNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private RegStatus status;

    @Column(name = "ndda_id")
    private Long nddaId;

    @Column(columnDefinition = "TEXT")
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

    @Column(columnDefinition = "TEXT")
    private String purpose;

    @Column(name = "use_area", columnDefinition = "TEXT")
    private String useArea;

    @Column(name = "tech_chars", columnDefinition = "TEXT")
    private String techChars;

    @Column(name = "risk_class", length = 200)
    private String riskClass;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<RegistrationComponent> complect = new ArrayList<>();

    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;

    @Column(length = 300)
    private String error;
}
```

`repository/ProductRegistrationRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ProductRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;

public interface ProductRegistrationRepository extends JpaRepository<ProductRegistration, String> {
    @Query("select max(r.checkedAt) from ProductRegistration r")
    OffsetDateTime lastChecked();
}
```

`TenderLotProductMatch` — после `specCheckedAt` (импорты `FitVerdict`, `FitCandidate` — тот же пакет):

```java
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private FitVerdict fit;

    @Column(name = "fit_product_id")
    private Long fitProductId;

    @Column(name = "fit_met")
    private Integer fitMet;

    @Column(name = "fit_total")
    private Integer fitTotal;

    @Column(name = "fit_reason", length = 300)
    private String fitReason;

    @Column(name = "foreign_brand", length = 100)
    private String foreignBrand;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fit_candidates", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<FitCandidate> fitCandidates = new ArrayList<>();

    @Column(name = "fit_checked_at")
    private OffsetDateTime fitCheckedAt;
```

- [ ] **Step 7: Тест проверки РУ (красный)**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.NddaRegistration;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.ProductRegistrationRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.ProductRegistrationSync;
import com.vladoose.nir.service.productwatch.ProductRegistrationWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверка РУ товаров сайта (спека §4.2): действует / истекло / не найдено / сбой — прежние данные остаются. */
@SpringBootTest
@Transactional
class ProductRegistrationSyncTest {

    @Autowired SiteProductRepository products;
    @Autowired ProductRegistrationRepository registrations;
    @Autowired ProductRegistrationWriter writer;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;

    final Map<String, NddaRegistration> found = new HashMap<>();
    final Set<String> failing = new HashSet<>();
    final List<Long> sleeps = new ArrayList<>();

    final NddaClient ndda = new NddaClient() {
        @Override public Long resolveId(String regNumber) { throw new UnsupportedOperationException(); }
        @Override public NddaRegistration findRegistration(String regNumber) {
            if (failing.contains(regNumber)) throw new UpstreamException("НЦЭЛС недоступен: timeout", null);
            return found.get(regNumber);
        }
        @Override public NddaDetailDto fetchDetail(long id) {
            NddaDetailDto d = new NddaDetailDto();
            d.setPurpose("для резания и коагуляции");
            d.setShortTechnicalCharacteristicsRu("Масса блока 4,5 кг");
            return d;
        }
        @Override public List<NddaComplectItemDto> fetchComplectList(long nddaId) {
            NddaComplectItemDto c = new NddaComplectItemDto();
            c.setComponent("Педаль двухклавишная");
            return List.of(c);
        }
    };

    ProductRegistrationSync sync() {
        return new ProductRegistrationSync(products, ndda, writer, tm, () -> {}, ms -> sleeps.add(ms));
    }

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        registrations.deleteAllInBatch();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    void product(String reg) {
        products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("Товар " + reg).registrationCert(reg)
                .syncedAt(OffsetDateTime.now()).build());
    }

    @Test
    void activeExpiredNotFound_numbersNormalizedAndDistinct() {
        product("РК-МТ-7№010893");
        product(" РК-МТ-7№010893 ");                 // тот же № с пробелами — один запрос
        product("РК-МТ-5№022493");
        product("РК-МТ-5№000000");
        product(null);                               // без № — не запрашивается
        found.put("РК-МТ-7№010893", new NddaRegistration(1, "РК-МТ-7№010893", "ЭХВЧ-80", "ФОТЕК", "РОССИЯ", LocalDate.of(2019, 11, 25), null, true, true));
        found.put("РК-МТ-5№022493", new NddaRegistration(2, "РК-МТ-5№022493", "ОМХ-5/80", "Аксион", "РОССИЯ", LocalDate.of(2021, 6, 28),
                LocalDate.of(2026, 6, 28), false, false));
        ProductRegistrationSync.Result r = sync().sync();
        assertThat(r.numbers()).isEqualTo(3);
        assertThat(r.active()).isEqualTo(1);
        assertThat(r.expired()).isEqualTo(1);
        assertThat(r.notFound()).isEqualTo(1);
        assertThat(sleeps).containsOnly(ProductRegistrationSync.PAUSE_MS).hasSize(3);
        ProductRegistration a = registrations.findById("РК-МТ-7№010893").orElseThrow();
        assertThat(a.getStatus()).isEqualTo(RegStatus.ACTIVE);
        assertThat(a.getTechChars()).isEqualTo("Масса блока 4,5 кг");
        assertThat(a.getComplect()).extracting(RegistrationComponent::component).containsExactly("Педаль двухклавишная");
        assertThat(registrations.findById("РК-МТ-5№022493").orElseThrow().getExpirationDate()).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(registrations.findById("РК-МТ-5№000000").orElseThrow().getStatus()).isEqualTo(RegStatus.NOT_FOUND);
    }

    /** Сбой номера — прежний статус и данные остаются, в итоге «ошибок 1»; номера без прежних данных — UNKNOWN. */
    @Test
    void failureKeepsPrevious() {
        product("РК-МТ-7№010893");
        product("РК-МТ-5№111111");
        found.put("РК-МТ-7№010893", new NddaRegistration(1, "РК-МТ-7№010893", "ЭХВЧ-80", "ФОТЕК", "РОССИЯ", null, null, true, true));
        sync().sync();
        product("РК-МТ-5№222222");                   // новый номер — первая же проверка со сбоем
        failing.add("РК-МТ-7№010893");
        failing.add("РК-МТ-5№111111");
        failing.add("РК-МТ-5№222222");
        ProductRegistrationSync.Result r = sync().sync();
        assertThat(r.errors()).isEqualTo(3);
        ProductRegistration kept = registrations.findById("РК-МТ-7№010893").orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(RegStatus.ACTIVE);
        assertThat(kept.getError()).contains("НЦЭЛС недоступен");
        assertThat(registrations.findById("РК-МТ-5№111111").orElseThrow().getStatus())
                .isEqualTo(RegStatus.NOT_FOUND);       // первый проход: не найдено; сбой его не стёр
        assertThat(registrations.findById("РК-МТ-5№222222").orElseThrow().getStatus())
                .isEqualTo(RegStatus.UNKNOWN);         // прежних данных нет — «не проверено»
        assertThat(r.text()).contains("ошибок 3");
    }
}
```

- [ ] **Step 8: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.ProductRegistrationSyncTest'`
Expected: FAIL — компиляция (`ProductRegistrationSync`, `ProductRegistrationWriter` нет).

- [ ] **Step 9: Проверка РУ**

`ProductRegistrationWriter.java`:

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductRegistration;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.RegistrationComponent;
import com.vladoose.nir.integration.ndda.NddaRegistration;
import com.vladoose.nir.integration.ndda.dto.NddaComplectItemDto;
import com.vladoose.nir.integration.ndda.dto.NddaDetailDto;
import com.vladoose.nir.repository.ProductRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Запись проверки РУ — своей транзакцией на номер (сеть — снаружи, в {@link ProductRegistrationSync}). */
@Component
public class ProductRegistrationWriter {

    private final ProductRegistrationRepository repo;

    public ProductRegistrationWriter(ProductRegistrationRepository repo) { this.repo = repo; }

    @Transactional
    public void save(String number, NddaRegistration r, NddaDetailDto d, List<NddaComplectItemDto> complect) {
        ProductRegistration p = repo.findById(number).orElseGet(() -> ProductRegistration.builder().regNumber(number).build());
        p.setStatus(r.active() ? RegStatus.ACTIVE : RegStatus.EXPIRED);
        p.setNddaId(r.id());
        p.setName(r.name());
        p.setProducer(trunc(r.producer(), 500));
        p.setCountry(trunc(r.country(), 200));
        p.setRegDate(r.regDate());
        p.setExpirationDate(r.expirationDate());
        p.setUnlimited(r.unlimited());
        p.setPurpose(d == null ? null : d.getPurpose());
        p.setUseArea(d == null ? null : d.getUseArea());
        p.setTechChars(d == null ? null : d.getShortTechnicalCharacteristicsRu());
        p.setRiskClass(d == null ? null : trunc(d.getDegreeRiskName(), 200));
        p.setComplect(complect == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(complect.stream()
                .map(c -> new RegistrationComponent(c.getComponent() != null ? c.getComponent() : c.getProductName(), c.getProducerName(),
                        c.getCountryName())).toList()));
        p.setCheckedAt(OffsetDateTime.now());
        p.setError(null);
        repo.save(p);
    }

    @Transactional
    public void notFound(String number) {
        ProductRegistration p = repo.findById(number).orElseGet(() -> ProductRegistration.builder().regNumber(number).build());
        p.setStatus(RegStatus.NOT_FOUND);
        p.setCheckedAt(OffsetDateTime.now());
        p.setError(null);
        repo.save(p);
    }

    /** Сбой номера: прежние данные остаются; строки не было — UNKNOWN. */
    @Transactional
    public void error(String number, String error) {
        ProductRegistration p = repo.findById(number).orElseGet(() -> ProductRegistration.builder().regNumber(number)
                .status(RegStatus.UNKNOWN).build());
        p.setError(trunc(error, 300));
        p.setCheckedAt(OffsetDateTime.now());
        repo.save(p);
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
```

`ProductRegistrationSync.java`:

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.integration.ndda.NddaClient;
import com.vladoose.nir.integration.ndda.NddaRegistration;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * РУ товаров сайта на ndda.kz (спека 2026-10-20-product-spec-fit §4.2): по каждому разному №, пауза 300 мс; сеть — вне
 * транзакции, запись — {@link ProductRegistrationWriter}; в конце — снимок правил заново ({@link ProductCatalog#refresh()}).
 */
@Service
public class ProductRegistrationSync {

    private static final Logger log = LoggerFactory.getLogger(ProductRegistrationSync.class);
    public static final long PAUSE_MS = 300;

    public record Result(int numbers, int active, int expired, int notFound, int errors) {
        public String text() {
            return "РУ: проверено " + numbers + " (действует " + active + ", истекло " + expired + ", не найдено " + notFound
                    + (errors > 0 ? ", ошибок " + errors : "") + ")";
        }
    }

    private final SiteProductRepository products;
    private final NddaClient ndda;
    private final ProductRegistrationWriter writer;
    private final TransactionTemplate readTx;
    private final Runnable refreshCatalog;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public ProductRegistrationSync(SiteProductRepository products, NddaClient ndda, ProductRegistrationWriter writer,
                                   PlatformTransactionManager tm, ProductCatalog catalog) {
        this(products, ndda, writer, tm, catalog::refresh, UpstreamRetry.REAL);
    }

    public ProductRegistrationSync(SiteProductRepository products, NddaClient ndda, ProductRegistrationWriter writer,
                                   PlatformTransactionManager tm, Runnable refreshCatalog, UpstreamRetry.Sleeper sleeper) {
        this.products = products; this.ndda = ndda; this.writer = writer; this.refreshCatalog = refreshCatalog; this.sleeper = sleeper;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
    }

    public Result sync() {
        List<String> numbers = readTx.execute(st -> products.findByMarketAndRemovedAtIsNull(Market.KZ).stream()
                .map(SiteProduct::getRegistrationCert).map(RegNumbers::normalize).filter(Objects::nonNull).distinct().sorted().toList());
        int active = 0, expired = 0, notFound = 0, errors = 0;
        for (String n : numbers == null ? List.<String>of() : numbers) {
            pause();
            try {
                NddaRegistration r = ndda.findRegistration(n);
                if (r == null) {
                    writer.notFound(n);
                    notFound++;
                    continue;
                }
                writer.save(n, r, ndda.fetchDetail(r.id()), ndda.fetchComplectList(r.id()));
                if (r.active()) active++; else expired++;
            } catch (RuntimeException e) {
                writer.error(n, ErrorText.of(e));
                errors++;
            }
        }
        Result res = new Result(numbers == null ? 0 : numbers.size(), active, expired, notFound, errors);
        if (errors > 0) log.warn("Наша продукция: {}", res.text()); else log.info("Наша продукция: {}", res.text());
        refreshCatalog.run();
        return res;
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 10: Прогнать — зелёный**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.ProductRegistrationSyncTest'`
Expected: PASS (2).

- [ ] **Step 11: Место в загрузке каталога**

`ProductWatchScheduler`: поле `private final ProductRegistrationSync registrations;`, пятый параметр конструктора (см. Interfaces). `startCatalog`:

```java
    public Status startCatalog() {
        if (!enabled || !catalogQueued.compareAndSet(false, true)) return status();
        executor.execute(() -> runTask("каталог сайта", () -> {
            String text;
            try {
                SiteCatalogSync.SyncResult r = catalog.sync();
                text = "товаров " + r.count() + " (новых " + r.added() + ", изменено " + r.updated() + ", пропало " + r.removed() + ")"
                        + (r.full() ? "" : " — без описаний (публичный список)");
            } catch (RuntimeException e) {
                text = "не загрузился: " + ErrorText.of(e);
            }
            try {
                text += "; " + registrations.sync().text();     // РУ — и при сбое каталога: товары сайта в базе есть
            } catch (RuntimeException e) {
                text += "; РУ не проверены: " + ErrorText.of(e);
            } finally {
                lastCatalog = text;
                lastCatalogAt = Instant.now();
                catalogQueued.set(false);
                startRematch();                                  // новые характеристики и РУ — пересчёт открытых тендеров
            }
        }));
        return status();
    }
```

`ProductWatchSchedulerTest`: в помощник `scheduler(…)` — `ProductRegistrationSync registrations = mock(ProductRegistrationSync.class);` пятым аргументом. Новый тест порядка:

```java
    @Test
    void catalogRun_thenRegistrations_thenRematch() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        when(registrations.sync()).thenReturn(new ProductRegistrationSync.Result(1, 1, 0, 0, 0));
        when(catalog.sync()).thenReturn(new SiteCatalogSync.SyncResult(1, 0, 0, 0, true));
        doAnswer(inv -> { done.countDown(); return 0; }).when(rematch).rematchOpen();
        scheduler(true, "-", "-", "-").startCatalog();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        InOrder o = inOrder(catalog, registrations, rematch);
        o.verify(catalog).sync();
        o.verify(registrations).sync();
        o.verify(rematch).rematchOpen();
    }
```

(импорты `CountDownLatch`, `TimeUnit`, `InOrder`, `ProductRegistrationSync` — как у соседних тестов класса).

- [ ] **Step 12: Прогнать пакет — зелёный; мутации**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.*' --tests 'com.vladoose.nir.integration.ndda.*'`
Expected: PASS. Мутации: (1) `findRegistration` без второго списка → красный `expired_secondList`; (2) `writer.error` сбрасывает статус в UNKNOWN у существующей строки → красный `failureKeepsPrevious`; (3) без `distinct()` → красный `activeExpiredNotFound…` (4 запроса); (4) `startCatalog` без `registrations.sync()` → красный `catalogRun_thenRegistrations_thenRematch`. Потом `./gradlew compileJava`.

- [ ] **Step 13: Коммит**

```bash
git add src/main/resources/db/migration/V39__product_spec_fit.sql src/main/java/com/vladoose/nir/entity/ProductRegistration.java \
  src/main/java/com/vladoose/nir/entity/RegistrationComponent.java src/main/java/com/vladoose/nir/entity/TenderLotProductMatch.java \
  src/main/java/com/vladoose/nir/repository/ProductRegistrationRepository.java src/main/java/com/vladoose/nir/integration/ndda/ \
  src/main/java/com/vladoose/nir/service/productwatch/ProductRegistrationSync.java src/main/java/com/vladoose/nir/service/productwatch/ProductRegistrationWriter.java \
  src/main/java/com/vladoose/nir/service/productwatch/ProductWatchScheduler.java src/test/java/com/vladoose/nir/integration/ndda/NddaRegistrationClientTest.java \
  src/test/java/com/vladoose/nir/productwatch/ProductRegistrationSyncTest.java src/test/java/com/vladoose/nir/productwatch/ProductWatchSchedulerTest.java
git commit -m "feat(product-fit): V39 и проверка РУ товаров сайта на ndda.kz после загрузки каталога

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Сверка в отметках — уровень FITS, снимок с карточками, evaluate

**Files:**
- Modify: `entity/ProductMatchLevel.java`, `service/productwatch/CatalogSnapshot.java`, `ProductCatalog.java`, `ProductMatchService.java`, `ProductMatchQuery.java`, `ProductWatchComposer.java` (ветка FITS в `switch`), `repository/TenderLotProductMatchRepository.java`, `repository/MedRegistryRepository.java`
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductFitEvaluateTest.java`

**Interfaces:**
- Consumes: `SpecRequirements.body / extract / reflowText`, `SpecFit.evaluate`, `ForeignBrand.detect / ours / registryBrands`, `ProductCards.of`, `RegistrationInfo`, `LotFit`, сущность `ProductRegistration`.
- Produces:
  - `ProductMatchLevel`: `TYPE, FITS, NAMED, COPIED`; `exact()` — `NAMED`, `COPIED`; `alert()` — всё, кроме `TYPE`.
  - `CatalogSnapshot.build(List<ProductGroup>, List<SiteProduct>, Map<String, RegistrationInfo> registrations, Set<String> knownBrands)`; прежние `build(groups, products)` и `build(groups, products, rules)` — с пустыми; доступ `ProductCard card(long productId)`, `List<ProductCard> groupCards(long groupId)`, `GroupRule group(long groupId)`, `Set<String> ours()`, `Set<String> knownBrands()`.
  - `ProductMatchQuery.LotLine` — ещё компонент `FitSummary fit` (последний; прежний конструктор без него — остаётся); `record FitSummary(FitVerdict verdict, String productName, String productSlug, int met, int total, int unknown, String reasonShort, String foreignBrand, String regStatus)`.
  - `MedRegistryRepository.findDistinctProducers()`.
  - `static String ProductMatchService.fitText(String spec, String description)` (разобранное ТЗ — тело, иначе описание со склейкой строк) и `static LotFit ProductMatchService.fitFor(CatalogSnapshot snap, long groupId, String text)` (бренд-группа или неизвестная — `null`) — общие для отметки, «Требований ТЗ», «Для ИИ» и гейта.

- [ ] **Step 1: Тест (красный)**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Сверка в отметках (спека §6.5): FITS поднимает TYPE, NOT_FIT и «ТЗ под X» остаются у TYPE, бренд-группа — без сверки. */
@SpringBootTest
@Transactional
class ProductFitEvaluateTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProductMatchQuery query;
    @Autowired EntityManager em;

    ProductMatchService service;
    SiteProduct omh, ehvch;

    static final String ASPIRATOR_SPEC = "Приложение 2\nТехническая спецификация\nОписание и требуемые функциональные, технические, "
            + "качественные и эксплуатационные характеристики закупаемых товаров:\nМедицинский аспиратор. Основные технические "
            + "характеристики: работа от электрической сети; вакуумная система отсасывания; производительность отсасывания: "
            + "примерно 15–30 л/мин; уровень вакуума: до 0–75 кПа; наличие ёмкости для сбора отсасываемой жидкости.";

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        // группы сида (V36–V38): в них есть «Электрохирургия (Фотек)» — в ProductFixtures.groups() её нет
        List<ProductGroup> groups = ProductFixtures.seedGroups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        omh = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Отсасыватель медицинский хирургический ОМХ-5/80 «Аксион» от электрической сети, вакуумная система, ёмкость для сбора жидкости")
                .brand("Аксион").slug("omh").registrationCert("РК-1").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                        new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа")))).build());
        ehvch = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("ВЧ электрохирургический блок для аппарата ЭХВЧ-80-03-«ФОТЕК»").brand("Фотек").slug("ehvch80")
                .registrationCert("РК-2").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Максимальная выходная мощность", "80 Вт")))).build());
        Map<String, RegistrationInfo> regs = Map.of(
                "РК-1", new RegistrationInfo("РК-1", RegStatus.ACTIVE, null, true, null, null, null, List.of()),
                "РК-2", new RegistrationInfo("РК-2", RegStatus.ACTIVE, null, true, null, null, null, List.of()));
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of(omh, ehvch), regs, Set.of());
        service = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    Tender tender(String ext, String lotName, String spec, TechSpecStatus status) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп медицинских изделий"); t.setPlatform(TenderPlatform.GOSZAKUP);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName); l.setRequiredSpec(spec); l.setTechSpecStatus(status);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        return t;
    }

    /** Строка отметки группы вида (не «Наши бренды и модели»). */
    TenderLotProductMatch row(Tender t) {
        return matches.findByTenderId(t.getId()).stream()
                .filter(m -> groupRepo.findById(m.getGroupId()).map(g -> !g.isBrandGroup()).orElse(false))
                .findFirst().orElseThrow();
    }

    @Test
    void parsedSpec_fits_levelRaised() {
        Tender t = tender("FIT-1", "Аспиратор", ASPIRATOR_SPEC, TechSpecStatus.OK);
        ProductMatchService.Result r = service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(r.level()).isEqualTo(ProductMatchLevel.FITS);
        TenderLotProductMatch m = row(t);
        assertThat(m.getFit()).isEqualTo(FitVerdict.FITS);
        assertThat(m.getFitProductId()).isEqualTo(omh.getId());
        assertThat(m.getFitMet()).isGreaterThanOrEqualTo(3);
        assertThat(m.getFitCandidates()).isNotEmpty();
        assertThat(m.getFitCandidates().get(0).rows()).isNotEmpty();
        assertThat(m.getFitCheckedAt()).isNotNull();
        assertThat(query.ourProduct(List.of(t.getId())).get(t.getId()).label()).startsWith("✓ подходит: Отсасыватель");
    }

    /** Review Focus #5: ТЗ не разобрано, но в описании площадки есть числа — сверка по описанию. */
    @Test
    void descriptionOnlyLot_fitComputed() {
        Tender t = tender("FIT-2", "Отсасыватель", "Отсасыватель медицинский: работа от электрической сети; производительность "
                + "не менее 15 л/мин; разрежение до 75 кПа; вакуумная система отсасывания", null);
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        TenderLotProductMatch m = row(t);
        assertThat(m.getFit()).isNotNull().isNotEqualTo(FitVerdict.NO_REQUIREMENTS);
    }

    /** ТЗ под BOWA с мощностью больше нашей — «не подходит», «ТЗ под BOWA», уровень TYPE. */
    @Test
    void foreignAndNotFit_staysType() {
        Tender t = tender("FIT-3", "Электрокоагулятор", "Подробные технические характеристики основного блока ЭХВЧ BOWA ARC 303; "
                + "выходная мощность не менее 300 Вт; режим биполярной коагуляции; режим монополярного резания", TechSpecStatus.OK);
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        TenderLotProductMatch m = row(t);
        assertThat(m.getLevel()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(m.getFit()).isEqualTo(FitVerdict.NOT_FIT);
        assertThat(m.getForeignBrand()).isEqualTo("BOWA");
        assertThat(m.getFitReason()).contains("300");
        assertThat(query.ourProduct(List.of(t.getId())).get(t.getId()).label()).startsWith("✗ не подходит: ");
    }

    /** Без ТЗ и без чисел — требований нет, сверки нет, уровень TYPE. */
    @Test
    void noRequirements_type() {
        Tender t = tender("FIT-4", "Отсасыватель", "медицинский", null);
        ProductMatchService.Result r = service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(r.level()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(row(t).getFit()).isEqualTo(FitVerdict.NO_REQUIREMENTS);
    }

    /** «Наши бренды и модели» — назван наш товар, сверка не нужна: у её строки fit пуст. */
    @Test
    void brandGroup_noFit() {
        Tender t = tender("FIT-6", "Аппарат для аспирации дыма АСД-«ФОТЕК»", ASPIRATOR_SPEC, TechSpecStatus.OK);
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        TenderLotProductMatch brand = matches.findByTenderId(t.getId()).stream()
                .filter(m -> groupRepo.findById(m.getGroupId()).map(ProductGroup::isBrandGroup).orElse(false))
                .findFirst().orElseThrow();
        assertThat(brand.getLevel()).isEqualTo(ProductMatchLevel.NAMED);
        assertThat(brand.getFit()).isNull();
        assertThat(brand.getFitCandidates()).isEmpty();
    }

    /** FITS ещё не сообщён — в очереди уведомлений; после отметки «сообщено FITS» — нет. */
    @Test
    void notifyQueueKnowsFits() {
        Tender t = tender("FIT-5", "Аспиратор", ASPIRATOR_SPEC, TechSpecStatus.OK);
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(matches.findTendersToNotify()).contains(t.getId());
        service.markNotified(t.getId(), ProductMatchLevel.FITS);
        em.flush();
        assertThat(matches.findTendersToNotify()).doesNotContain(t.getId());
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.ProductFitEvaluateTest'`
Expected: FAIL — компиляция (`ProductMatchLevel.FITS`, `CatalogSnapshot.build(…, regs, …)` нет).

- [ ] **Step 3: Уровень FITS**

```java
package com.vladoose.nir.entity;

/** Уровень отметки «наш товар» (спеки 2026-10-19-product-watch §4.3, 2026-10-20-product-spec-fit §6.4): порядок значим. */
public enum ProductMatchLevel {
    /** Наш вид изделия. */
    TYPE,
    /** ТЗ сверено с нашим товаром — подходит (РУ действует). */
    FITS,
    /** Назван наш бренд или модель. */
    NAMED,
    /** ТЗ списано с нашего товара. */
    COPIED;

    /** «★ точно наше». */
    public boolean exact() { return this == NAMED || this == COPIED; }

    /** В Telegram в режиме «только ★» (`ProductNotifyPolicy` STAR): подходит или точно наше. */
    public boolean alert() { return this != TYPE; }

    public static ProductMatchLevel max(ProductMatchLevel a, ProductMatchLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
```

`TenderLotProductMatchRepository.findTendersToNotify` — порядок уровней:

```sql
            HAVING MAX(CASE m.level WHEN 'COPIED' THEN 4 WHEN 'NAMED' THEN 3 WHEN 'FITS' THEN 2 ELSE 1 END)
                 > COALESCE(CASE t.product_notified_level WHEN 'COPIED' THEN 4 WHEN 'NAMED' THEN 3 WHEN 'FITS' THEN 2 WHEN 'TYPE' THEN 1 END, 0)
```

Проверить, что других SQL с порядком уровней нет: `grep -rn "WHEN 'COPIED'" src/main` — одно место.

`ProductNotifyPolicy.levelPasses`: `case STAR -> level.alert();` (FITS в Telegram — решение 2 спеки). `ProductWatchNotifier.prepare`: фильтр лотов «Уточнения» и режима STAR — `l.level().alert()` вместо `exact()`.

- [ ] **Step 4: Снимок с карточками**

`MedRegistryRepository`:

```java
    @Query("select distinct m.producer from MedRegistry m where m.producer is not null")
    List<String> findDistinctProducers();
```

`CatalogSnapshot` — новые поля и сборка:

```java
    private final Map<Long, ProductCard> cards;
    private final Map<Long, List<ProductCard>> groupCards;
    private final Map<Long, GroupRule> groupById;
    private final Set<String> ours;
    private final Set<String> knownBrands;

    public static CatalogSnapshot build(List<ProductGroup> groups, List<SiteProduct> siteProducts) {
        return build(groups, siteProducts, Map.of(), Set.of(), CatalogPhrases.Rules.DEFAULT);
    }

    public static CatalogSnapshot build(List<ProductGroup> groups, List<SiteProduct> siteProducts, CatalogPhrases.Rules phraseRules) {
        return build(groups, siteProducts, Map.of(), Set.of(), phraseRules);
    }

    public static CatalogSnapshot build(List<ProductGroup> groups, List<SiteProduct> siteProducts,
                                        Map<String, RegistrationInfo> registrations, Set<String> knownBrands) {
        return build(groups, siteProducts, registrations, knownBrands, CatalogPhrases.Rules.DEFAULT);
    }
```

В общем `build(…, registrations, knownBrands, phraseRules)` (прежнее тело + в конце):

```java
        Map<Long, ProductCard> cards = new HashMap<>();
        for (SiteProduct p : siteProducts) {
            if (p.getRemovedAt() != null || p.getId() == null) continue;
            String reg = RegNumbers.normalize(p.getRegistrationCert());
            cards.put(p.getId(), ProductCards.of(p, reg == null ? null : registrations.get(reg)));
        }
        Map<Long, List<ProductCard>> groupCards = new HashMap<>();
        byGroup.forEach((gid, ps) -> groupCards.put(gid, ps.stream().map(p -> cards.get(p.id())).filter(Objects::nonNull).toList()));
        Map<Long, GroupRule> byId = new HashMap<>();
        rules.forEach(r -> byId.put(r.id(), r));
        Set<String> ours = ForeignBrand.ours(products.stream().map(CatalogProduct::name).toList(),
                products.stream().map(CatalogProduct::brand).toList());
```

и передать их в конструктор; доступ:

```java
    public ProductCard card(long productId) { return cards.get(productId); }
    public List<ProductCard> groupCards(long groupId) { return groupCards.getOrDefault(groupId, List.of()); }
    public GroupRule group(long groupId) { return groupById.get(groupId); }
    public Set<String> ours() { return ours; }
    public Set<String> knownBrands() { return knownBrands; }
```

`ProductCatalog` — конструктор берёт ещё `ProductRegistrationRepository registrations`, `MedRegistryRepository registry`; `refresh()`:

```java
    public synchronized void refresh() {
        snapshot = readTx.execute(st -> {
            Map<String, RegistrationInfo> regs = new HashMap<>();
            for (ProductRegistration r : registrations.findAll()) {
                regs.put(r.getRegNumber(), new RegistrationInfo(r.getRegNumber(), r.getStatus(), r.getExpirationDate(),
                        Boolean.TRUE.equals(r.getUnlimited()), r.getPurpose(), r.getUseArea(), r.getTechChars(),
                        r.getComplect().stream().map(RegistrationComponent::component).filter(Objects::nonNull).toList()));
            }
            return CatalogSnapshot.build(groups.findByMarketAndActiveTrueOrderByPositionAscIdAsc(Market.KZ),
                    products.findByMarketAndRemovedAtIsNull(Market.KZ), regs, ForeignBrand.registryBrands(registry.findDistinctProducers()));
        });
    }
```

- [ ] **Step 5: Сверка в `evaluate`**

`ProductMatchService.evaluate` целиком (снимок — один раз; текст сверки — один раз на лот):

```java
    @Transactional
    public Result evaluate(long tenderId, ProductMatchSource source) {
        Tender t = tenders.findById(tenderId).orElse(null);
        if (t == null) return new Result(tenderId, null, 0, false);
        List<LotInput> inputs = new ArrayList<>();
        for (TenderLot l : t.getLots()) {
            if (l.getSourceRemovedAt() != null || l.getId() == null) continue;
            boolean spec = l.getTechSpecStatus() == TechSpecStatus.OK;
            // разобранное ТЗ заменило описание площадки — берём его из шапки ТЗ (исключения групп смотрят на текст лота)
            String description = spec ? LotDescriptiveText.lotDescription(l.getRequiredSpec()) : l.getRequiredSpec();
            inputs.add(new LotInput(l.getId(), l.getEquipName(), description, spec ? l.getRequiredSpec() : null));
        }
        CatalogSnapshot snap = catalog.get();
        List<LotMatch> result = ProductMatcher.match(snap, t.getDescription(), inputs);
        Map<Long, LotInput> inputById = new HashMap<>();
        inputs.forEach(i -> inputById.put(i.lotId(), i));
        Map<Long, String> textByLot = new HashMap<>();
        List<TenderLotProductMatch> existing = matches.findByTenderId(tenderId);
        boolean hadMarks = !existing.isEmpty();
        Map<String, TenderLotProductMatch> byKey = new HashMap<>();
        for (TenderLotProductMatch m : existing) byKey.put(m.getLotId() + "/" + m.getGroupId(), m);
        OffsetDateTime now = OffsetDateTime.now();
        Set<String> keep = new HashSet<>();
        Set<Long> lots = new HashSet<>();
        ProductMatchLevel max = null;
        for (LotMatch m : result) {
            String key = m.lotId() + "/" + m.groupId();
            keep.add(key);
            lots.add(m.lotId());
            TenderLotProductMatch row = byKey.get(key);
            if (row == null) {
                row = TenderLotProductMatch.builder().tenderId(tenderId).lotId(m.lotId()).groupId(m.groupId())
                        .source(source).matchedAt(now).build();
            }
            row.setWord(trunc(m.word(), 100));
            row.setEvidence(trunc(m.evidence(), 1000));
            row.setProductId(m.productId());
            row.setSimilar(new ArrayList<>(m.similar()));
            if (m.specUsed() && row.getSpecCheckedAt() == null) row.setSpecCheckedAt(now);
            row.setUpdatedAt(now);
            ProductMatchLevel level = m.level();
            LotInput in = inputById.get(m.lotId());
            String text = textByLot.computeIfAbsent(m.lotId(), id -> fitText(in.spec(), in.description()));
            LotFit fit = fitFor(snap, m.groupId(), text);
            applyFit(row, fit, now);
            if (fit != null && level == ProductMatchLevel.TYPE && fit.verdict() == FitVerdict.FITS) level = ProductMatchLevel.FITS;
            row.setLevel(level);
            matches.save(row);
            max = ProductMatchLevel.max(max, level);
        }
        for (TenderLotProductMatch m : existing) {
            if (!keep.contains(m.getLotId() + "/" + m.getGroupId())) matches.delete(m);
        }
        return new Result(tenderId, max, lots.size(), hadMarks);
    }

    private static void applyFit(TenderLotProductMatch row, LotFit fit, OffsetDateTime now) {
        FitCandidate best = fit == null ? null : fit.best();
        row.setFit(fit == null ? null : fit.verdict());
        row.setFitProductId(best == null ? null : best.productId());
        row.setFitMet(best == null ? null : best.met());
        row.setFitTotal(best == null ? null : best.total());
        row.setFitReason(best == null ? null : trunc(best.reason(), 300));
        row.setForeignBrand(fit == null ? null : trunc(fit.foreignBrand(), 100));
        row.setFitCandidates(fit == null ? new ArrayList<>() : new ArrayList<>(fit.candidates()));
        row.setFitCheckedAt(fit == null ? null : now);
    }
```

(импорты — `FitCandidate`, `FitVerdict`, `service.productwatch.fit.*`, `java.util.*`.)

Общие помощники — их же зовут «Требования ТЗ» у лота (задача 10), «Для ИИ» (задача 13) и гейт (задача 14): проверять качество и показывать требования нужно тем же кодом, что считает отметку (CLAUDE.md §14):

```java
    /**
     * Текст для сверки (спека §5.1): разобранное ТЗ — раздел характеристик; нет ТЗ — описание лота с площадки, строки склеены.
     * `spec` — `required_spec` при `tech_spec_status = OK`, иначе null; `description` — описание площадки.
     */
    public static String fitText(String spec, String description) {
        return spec != null && !spec.isBlank() ? SpecRequirements.body(spec) : SpecRequirements.reflowText(Objects.toString(description, ""));
    }

    /** Сверка лота с товарами группы вида (спека §6); группа «Наши бренды и модели» или неизвестная — null. */
    public static LotFit fitFor(CatalogSnapshot snap, long groupId, String text) {
        GroupRule rule = snap.group(groupId);
        if (rule == null || rule.brandGroup()) return null;
        return SpecFit.evaluate(SpecRequirements.extract(text), snap.groupCards(groupId), ForeignBrand.detect(text, snap.ours(), snap.knownBrands()));
    }
```

```java
    private static void applyFit(TenderLotProductMatch row, LotFit fit, OffsetDateTime now) {
        FitCandidate best = fit == null ? null : fit.best();
        row.setFit(fit == null ? null : fit.verdict());
        row.setFitProductId(best == null ? null : best.productId());
        row.setFitMet(best == null ? null : best.met());
        row.setFitTotal(best == null ? null : best.total());
        row.setFitReason(best == null ? null : trunc(best.reason(), 300));
        row.setForeignBrand(fit == null ? null : trunc(fit.foreignBrand(), 100));
        row.setFitCandidates(fit == null ? new ArrayList<>() : new ArrayList<>(fit.candidates()));
        row.setFitCheckedAt(fit == null ? null : now);
    }
```

(прежние `row.setLevel(m.level())` и `max = …(m.level())` заменяются на `level`).

- [ ] **Step 6: Строки запроса и составителя — ветка FITS**

`ProductMatchQuery`:

```java
    /** Итог сверки нашего лота — для чипа и сообщения (спека §6.3, §7). */
    public record FitSummary(FitVerdict verdict, String productName, String productSlug, int met, int total, int unknown,
                             String reasonShort, String foreignBrand, String regStatus) {}

    public record LotLine(long lotId, String lotName, Integer qty, ProductMatchLevel level, String groupName, String word,
                          String evidence, String productName, String productSlug, List<SimilarProductRef> similar, FitSummary fit) {
        public LotLine(long lotId, String lotName, Integer qty, ProductMatchLevel level, String groupName, String word,
                       String evidence, String productName, String productSlug, List<SimilarProductRef> similar) {
            this(lotId, lotName, qty, level, groupName, word, evidence, productName, productSlug, similar, null);
        }
    }
```

В `summaries` — `fit` из строки отметки:

```java
    static FitSummary fit(TenderLotProductMatch m) {
        if (m.getFit() == null || m.getFitCandidates() == null || m.getFitCandidates().isEmpty()) {
            return m.getFit() == null ? null : new FitSummary(m.getFit(), null, null, 0, 0, 0, null, m.getForeignBrand(), null);
        }
        FitCandidate b = m.getFitCandidates().get(0);
        return new FitSummary(m.getFit(), b.name(), b.slug(), b.met(), b.total(), b.unknown(), b.reasonShort(), m.getForeignBrand(), b.regStatus());
    }
```

`label` целиком (чип тендера в списке — вердикт лучшего лота, спека §8.3):

```java
    /** Подпись чипа тендера: ★ — как было; «подходит» — наш товар; у вида — вердикт сверки лучшего лота; сверки нет — слово группы. */
    static String label(ProductMatchLevel level, List<LotLine> lines) {
        LotLine top = lines.get(0);
        if (level != null && level.exact()) return "★ наш товар: " + what(top);
        if (level == ProductMatchLevel.FITS) {
            return "✓ подходит: " + shortName(top.fit() != null && top.fit().productName() != null ? top.fit().productName() : top.word());
        }
        FitSummary f = lines.stream().map(LotLine::fit).filter(Objects::nonNull)
                .max(Comparator.comparingInt((FitSummary x) -> x.verdict().rank())).orElse(null);
        if (f != null && f.verdict() == FitVerdict.FITS_NO_RU) return "подходит по ТЗ, " + noRu(f.regStatus());
        if (f != null && f.verdict() == FitVerdict.PARTIAL) return "◐ похоже: " + top.word();
        if (f != null && f.verdict() == FitVerdict.NOT_FIT) return "✗ не подходит: " + Objects.toString(f.reasonShort(), top.word());
        return "🏷 наш вид: " + top.word();
    }

    /** «РУ истекло / не найдено / не указано / не проверено» по имени статуса (null — не проверено). */
    static String noRu(String regStatus) {
        try {
            return regStatus == null ? RegStatus.UNKNOWN.noRuText() : RegStatus.valueOf(regStatus).noRuText();
        } catch (IllegalArgumentException e) {
            return RegStatus.UNKNOWN.noRuText();
        }
    }
```

(импорты — `FitVerdict`, `RegStatus`, `java.util.Comparator`.) `telegramLine` (в `switch`): `case FITS -> "✓ Подходит наш товар: " + shortName(Objects.toString(top.fit() == null ? null : top.fit().productName(), top.word())) + metTail(top.fit());`, где

```java
    /** « — 5 из 8 требований (нет данных: 3)». */
    public static String metTail(FitSummary f) {
        if (f == null || f.total() == 0) return "";
        return " — " + f.met() + " из " + f.total() + " требований" + (f.unknown() > 0 ? " (нет данных: " + f.unknown() + ")" : "");
    }
```

`ProductWatchComposer.block` — в `switch (l.level())`:

```java
                case FITS -> "подходит наш товар: " + TelegramText.oneLine(ProductMatchQuery.shortName(
                        l.fit() == null ? l.word() : l.fit().productName()), 100) + ProductMatchQuery.metTail(l.fit());
```

и строка «ТЗ под X» в задаче 9.

- [ ] **Step 7: Прогнать — зелёный, вместе с прежними тестами блока**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.*' --tests 'com.vladoose.nir.service.productwatch.*' --tests 'com.vladoose.nir.service.tendernotify.*'`
Expected: PASS. Прежние тесты, где «★» ждали по `exact()`, — без изменений (NAMED / COPIED те же).

- [ ] **Step 8: Мутации**

(1) не поднимать TYPE до FITS → красный `parsedSpec_fits_levelRaised`; (2) сверка и у бренд-группы (в `fitFor` снять `|| rule.brandGroup()`) → красный `brandGroup_noFit`; (3) SQL без `WHEN 'FITS'` → красный `notifyQueueKnowsFits`; (4) `fitText` из описания и для лотов с ТЗ → красный `parsedSpec_fits…` (описание короче); (5) `label` без вердиктов вида (всегда «🏷 наш вид») → красный `foreignAndNotFit_staysType`. Потом `./gradlew compileJava`.

- [ ] **Step 9: Коммит**

```bash
git add src/main/java/com/vladoose/nir/entity/ProductMatchLevel.java src/main/java/com/vladoose/nir/service/productwatch/ \
  src/main/java/com/vladoose/nir/repository/TenderLotProductMatchRepository.java src/main/java/com/vladoose/nir/repository/MedRegistryRepository.java \
  src/test/java/com/vladoose/nir/productwatch/ProductFitEvaluateTest.java
git commit -m "feat(product-fit): уровень «✓ подходит», сверка в отметках, снимок с карточками товаров и РУ

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Очередь ТЗ — только тендеры, где наши лоты от порога суммы

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/productwatch/OurLots.java`
- Modify: `ProductSpecChecker.java`, `ProductWatchNotifier.java` (сумма — через `OurLots`)
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductSpecCheckerThresholdTest.java`, `ProductSpecCheckerTest.java` (конструктор)

**Interfaces:**
- Produces: `static BigDecimal OurLots.sum(Tender t, Collection<Long> lotIds)` — правило прежнего `ProductWatchNotifier.ourLotsSum` (цена за единицу × количество; лот один и без цены — сумма тендера; ничего — null); конструктор `ProductSpecChecker(…, ProductNotifyPolicy policy)` — последним параметром у тестового, `@Value` строки — у `@Autowired`.

- [ ] **Step 1: Тест (красный)**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.LotSpecParser;
import com.vladoose.nir.service.TechSpecLotParser;
import com.vladoose.nir.service.TechSpecStatusWriter;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ТЗ качаем только у тендеров, где наши лоты от порога (спека §12): дешёвые и с неизвестной суммой — нет. */
@SpringBootTest
@Transactional
class ProductSpecCheckerThresholdTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired TechSpecStatusWriter statusWriter;
    @Autowired EntityManager em;

    final List<Long> parsed = new ArrayList<>();
    ProductMatchService matchService;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of());
        matchService = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Тендер: наш лот «Отсасыватель» (цена за единицу × количество) + чужой «Бумага офисная»; приём — через `days` дней. */
    long lot(String ext, Integer qty, String unitPrice, BigDecimal total, int days) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп"); t.setPlatform(TenderPlatform.GOSZAKUP); t.setTotalCost(total);
        t.setDeadline(LocalDateTime.now().plusDays(days).toLocalDate()); t.setDeadlineAt(LocalDateTime.now().plusDays(days));
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName("Отсасыватель"); l.setQuantity(qty);
        if (unitPrice != null) l.setMaxCost(new BigDecimal(unitPrice));
        l.setTechSpecStatus(TechSpecStatus.PENDING);
        t.getLots().add(l);
        TenderLot other = new TenderLot(); other.setTender(t); other.setEquipName("Бумага офисная"); other.setQuantity(1);
        t.getLots().add(other);
        tenders.save(t);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        em.flush();
        return t.getLots().get(0).getId();
    }

    ProductSpecChecker checker(String minSum) {
        LotSpecParser parser = lotId -> { parsed.add(lotId); return new TechSpecLotParser.Result(TechSpecStatus.NO_FILE, false); };
        return new ProductSpecChecker(matches, lots, statusWriter, parser, matchService, () -> true, ms -> {},
                ProductNotifyPolicy.of("star", minSum));
    }

    @Test
    void onlyFromThreshold() {
        long cheap = lot("TH-1", 1, "300000", null, 2);
        long rich = lot("TH-2", 2, "900000", null, 2);
        long unknown = lot("TH-3", null, null, new BigDecimal("5000000"), 2);   // лотов два, цены нет — сумма не доказана
        checker("1000000").run(10, new ImportSummary());
        assertThat(parsed).containsExactly(rich).doesNotContain(cheap, unknown);
    }

    /** Фильтр — до предела пачки: дешёвый лот с ближайшим сроком не занимает место дорогого. */
    @Test
    void limitCountsOnlyPassing() {
        lot("TH-6", 1, "300000", null, 1);
        long rich = lot("TH-7", 2, "900000", null, 3);
        checker("1000000").run(1, new ImportSummary());
        assertThat(parsed).containsExactly(rich);
    }

    @Test
    void zeroThreshold_all() {
        lot("TH-4", 1, "300000", null, 2);
        lot("TH-5", 2, "900000", null, 2);
        checker("0").run(10, new ImportSummary());
        assertThat(parsed).hasSize(2);
    }
}
```

(`TechSpecLotParser.Result(TechSpecStatus status, boolean outage)`, `LotSpecParser` — `Result parse(Long lotId)`, `UpstreamRetry.Sleeper` — `void sleep(long ms)`: сверено по коду.)

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.ProductSpecCheckerThresholdTest'`
Expected: FAIL — компиляция (конструктора с `ProductNotifyPolicy` нет).

- [ ] **Step 3: `OurLots` и фильтр очереди**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Сумма наших лотов тендера (решение оператора 2026-10-20: порог «от 1 млн ₸»): цена за единицу × количество; лот в
 * тендере один и без цены — сумма тендера; ничего не известно — null (порог не доказан).
 */
public final class OurLots {
    private OurLots() {}

    public static BigDecimal sum(Tender t, Collection<Long> lotIds) {
        Set<Long> ids = new HashSet<>(lotIds);
        BigDecimal sum = null;
        for (TenderLot l : t.getLots()) {
            if (!ids.contains(l.getId()) || l.getMaxCost() == null || l.getQuantity() == null) continue;
            BigDecimal a = l.getMaxCost().multiply(BigDecimal.valueOf(l.getQuantity()));
            sum = sum == null ? a : sum.add(a);
        }
        if (sum == null && t.getLots().size() == 1 && ids.contains(t.getLots().get(0).getId())) return t.getTotalCost();
        return sum;
    }
}
```

`ProductWatchNotifier`: `ourLotsSum` удалить, вызов — `OurLots.sum(t, lots.stream().map(ProductMatchQuery.LotLine::lotId).toList())`.

`ProductSpecChecker` — поле `private final ProductNotifyPolicy policy;` и конструкторы:

```java
    @Autowired
    public ProductSpecChecker(TenderLotProductMatchRepository matches, TenderLotRepository lots, TechSpecStatusWriter statusWriter,
                              TechSpecLotParser parser, ProductMatchService matchService, GoszakupClient goszakup,
                              @Value("${product-watch.notify:}") String notify, @Value("${product-watch.notify-min-sum:}") String notifyMinSum) {
        this(matches, lots, statusWriter, parser, matchService, goszakup::isConfigured, UpstreamRetry.REAL,
                ProductNotifyPolicy.of(notify, notifyMinSum));
    }

    public ProductSpecChecker(TenderLotProductMatchRepository matches, TenderLotRepository lots, TechSpecStatusWriter statusWriter,
                              LotSpecParser parser, ProductMatchService matchService, BooleanSupplier goszakupConfigured,
                              UpstreamRetry.Sleeper sleeper, ProductNotifyPolicy policy) {
        this.matches = matches; this.lots = lots; this.statusWriter = statusWriter; this.parser = parser;
        this.matchService = matchService; this.goszakupConfigured = goszakupConfigured; this.sleeper = sleeper; this.policy = policy;
    }
```

В `run` — очередь (javadoc класса — ещё «ТЗ — только у тендеров, где сумма всех отмеченных лотов не меньше порога `PRODUCT_WATCH_NOTIFY_MIN_SUM`, спека 2026-10-20-product-spec-fit §12»); отметки кандидатов — одним запросом, фильтр — до предела пачки:

```java
        List<TenderLot> candidates = lots.findAllById(matches.findLotsForSpecCheckUnordered(goszakupConfigured.getAsBoolean()));
        if (candidates.isEmpty()) return 0;
        Map<Long, List<Long>> marked = matches.findByTenderIdIn(candidates.stream().map(l -> l.getTender().getId()).collect(Collectors.toSet()))
                .stream().collect(Collectors.groupingBy(TenderLotProductMatch::getTenderId,
                        Collectors.mapping(TenderLotProductMatch::getLotId, Collectors.toList())));
        List<TenderLot> queue = candidates.stream()
                .filter(l -> policy.sumPasses(OurLots.sum(l.getTender(), marked.getOrDefault(l.getTender().getId(), List.of()))))
                .sorted(Comparator.comparing((TenderLot l) -> l.getTender().getDeadlineAt(), Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder())))
                .limit(limit).toList();
```

(импорты — `TenderLotProductMatch`, `org.springframework.beans.factory.annotation.Value`, `java.util.Map`, `java.util.stream.Collectors`.)

`ProductSpecCheckerTest` — прежние вызовы конструктора: последним аргументом `ProductNotifyPolicy.of("all", "0")` (без порога — как было).

- [ ] **Step 4: Прогнать — зелёный; мутации**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.*'`
Expected: PASS. Мутации: (1) фильтр после `limit` (`.limit` выше `.filter`) → красный `limitCountsOnlyPassing`; (2) `OurLots.sum` без проверки `ids.contains` (считает и чужие лоты) → красный `ProductWatchNotifierTest.cheapOurLots_notSent_evenWithExpensiveOtherLot`; (3) без фильтра порога → красный `onlyFromThreshold`. Потом `./gradlew compileJava`.

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/productwatch/OurLots.java src/main/java/com/vladoose/nir/service/productwatch/ProductSpecChecker.java \
  src/main/java/com/vladoose/nir/service/productwatch/ProductWatchNotifier.java src/test/java/com/vladoose/nir/productwatch/ProductSpecCheckerThresholdTest.java \
  src/test/java/com/vladoose/nir/productwatch/ProductSpecCheckerTest.java
git commit -m "feat(product-fit): ТЗ качаем только у тендеров, где наши лоты от порога суммы

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Telegram — «✅ Подходит наш товар»

**Files:**
- Modify: `service/productwatch/ProductWatchComposer.java` (фильтр уведомителя и режим STAR по `alert()` — задача 7)
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductWatchComposerTest.java` (новые случаи), `ProductFitNotifyTest.java` (новый)

**Interfaces:**
- Consumes: `ProductMatchLevel.alert()`, `ProductMatchQuery.FitSummary`, `ProductMatchQuery.metTail`.
- Produces: раздел «✅ Подходит наш товар» в `ProductWatchComposer.compose`; строка «ТЗ под X — наш аналог по ТЗ проходит» под строкой лота FITS.

- [ ] **Step 1: Тест составителя (красный)**

В `ProductWatchComposerTest` — импорт `com.vladoose.nir.entity.FitVerdict` и тест:

```java
    @Test
    void fitsSection_withMetTailAndForeignLine_sound() {
        ProductMatchQuery.LotLine lot = new ProductMatchQuery.LotLine(1, "Электрокоагулятор", 1, ProductMatchLevel.FITS, "Электрохирургия",
                "электрокоагулятор", null, null, null, List.of(),
                new ProductMatchQuery.FitSummary(FitVerdict.FITS, "ВЧ электрохирургический блок для аппарата ЭХВЧ-350-03-«ФОТЕК»",
                        "ehvch350", 9, 12, 2, null, "BOWA", "ACTIVE"));
        ProductWatchComposer.Card card = new ProductWatchComposer.Card(10, "17828560-1", "goszakup", null,
                "Приобретение электрокоагулятора для больниц", "КГП на ПХВ «Многопрофильная областная детская больница»",
                "Кызылординская область", new BigDecimal("15700000"), LocalDate.of(2026, 10, 22),
                LocalDateTime.of(2026, 10, 22, 8, 0), ProductMatchLevel.FITS, false, List.of(lot));
        ProductWatchComposer.Message m = ProductWatchComposer.compose(List.of(card), "https://ais.westmed.kz");
        assertThat(m.text()).startsWith("🏷 Наш товар в тендерах: 1")
                .contains("\n\n✅ Подходит наш товар\n")
                .contains("Электрокоагулятор — 1 шт. · подходит наш товар: ВЧ электрохирургический блок для аппарата ЭХВЧ-350-03-«ФОТЕК» — 9 из 12 требований (нет данных: 2)")
                .contains("\nТЗ под BOWA — наш аналог по ТЗ проходит")
                .doesNotContain("🏷 Наш вид изделия");
        assertThat(m.sound()).isTrue();
    }

    /** Порядок разделов: «★ Точно наше» → «✅ Подходит» → «🏷 Наш вид»; без ★ и FITS — тихо. */
    @Test
    void sectionsOrder_andTypeOnlyIsSilent() {
        ProductMatchQuery.LotLine fitsLot = new ProductMatchQuery.LotLine(2, "Аспиратор", 1, ProductMatchLevel.FITS, "Отсасыватели", "аспиратор",
                null, null, null, List.of(), new ProductMatchQuery.FitSummary(FitVerdict.FITS, "ОМХ-5/80", "omh", 5, 8, 3, null, null, "ACTIVE"));
        ProductWatchComposer.Card fits = new ProductWatchComposer.Card(11, "17000001-1", "goszakup", null, "Аспиратор", null, null, null,
                null, null, ProductMatchLevel.FITS, false, List.of(fitsLot));
        String text = ProductWatchComposer.compose(List.of(astana(ProductMatchLevel.TYPE, false), fits, astana(ProductMatchLevel.COPIED, false)),
                null).text();
        assertThat(text.indexOf("★ Точно наше")).isLessThan(text.indexOf("✅ Подходит наш товар"));
        assertThat(text.indexOf("✅ Подходит наш товар")).isLessThan(text.indexOf("🏷 Наш вид изделия"));
        assertThat(ProductWatchComposer.compose(List.of(astana(ProductMatchLevel.TYPE, false)), null).sound()).isFalse();
    }
```

- [ ] **Step 2: Тест уведомителя (красный)**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** «Только ★» (решение оператора 2026-10-20) включает «✓ подходит»; «подходит по ТЗ, но РУ истекло» — не шлём. */
@SpringBootTest
@Transactional
class ProductFitNotifyTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProductMatchQuery query;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;

    record Sent(String text, boolean silent) {}
    final List<Sent> sent = new ArrayList<>();
    static final TelegramSettings TG = new TelegramSettings(true, "http://127.0.0.1:1", "t:k", "-1", "52", "55");
    List<ProductGroup> groups;
    SiteProduct omh;
    ProductMatchService matchService;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        groups = ProductFixtures.seedGroups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        omh = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Отсасыватель медицинский хирургический ОМХ-5/80 «Аксион» от электрической сети, вакуумная система, ёмкость для сбора жидкости")
                .brand("Аксион").slug("omh").registrationCert("РК-1").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                        new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа")))).build());
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Снимок с РУ ОМХ в этом статусе. */
    void snapshot(RegStatus status) {
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of(omh),
                Map.of("РК-1", new RegistrationInfo("РК-1", status, LocalDate.of(2026, 6, 28), false, null, null, null, List.of())), Set.of());
        matchService = new ProductMatchService(tenders, matches, () -> snap);
    }

    /** Тендер с одним лотом: цена за единицу × количество, разобранное ТЗ, приём до 22.10.2026 13:00; отметки посчитаны. */
    Tender tender(String ext, String lotName, String spec, int qty, String unitPrice) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп медицинских изделий"); t.setPlatform(TenderPlatform.GOSZAKUP);
        LocalDateTime deadlineAt = LocalDateTime.of(2026, 10, 22, 13, 0);
        t.setDeadline(deadlineAt.toLocalDate()); t.setDeadlineAt(deadlineAt);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName); l.setQuantity(qty);
        l.setMaxCost(new BigDecimal(unitPrice)); l.setRequiredSpec(spec); l.setTechSpecStatus(TechSpecStatus.OK);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        return t;
    }

    ProductWatchNotifier notifier() {
        return new ProductWatchNotifier(matches, tenders, query, matchService, tm, TG, (text, silent) -> sent.add(new Sent(text, silent)),
                true, ProductNotifyPolicy.of("star", "1000000"), "https://ais.westmed.kz",
                Clock.fixed(Instant.parse("2026-10-21T05:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void fits_sentInStarMode_withSound() {
        snapshot(RegStatus.ACTIVE);
        Tender t = tender("FN-1", "Аспиратор", ProductFitEvaluateTest.ASPIRATOR_SPEC, 2, "900000");   // наши лоты — 1,8 млн
        notifier().afterRun(new ImportSummary());
        assertThat(sent).singleElement().satisfies(s -> {
            assertThat(s.text()).contains("✅ Подходит наш товар").contains("№ FN-1");
            assertThat(s.silent()).isFalse();
        });
        em.flush();
        em.clear();
        assertThat(tenders.findById(t.getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.FITS);
    }

    @Test
    void fitsNoRu_notSent() {
        snapshot(RegStatus.EXPIRED);
        tender("FN-2", "Аспиратор", ProductFitEvaluateTest.ASPIRATOR_SPEC, 2, "900000");
        notifier().afterRun(new ImportSummary());
        assertThat(sent).isEmpty();
    }

    @Test
    void fits_belowThreshold_notSent() {
        snapshot(RegStatus.ACTIVE);
        tender("FN-3", "Аспиратор", ProductFitEvaluateTest.ASPIRATOR_SPEC, 1, "900000");          // 900 тыс. — меньше 1 млн
        notifier().afterRun(new ImportSummary());
        assertThat(sent).isEmpty();
    }
}
```

- [ ] **Step 3: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.ProductWatchComposerTest' --tests 'com.vladoose.nir.productwatch.ProductFitNotifyTest'`
Expected: FAIL — нет раздела «✅ Подходит наш товар» (FITS сейчас попадает в «🏷 Наш вид изделия» по `!exact()`), нет строки «ТЗ под BOWA».

- [ ] **Step 4: Составитель**

`ProductWatchComposer` — javadoc класса: «…сверху «★ Точно наше», ниже «✅ Подходит наш товар» (сверка ТЗ, спека 2026-10-20-product-spec-fit §7) и «🏷 Наш вид изделия» … Со звуком, если есть ★ или «подходит»». `compose` целиком:

```java
    /** null — показывать нечего. publicUrl пустой — без ссылок. Порядок карточек внутри разделов — как пришли. */
    public static Message compose(List<Card> cards, String publicUrl) {
        if (cards.isEmpty()) return null;
        String base = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
        List<Card> exact = cards.stream().filter(c -> !c.upgrade() && c.level().exact()).toList();
        List<Card> fits = cards.stream().filter(c -> !c.upgrade() && c.level() == ProductMatchLevel.FITS).toList();
        List<Card> upgrades = cards.stream().filter(Card::upgrade).toList();
        List<Card> type = cards.stream().filter(c -> !c.upgrade() && c.level() == ProductMatchLevel.TYPE).toList();
        int fresh = exact.size() + fits.size() + type.size();
        StringBuilder out = new StringBuilder(fresh == 0
                ? "★ Уточнение: ТЗ под наш товар" + (upgrades.size() > 1 ? " — " + upgrades.size() : "")
                : "🏷 Наш товар в тендерах: " + fresh);
        String tail = tail(base);
        List<Card> shown = new ArrayList<>();
        section(out, fresh == 0 ? null : "★ Точно наше", exact, base, tail, shown);
        section(out, fresh == 0 ? null : "✅ Подходит наш товар", fits, base, tail, shown);
        section(out, fresh == 0 ? null : "★ Уточнение", upgrades, base, tail, shown);
        section(out, "🏷 Наш вид изделия", type, base, tail, shown);
        if (shown.size() < cards.size()) out.append("\n\n…и ещё ").append(cards.size() - shown.size()).append(tail);
        boolean sound = shown.stream().anyMatch(c -> c.level().alert());
        return new Message(out.toString(), sound, shown.stream().map(Card::tenderId).toList());
    }
```

В `block` — цикл по лотам целиком (ветка `FITS` в `switch` — задача 7; здесь — строка «ТЗ под X» под лотом FITS):

```java
        for (ProductMatchQuery.LotLine l : c.lots().subList(0, Math.min(SHOWN_LOTS, c.lots().size()))) {
            String lot = blank(TelegramText.oneLine(l.lotName(), 80), "лот") + (l.qty() == null ? "" : " — " + l.qty() + " шт.");
            String what = switch (l.level()) {
                case COPIED -> "ТЗ списано с нашего товара: "
                        + TelegramText.oneLine(ProductMatchQuery.shortName(l.productName() == null ? l.word() : l.productName()), 100)
                        + countTail(l.evidence());
                case NAMED -> "назван наш товар: " + TelegramText.oneLine(ProductMatchQuery.what(l), 100);
                case FITS -> "подходит наш товар: " + TelegramText.oneLine(ProductMatchQuery.shortName(
                        l.fit() == null ? l.word() : l.fit().productName()), 100) + ProductMatchQuery.metTail(l.fit());
                case TYPE -> l.similar() == null || l.similar().isEmpty() ? "" : "похоже: " + l.similar().stream().limit(2)
                        .map(x -> TelegramText.oneLine(ProductMatchQuery.shortName(x.name()), 60)
                                + (x.brand() == null || x.brand().isBlank() ? "" : " (" + TelegramText.oneLine(x.brand(), 30) + ")"))
                        .collect(Collectors.joining(", "));
            };
            String line = what.isEmpty() ? lot : lot + " · " + what;
            if (l.level() == ProductMatchLevel.FITS && l.fit() != null && l.fit().foreignBrand() != null) {
                line += "\nТЗ под " + TelegramText.oneLine(l.fit().foreignBrand(), 40) + " — наш аналог по ТЗ проходит";
            }
            parts.add(line);
        }
```

- [ ] **Step 5: Прогнать — зелёный (весь пакет); мутации**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.*' --tests 'com.vladoose.nir.service.tendernotify.*'`
Expected: PASS. Мутации: (1) `ProductNotifyPolicy` STAR по `exact()` → красный `fits_sentInStarMode_withSound`; (2) звук по `exact()` → красный `fitsSection…` (`sound`); (3) без строки «ТЗ под» → красный `fitsSection…`; (4) раздел «✅ Подходит» после «🏷 Наш вид» → красный `sectionsOrder…`. Потом `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/productwatch/ProductWatchComposer.java \
  src/test/java/com/vladoose/nir/productwatch/ProductWatchComposerTest.java src/test/java/com/vladoose/nir/productwatch/ProductFitNotifyTest.java
git commit -m "feat(product-fit): Telegram — раздел «✅ Подходит наш товар», «ТЗ под X», звук у «подходит»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: API — сверка у лота, «Требования ТЗ», «Наша продукция → Товары»

**Files:**
- Modify: `dto/response/LotProductMatchDto.java`, `service/productwatch/ProductMatchQuery.java` (`lotMatches`), `dto/response/TenderLotResponse.java`, `controller/TenderController.java` (`getLots`), `controller/ProductWatchController.java`
- Create: `dto/response/ProductCatalogResponse.java`, `service/productwatch/ProductCatalogQuery.java`
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductCatalogApiTest.java`

**Interfaces:**
- Produces:
  - `LotProductMatchDto(String groupName, String word, String level, String evidence, String productName, String productUrl, List<Similar> similar, String fit, String foreignBrand, List<Candidate> candidates)`; `record Candidate(long productId, String name, String url, String verdict, int met, int failed, int total, int unknown, String reason, String reasonShort, String regStatus, String regNumber, List<Row> rows)`; `record Row(String mark, String text, String ours, String source)`.
  - `TenderLotResponse.requirements` (`List<String>`) — у лотов с `techSpecStatus = OK`.
  - `GET /api/product-catalog` → `ProductCatalogResponse(boolean kz, OffsetDateTime checkedAt, List<Item> items)`; `record Item(long id, String name, String brand, String url, String regNumber, String regStatus, LocalDate regExpires, boolean regUnlimited, int attributesCount)`.

- [ ] **Step 1: Тест (красный)**

```java
package com.vladoose.nir.productwatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Сверка у лота и вкладка «Товары» через API (спека §11). */
@SpringBootTest
@Transactional
class ProductCatalogApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired SiteProductRepository products;
    @Autowired ProductRegistrationRepository registrations;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired EntityManager em;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        products.deleteAllInBatch();
        registrations.deleteAllInBatch();
        em.flush();
    }

    @AfterEach
    void clear() {
        MarketContext.clear();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void catalogList_regStatusAndAttributes() throws Exception {
        products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("ОМХ-5/80").brand("Аксион").slug("omh")
                .registrationCert("РК-МТ-5№022493").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность", "15 л/мин")))).build());
        products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("Педаль").brand("Фотек").slug("pedal")
                .syncedAt(OffsetDateTime.now()).build());
        registrations.save(ProductRegistration.builder().regNumber("РК-МТ-5№022493").status(RegStatus.EXPIRED)
                .expirationDate(LocalDate.of(2026, 6, 28)).unlimited(false).checkedAt(OffsetDateTime.now()).build());
        em.flush();
        mvc.perform(get("/api/product-catalog").header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kz").value(true))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.name == 'ОМХ-5/80')].regStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.items[?(@.name == 'ОМХ-5/80')].regExpires").value("2026-06-28"))
                .andExpect(jsonPath("$.items[?(@.name == 'ОМХ-5/80')].attributesCount").value(1))
                .andExpect(jsonPath("$.items[?(@.name == 'Педаль')].regStatus").value("NOT_SPECIFIED"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void rfMarket_empty() throws Exception {
        mvc.perform(get("/api/product-catalog").header("X-Market", "RF"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kz").value(false)).andExpect(jsonPath("$.items.length()").value(0));
    }

    /**
     * Лот с отметкой и разобранным ТЗ: вердикт, кандидат со строками и ссылкой, «Требования ТЗ»;
     * лот без разобранного ТЗ (описание площадки с числами) — без «Требований ТЗ» (список — только у разобранного ТЗ).
     */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void lotsCarryFitAndRequirements() throws Exception {
        List<ProductGroup> groups = ProductFixtures.seedGroups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        SiteProduct omh = products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Отсасыватель медицинский хирургический ОМХ-5/80 от электрической сети, вакуумная система, ёмкость для сбора жидкости")
                .brand("Аксион").slug("omh").registrationCert("РК-1").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                        new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа")))).build());
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of(omh),
                Map.of("РК-1", new RegistrationInfo("РК-1", RegStatus.ACTIVE, null, true, null, null, null, List.of())), Set.of());
        Tender t = new Tender();
        t.setTenderNumber("API-1"); t.setSourceExtId("API-1"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп"); t.setPlatform(TenderPlatform.GOSZAKUP); t.setSource(Source.PUBLIC_TENDER);
        TenderLot asp = new TenderLot(); asp.setTender(t); asp.setLotNumber(1); asp.setEquipName("Аспиратор");
        asp.setRequiredSpec(ProductFitEvaluateTest.ASPIRATOR_SPEC); asp.setTechSpecStatus(TechSpecStatus.OK);
        TenderLot couch = new TenderLot(); couch.setTender(t); couch.setLotNumber(2); couch.setEquipName("Кушетка");
        couch.setRequiredSpec("Кушетка смотровая: длина не менее 1900 мм; ширина не менее 600 мм; нагрузка не менее 150 кг.");
        t.getLots().add(asp);
        t.getLots().add(couch);
        tenders.save(t);
        em.flush();
        new ProductMatchService(tenders, matches, () -> snap).evaluate(t.getId(), ProductMatchSource.SEARCH);
        em.flush();
        String json = mvc.perform(get("/api/tenders/" + t.getId() + "/lots").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> lots = new ObjectMapper().readValue(json, new TypeReference<>() {});
        Map<String, Object> a = lots.stream().filter(l -> "Аспиратор".equals(l.get("equipName"))).findFirst().orElseThrow();
        Map<String, Object> c = lots.stream().filter(l -> "Кушетка".equals(l.get("equipName"))).findFirst().orElseThrow();
        assertThat((List<?>) a.get("requirements")).hasSizeGreaterThan(2);
        assertThat((List<?>) c.get("requirements")).as("описание площадки — не разобранное ТЗ").isEmpty();
        Map<?, ?> m = (Map<?, ?>) ((List<?>) a.get("productMatches")).get(0);
        assertThat(m.get("fit")).isEqualTo("FITS");
        Map<?, ?> best = (Map<?, ?>) ((List<?>) m.get("candidates")).get(0);
        assertThat((String) best.get("name")).startsWith("Отсасыватель");
        assertThat((String) best.get("url")).endsWith("/product/omh");
        assertThat((List<?>) best.get("rows")).isNotEmpty();
        assertThat(((Map<?, ?>) ((List<?>) best.get("rows")).get(0)).get("mark")).isNotNull();
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.ProductCatalogApiTest'`
Expected: FAIL — 404 на `/api/product-catalog`, нет `requirements` / `fit` в ответе лотов.

- [ ] **Step 3: DTO и запрос**

`LotProductMatchDto`:

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/**
 * Отметка «наш товар» лота для карточки тендера (спеки 2026-10-19-product-watch §12.3, 2026-10-20-product-spec-fit §8.1):
 * ссылки — на товары сайта; сверка — лучший товар и варианты со строками.
 */
public record LotProductMatchDto(String groupName, String word, String level, String evidence, String productName, String productUrl,
                                 List<Similar> similar, String fit, String foreignBrand, List<Candidate> candidates) {
    public record Similar(String name, String brand, String url) {}
    public record Candidate(long productId, String name, String url, String verdict, int met, int failed, int total, int unknown,
                            String reason, String reasonShort, String regStatus, String regNumber, List<Row> rows) {}
    public record Row(String mark, String text, String ours, String source) {}
}
```

`ProductMatchQuery.lotMatches` — кандидаты лежат в строке отметки, а `LotLine` её не несёт: строки одного тендера дочитываются картой «лот | группа»:

```java
    /** Отметки лотов одного тендера для карточки (спеки §12.3 / 2026-10-20 §8.1): товар, похожие и сверка — ссылками на сайт. */
    @Transactional(readOnly = true)
    public Map<Long, List<LotProductMatchDto>> lotMatches(long tenderId) {
        Summary s = summaries(List.of(tenderId)).get(tenderId);
        if (s == null) return Map.of();
        List<TenderLotProductMatch> rows = matches.findByTenderId(tenderId);
        Map<Long, String> groupNames = groups.findAllById(rows.stream().map(TenderLotProductMatch::getGroupId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProductGroup::getId, ProductGroup::getName));
        Map<String, TenderLotProductMatch> byKey = new HashMap<>();
        for (TenderLotProductMatch m : rows) byKey.put(m.getLotId() + "|" + groupNames.get(m.getGroupId()), m);
        Map<Long, List<LotProductMatchDto>> out = new HashMap<>();
        for (LotLine l : s.lots()) {
            List<LotProductMatchDto.Similar> sim = l.similar() == null ? List.of() : l.similar().stream()
                    .map(x -> new LotProductMatchDto.Similar(shortName(x.name()), x.brand(), url(x.slug())))
                    .toList();
            TenderLotProductMatch m = byKey.get(l.lotId() + "|" + l.groupName());
            out.computeIfAbsent(l.lotId(), k -> new ArrayList<>()).add(new LotProductMatchDto(l.groupName(), l.word(), l.level().name(),
                    l.evidence(), l.productName() == null ? null : shortName(l.productName()), url(l.productSlug()), sim,
                    m == null || m.getFit() == null ? null : m.getFit().name(), m == null ? null : m.getForeignBrand(), candidates(m)));
        }
        return out;
    }

    private List<LotProductMatchDto.Candidate> candidates(TenderLotProductMatch m) {
        if (m == null || m.getFitCandidates() == null) return List.of();
        return m.getFitCandidates().stream().map(c -> new LotProductMatchDto.Candidate(c.productId(), c.name(), url(c.slug()),
                c.verdict() == null ? null : c.verdict().name(), c.met(), c.failed(), c.total(), c.unknown(), c.reason(), c.reasonShort(),
                c.regStatus(), c.regNumber(), c.rows() == null ? List.of() : c.rows().stream()
                        .map(r -> new LotProductMatchDto.Row(r.mark() == null ? null : r.mark().name(), r.text(), r.ours(), r.source())).toList()))
                .toList();
    }
```

`TenderLotResponse`: `private List<String> requirements = new ArrayList<>();`. `TenderController.getLots` — после `productMatches` (текст — тем же помощником, что сверка, задача 7):

```java
        for (TenderLotResponse l : lots) {
            if (l.getTechSpecStatus() == TechSpecStatus.OK && l.getRequiredSpec() != null) {
                l.setRequirements(SpecRequirements.extract(ProductMatchService.fitText(l.getRequiredSpec(), null)).stream()
                        .map(SpecRequirement::text).toList());
            }
        }
```

`ProductCatalogResponse`:

```java
package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Вкладка «Наша продукция → Товары» (спека §8.2): товары каталога рынка KZ с РУ. */
public record ProductCatalogResponse(boolean kz, OffsetDateTime checkedAt, List<Item> items) {
    public record Item(long id, String name, String brand, String url, String regNumber, String regStatus, LocalDate regExpires,
                       boolean regUnlimited, int attributesCount) {}
}
```

`ProductCatalogQuery`:

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ProductCatalogResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.ProductRegistrationRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Товары каталога с РУ — вкладка «Товары» (спека §8.2); на РФ — пусто. */
@Service
public class ProductCatalogQuery {

    private final SiteProductRepository products;
    private final ProductRegistrationRepository registrations;
    private final String siteUrl;

    public ProductCatalogQuery(SiteProductRepository products, ProductRegistrationRepository registrations,
                               @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.products = products;
        this.registrations = registrations;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public ProductCatalogResponse list() {
        if (MarketContext.get() != Market.KZ) return new ProductCatalogResponse(false, null, List.of());
        Map<String, ProductRegistration> regs = new HashMap<>();
        registrations.findAll().forEach(r -> regs.put(r.getRegNumber(), r));
        List<ProductCatalogResponse.Item> items = products.findByMarketAndRemovedAtIsNull(Market.KZ).stream()
                .sorted(Comparator.comparing((SiteProduct p) -> Objects.toString(p.getBrand(), "")).thenComparing(SiteProduct::getName))
                .map(p -> {
                    String reg = RegNumbers.normalize(p.getRegistrationCert());
                    ProductRegistration r = reg == null ? null : regs.get(reg);
                    RegStatus st = reg == null ? RegStatus.NOT_SPECIFIED : r == null ? RegStatus.UNKNOWN : r.getStatus();
                    return new ProductCatalogResponse.Item(p.getId(), p.getName(), p.getBrand(),
                            p.getSlug() == null ? null : WestmedLeadMapper.productUrl(siteUrl, p.getSlug()), reg, st.name(),
                            r == null ? null : r.getExpirationDate(), r != null && Boolean.TRUE.equals(r.getUnlimited()),
                            p.getAttributes() == null ? 0 : p.getAttributes().size());
                }).toList();
        return new ProductCatalogResponse(true, registrations.lastChecked(), items);
    }
}
```

`ProductWatchController` — конструктор берёт `ProductCatalogQuery catalogQuery`:

```java
    @GetMapping("/api/product-catalog")
    public ProductCatalogResponse catalog() {
        return catalogQuery.list();
    }
```

- [ ] **Step 4: Прогнать — зелёный; мутации**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.*'`
Expected: PASS. Мутации: (1) статус без `NOT_SPECIFIED` (номера нет — всё равно `UNKNOWN`) → красный `catalogList…` («Педаль»); (2) «Требования ТЗ» у всех лотов, а не только у `OK` → красный `lotsCarryFitAndRequirements` («Кушетка»); (3) в `lotMatches` без `candidates(m)` (пустой список) → красный `lotsCarryFitAndRequirements`. Потом `./gradlew compileJava`.

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/vladoose/nir/dto/response/LotProductMatchDto.java src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java \
  src/main/java/com/vladoose/nir/dto/response/ProductCatalogResponse.java src/main/java/com/vladoose/nir/service/productwatch/ProductMatchQuery.java \
  src/main/java/com/vladoose/nir/service/productwatch/ProductCatalogQuery.java src/main/java/com/vladoose/nir/controller/TenderController.java \
  src/main/java/com/vladoose/nir/controller/ProductWatchController.java src/test/java/com/vladoose/nir/productwatch/ProductCatalogApiTest.java
git commit -m "feat(product-fit): API — сверка у лота, «Требования ТЗ», вкладка «Товары»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Фронт — вердикт у лота и «Сверка с нашим товаром»

**Files:**
- Create: `frontend/src/app/pages/tenders/lot-fit.component.ts`
- Modify: `frontend/src/app/shared/product-watch.ts`, `frontend/src/app/pages/tenders/tender-lots.component.ts`, `frontend/src/app/pages/tenders/tenders-filters.component.ts` (подпись варианта фильтра); чип FITS в списке тендеров и в карточке — без правки (`pw-exact` ставится по `level !== 'TYPE'`, текст — `ourProduct.label` с сервера, задача 7)

**Interfaces:**
- Consumes: ответ `GET /api/tenders/{id}/lots` (задача 10).
- Produces: `LotFitComponent` (`app-lot-fit`, `@Input() match: LotProductMatch | null`, `@Input() requirements: string[] | null`); типы `FitVerdict`, `FitMark`, `FitCandidateView`, `FitRowView`; `fitChip(m: LotProductMatch): { text: string; tone: 'success' | 'warn' | 'muted' | 'accent' }` в `shared/product-watch.ts`.

- [ ] **Step 1: Типы и подпись чипа**

`shared/product-watch.ts`:

```ts
export type MatchLevel = 'TYPE' | 'FITS' | 'NAMED' | 'COPIED';
export type FitVerdict = 'FITS' | 'FITS_NO_RU' | 'PARTIAL' | 'NOT_FIT' | 'NO_REQUIREMENTS';
export type FitMark = 'OK' | 'FAIL' | 'DIFF' | 'UNKNOWN';
export interface FitRowView { mark: FitMark; text: string; ours: string | null; source: string | null; }
export interface FitCandidateView {
  productId: number; name: string; url: string | null; verdict: FitVerdict; met: number; failed: number; total: number;
  unknown: number; reason: string | null; reasonShort: string | null; regStatus: string; regNumber: string | null; rows: FitRowView[];
}

/** Отметка «наш товар» лота в карточке тендера (спеки §12.3 / 2026-10-20 §8.1). */
export interface LotProductMatch {
  groupName: string; word: string; level: MatchLevel; evidence: string | null; productName: string | null; productUrl: string | null;
  similar: { name: string; brand: string | null; url: string | null }[];
  fit: FitVerdict | null; foreignBrand: string | null; candidates: FitCandidateView[];
}

export const OUR_LABELS: Record<string, string> = { ANY: 'Любой наш вид', EXACT: '★ Подходит или точно наше' };

/** Текст РУ для «подходит по ТЗ, но …» (как RegStatus.noRuText сервера). */
export const NO_RU: Record<string, string> = {
  EXPIRED: 'РУ истекло', NOT_FOUND: 'РУ не найдено в реестре', NOT_SPECIFIED: 'РУ не указано на сайте', UNKNOWN: 'РУ не проверено',
};

/** Название товара без хвоста «. Набор …» и не длиннее 60. */
export function shortProduct(name: string | null | undefined): string {
  if (!name) return '';
  const dot = name.indexOf('. ');
  const s = dot > 0 ? name.slice(0, dot) : name;
  return s.length <= 60 ? s : s.slice(0, 59) + '…';
}

/** Чип отметки лота (спека §8.1): ★ — как раньше, иначе вердикт сверки, нет сверки — «🏷 группа». */
export function fitChip(m: LotProductMatch): { text: string; tone: 'success' | 'warn' | 'muted' | 'accent' } {
  if (m.level === 'COPIED') return { text: '★ ТЗ списано с нашего', tone: 'success' };
  if (m.level === 'NAMED') return { text: '★ назван наш товар', tone: 'success' };
  const b = m.candidates?.[0];
  switch (m.fit) {
    case 'FITS': return { text: `✓ подходит: ${shortProduct(b?.name)} · ${b?.met} из ${b?.total}`, tone: 'success' };
    case 'FITS_NO_RU': return { text: `подходит по ТЗ, ${NO_RU[b?.regStatus ?? 'UNKNOWN'] ?? 'РУ не проверено'}`, tone: 'warn' };
    case 'PARTIAL': return { text: '◐ похоже — проверьте', tone: 'warn' };
    case 'NOT_FIT': return { text: `✗ не подходит: ${b?.reasonShort ?? 'см. сверку'}`, tone: 'muted' };
    default: return { text: '🏷 ' + m.groupName, tone: 'accent' };
  }
}
```

- [ ] **Step 2: Компонент `LotFitComponent`**

```ts
import { Component, Input, OnChanges } from '@angular/core';
import { NgClass, NgFor, NgIf } from '@angular/common';
import { NO_RU, shortProduct } from '../../shared/product-watch';
import type { FitCandidateView, FitMark, LotProductMatch } from '../../shared/product-watch';

/**
 * «Сверка с нашим товаром» и «Требования ТЗ» у лота (спека 2026-10-20-product-spec-fit §8.1): одна колонка и на телефоне;
 * сначала ✗, потом ≈, ✓, «нет данных» (порядок строк — с сервера); «ещё: …» переключает сравнение без запроса.
 */
@Component({
  selector: 'app-lot-fit',
  standalone: true,
  imports: [NgIf, NgFor, NgClass],
  template: `
    <section class="lf" *ngIf="match && current as c">
      <div class="lf-head">
        <span class="lf-title">Сверка с нашим товаром</span>
        <span class="lf-foreign" *ngIf="match.foreignBrand">ТЗ под {{ match.foreignBrand }}</span>
      </div>
      <div class="lf-product">
        <a *ngIf="c.url; else plainName" [href]="c.url" target="_blank" rel="noopener">{{ c.name }} ↗</a>
        <ng-template #plainName><span>{{ c.name }}</span></ng-template>
        <span class="lf-reg" [ngClass]="c.regStatus === 'ACTIVE' ? 'lf-reg-ok' : 'lf-reg-bad'">{{ regText(c) }}</span>
      </div>
      <div class="lf-count">✓ {{ c.met }} · ✗ {{ c.failed }} · ≈ {{ diff(c) }} · нет данных {{ c.unknown }}</div>
      <div class="lf-alts" *ngIf="match.candidates.length > 1">
        <span class="lf-alts-label">ещё:</span>
        <ng-container *ngFor="let a of match.candidates; let i = index">
          <button type="button" class="lf-alt" *ngIf="i !== index" (click)="index = i"
                  [title]="'Сравнить ТЗ с ' + a.name">{{ short(a.name) }}{{ i === 0 ? ' (лучший)' : '' }}</button>
        </ng-container>
      </div>
      <ol class="lf-rows">
        <li *ngFor="let r of c.rows" [ngClass]="'lf-' + r.mark">
          <span class="lf-mark" [attr.aria-label]="markLabel(r.mark)">{{ markIcon(r.mark) }}</span>
          <span class="lf-text">{{ r.text }}
            <span class="lf-ours">{{ r.ours ? 'у нас: ' + r.ours + (r.source ? ' (' + r.source + ')' : '') : 'нет данных' }}</span>
          </span>
        </li>
      </ol>
    </section>
    <section class="lf" *ngIf="!match && requirements?.length">
      <div class="lf-head"><span class="lf-title">Требования ТЗ: {{ requirements!.length }}</span></div>
      <ol class="lf-rows lf-plain"><li *ngFor="let r of requirements"><span class="lf-text">{{ r }}</span></li></ol>
    </section>
  `,
  styles: [`
    .lf { margin: 8px 0; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    .lf-head { display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; }
    .lf-title { font-weight: 600; color: var(--text); }
    .lf-foreign { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--text-muted); }
    .lf-product { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; overflow-wrap: anywhere; }
    .lf-product a { color: var(--accent); }
    .lf-reg { font-size: 12px; padding: 1px 8px; border-radius: 999px; }
    .lf-reg-ok { color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .lf-reg-bad { color: var(--warn-text); background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .lf-count { margin-top: 4px; font-size: 13px; color: var(--text-muted); }
    .lf-alts { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 12px; color: var(--text-muted); }
    .lf-alt { font-size: 12px; padding: 2px 10px; border-radius: 999px; border: 1px solid var(--border); background: var(--surface-2);
      color: var(--text); cursor: pointer; }
    .lf-rows { list-style: none; margin: 8px 0 0; padding: 0; display: grid; gap: 6px; }
    .lf-rows li { display: grid; grid-template-columns: 22px 1fr; gap: 6px; align-items: start; }
    .lf-plain li { grid-template-columns: 1fr; }
    .lf-mark { font-weight: 700; text-align: center; }
    .lf-OK .lf-mark { color: var(--success-text); }
    .lf-FAIL .lf-mark { color: var(--danger-text); }
    .lf-DIFF .lf-mark { color: var(--warn-text); }
    .lf-UNKNOWN .lf-mark { color: var(--text-muted); }
    .lf-text { color: var(--text); overflow-wrap: anywhere; }
    .lf-ours { display: block; font-size: 12px; color: var(--text-muted); }
    @media (max-width: 900px) {
      .lf-alt { min-height: 40px; }
    }
  `]
})
export class LotFitComponent implements OnChanges {
  @Input() match: LotProductMatch | null = null;
  @Input() requirements: string[] | null = null;
  index = 0;

  get current(): FitCandidateView | null { return this.match?.candidates?.[this.index] ?? null; }

  ngOnChanges(): void { this.index = 0; }

  short(name: string): string { return shortProduct(name); }
  diff(c: FitCandidateView): number { return c.rows.filter(r => r.mark === 'DIFF').length; }
  regText(c: FitCandidateView): string {
    return c.regStatus === 'ACTIVE' ? 'РУ действует' + (c.regNumber ? ' · ' + c.regNumber : '') : (NO_RU[c.regStatus] ?? 'РУ не проверено');
  }
  markIcon(m: FitMark): string { return m === 'OK' ? '✓' : m === 'FAIL' ? '✗' : m === 'DIFF' ? '≈' : '?'; }
  markLabel(m: FitMark): string {
    return m === 'OK' ? 'проходит' : m === 'FAIL' ? 'не проходит' : m === 'DIFF' ? 'отличается' : 'нет данных';
  }
}
```

- [ ] **Step 3: Карточка лота**

`tender-lots.component.ts`:
- `imports` компонента — `LotFitComponent`;
- чип отметки (блок `<div class="lot-ours" *ngFor="let m of l.productMatches || []">`): вместо `<span class="stage-chip" [class.stage-success]=… [class.stage-accent]=…>{{ m.level === 'TYPE' ? … }}</span>` — `<span class="stage-chip lot-ours-chip" [class]="'stage-' + chip(m).tone">{{ chip(m).text }}</span><span class="stage-chip stage-muted" *ngIf="m.foreignBrand">ТЗ под {{ m.foreignBrand }}</span>` (`[class]` рядом со статическими классами — как у чипа стадии в той же разметке, `NgClass` не нужен); «похоже: …» и «ТЗ недоступно» — только при `!m.fit || m.fit === 'NO_REQUIREMENTS'`; метод `chip(m: LotProductMatch) { return fitChip(m); }` (импорт `fitChip` из `shared/product-watch`);
- стили: `.stage-chip.lot-ours-chip { white-space: normal; }` (двойной класс — сильнее `.stage-chip { white-space: nowrap }` независимо от порядка: длинный «✓ подходит: …» переносится, а не вылезает за карточку на 390 px) и `.lot-fit-ph { min-height: 1px; }` (заглушке `@defer (on viewport)` нужен бокс, за которым следить);
- в теле лота, перед `<div class="lot-spec" *ngIf="l.requiredSpec">`:

```html
        @defer (on viewport) {
          <app-lot-fit *ngFor="let m of fitMatches(l)" [match]="m"></app-lot-fit>
          <app-lot-fit *ngIf="!fitMatches(l).length && l.requirements?.length" [requirements]="l.requirements"></app-lot-fit>
        } @placeholder {
          <div class="lot-fit-ph"></div>
        }
```

- метод `fitMatches(l: any): LotProductMatch[] { return (l.productMatches || []).filter((m: LotProductMatch) => m.candidates?.length); }`;
- кнопка свёрнутого текста ТЗ остаётся, подпись — «Полный текст ТЗ» вместо «Техническая спецификация».

`tenders-filters.component.ts` — вариант фильтра «Наш товар» в обеих раскладках (ряд фильтров и лист на телефоне): `<option value="EXACT">★ Точно наше</option>` → `<option value="EXACT">{{ ourLabels['EXACT'] }}</option>` (поле `ourLabels = OUR_LABELS` — подпись из одного места, `shared/product-watch`; чип активного фильтра уже берёт `OUR_LABELS`).

- [ ] **Step 4: Сборка и бюджет**

Run: `cd frontend && npm run build`
Expected: сборка без ошибок; `LotFitComponent` — отдельным чанком (`@defer`); начальный бандл ≤ 1,45 МБ (сейчас 1,42); предупреждения бюджета стилей компонентов — нет. Затем `cd /Users/vlad/IdeaProjects/AIS`.

- [ ] **Step 5: Живьём (кратко; полная проверка — задача 16)**

Бэкенд локально + `npm start`, карточка тендера с лотом «Аспиратор» из теста-набора (или свой тестовый тендер из задачи 16): чип «✓ подходит …», блок сверки, «ещё: …», строки ✗ сверху; 390 px — одна колонка, длинный чип переносится; тёмная тема.

- [ ] **Step 6: Коммит**

```bash
git add frontend/src/app/shared/product-watch.ts frontend/src/app/pages/tenders/lot-fit.component.ts frontend/src/app/pages/tenders/tender-lots.component.ts \
  frontend/src/app/pages/tenders/tenders-filters.component.ts
git commit -m "feat(product-fit): фронт — вердикт у лота, «Сверка с нашим товаром», «Требования ТЗ» списком

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: Фронт — «Наша продукция → Товары»

**Files:**
- Create: `frontend/src/app/pages/product-groups/product-catalog.component.ts`
- Modify: `frontend/src/app/pages/product-groups/product-groups.component.ts` (вкладки), `frontend/src/app/services/api.service.ts`, `frontend/src/app/shared/product-watch.ts`

**Interfaces:**
- Consumes: `GET /api/product-catalog`, `POST /api/site-catalog/sync` (`ApiService.syncSiteCatalog()` — уже есть).
- Produces: `ProductCatalogItem`, `ProductCatalogState` (типы), `ApiService.getProductCatalog()`.

- [ ] **Step 1: Типы и API**

```ts
export interface ProductCatalogItem {
  id: number; name: string; brand: string | null; url: string | null; regNumber: string | null; regStatus: string;
  regExpires: string | null; regUnlimited: boolean; attributesCount: number;
}
export interface ProductCatalogState { kz: boolean; checkedAt: string | null; items: ProductCatalogItem[]; }
```

`ApiService` (типы — `import type`):

```ts
  getProductCatalog(): Observable<ProductCatalogState> {
    return this.http.get<ProductCatalogState>(`${this.base}/product-catalog`);
  }
```

- [ ] **Step 2: Вкладка**

`product-catalog.component.ts` — standalone, `selector: 'app-product-catalog'`: загрузка в `ngOnInit`, фильтры-чипы «Все / Без характеристик / РУ истекло или не найдено» (`filter: 'ALL' | 'NO_ATTR' | 'BAD_RU'`), строка «Проверено РУ: ДД.ММ ЧЧ:ММ» (своя функция формата, без `DatePipe` — §12 «Наша продукция — фронт»), у админа — «Обновить» (`api.syncSiteCatalog()` → тост «Каталог и РУ обновляются — через минуту обновите вкладку»). Строка товара (карточкой на ≤ 900 px): название ссылкой ↗, бренд; РУ — чип: `ACTIVE` «действует» + «бессрочно» / «до ДД.ММ.ГГГГ», `EXPIRED` «истекло ДД.ММ.ГГГГ» (warn), `NOT_FOUND` «не найдено» (warn), `NOT_SPECIFIED` «не указано» (muted), `UNKNOWN` «не проверено» (muted); «характеристик: N» или «нет характеристик» (warn). Классы — с приставкой `pc-`. РФ — «Только для Казахстана».

`product-groups.component.ts`: над содержимым — две кнопки-вкладки «Группы» / «Товары» (`tab: 'groups' | 'products'`, по умолчанию «Группы»; выбор помнит `localStorage['ais.pg.tab']` в try/catch); «Товары» — `<app-product-catalog *ngIf="tab === 'products'"></app-product-catalog>`; прежняя разметка групп — под `*ngIf="tab === 'groups'"`.

- [ ] **Step 3: Сборка**

Run: `cd frontend && npm run build` — без ошибок, страница «Наша продукция» — ленивый чанк (как была). Затем `cd /Users/vlad/IdeaProjects/AIS`.

- [ ] **Step 4: Коммит**

```bash
git add frontend/src/app/pages/product-groups/ frontend/src/app/services/api.service.ts frontend/src/app/shared/product-watch.ts
git commit -m "feat(product-fit): фронт — «Наша продукция → Товары» с РУ и характеристиками

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: «📋 Для ИИ» — сервер и фронт

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/productwatch/ProductAiBrief.java`, `dto/response/AiBriefResponse.java`, `dto/request/AiBriefRequest.java`, `frontend/src/app/shared/ai-brief-button.component.ts`
- Modify: `controller/TenderController.java`, `frontend/src/app/services/api.service.ts`, `frontend/src/app/pages/tenders/tenders.component.ts`
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductAiBriefTest.java`

**Interfaces:**
- Produces:
  - `String ProductAiBrief.forTender(long tenderId)` (гард — `TenderService.findPublic`, 404 чужого рынка и вида); `AiBriefResponse ProductAiBrief.forTenders(List<Long> ids)` (`record AiBriefResponse(String text, int tenders, int total)`; чужие / несуществующие id пропускаются).
  - `GET /api/tenders/{id}/ai-brief` → `text/plain;charset=UTF-8`; `POST /api/tenders/ai-brief` `{ids}` → `AiBriefResponse`. Видят все вошедшие.
  - `ProductAiBrief.PROMPT` (дословно спека §9.2), `MAX_TENDERS = 15`, `LIST_SPEC_CHARS = 4000`, `MAX_TEXT = 120_000`.
  - Фронт: `app-ai-brief-button` (`@Input() tenderId?: number`, `@Input() ids?: number[]`, `@Input() label = '📋 Для ИИ'`).

- [ ] **Step 1: Тест (красный)**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.AiBriefResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Текст «Для ИИ» (спека §9): запрос, тендер, наш лот, требования, наш товар с РУ; пределы; гард рынка. */
@SpringBootTest
@Transactional
class ProductAiBriefTest {

    /** Длинный хвост ТЗ: в общем списке режется до 4000 знаков на лот, у одного тендера — целиком. */
    static final String TAIL = "очень длинное требование ".repeat(400);

    @Autowired ProductAiBrief brief;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProductRegistrationRepository registrations;
    @Autowired EntityManager em;
    ProductMatchService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        registrations.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.seedGroups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        SiteProduct omh = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Отсасыватель медицинский хирургический ОМХ-5/80 от электрической сети, вакуумная система, ёмкость для сбора жидкости")
                .brand("Аксион").slug("omh").registrationCert("РК-1").syncedAt(OffsetDateTime.now())
                .attributes(new ArrayList<>(List.of(new SiteProductAttribute("Производительность по воздуху", "не менее 15 л/мин"),
                        new SiteProductAttribute("Устанавливаемое разрежение", "от -5 до -80 кПа")))).build());
        registrations.save(ProductRegistration.builder().regNumber("РК-1").status(RegStatus.ACTIVE).unlimited(true)
                .purpose("Удаление жидкостей из полостей").checkedAt(OffsetDateTime.now()).build());
        em.flush();
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of(omh),
                Map.of("РК-1", new RegistrationInfo("РК-1", RegStatus.ACTIVE, null, true, null, null, null, List.of())), Set.of());
        service = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    /** Тендер KZ goszakup: `lots` лотов «Аспиратор» по 2 × 900 000 ₸ с разобранным ТЗ; отметки посчитаны. */
    Tender ourTender(String number, String spec, int lots) {
        Tender t = Tender.builder().tenderNumber(number).sourceExtId(number).status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.KZ).currency("KZT").platform(TenderPlatform.GOSZAKUP).description("Закуп медицинских изделий").build();
        for (int i = 1; i <= lots; i++) {
            TenderLot l = new TenderLot();
            l.setTender(t); l.setLotNumber(i); l.setSourceLotCode(number.replace("-", "") + "-ЗЦП" + i); l.setEquipName("Аспиратор");
            l.setQuantity(2); l.setMaxCost(new BigDecimal("900000")); l.setRequiredSpec(spec); l.setTechSpecStatus(TechSpecStatus.OK);
            t.getLots().add(l);
        }
        tenders.save(t);
        em.flush();
        service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        em.flush();
        return t;
    }

    @Test
    void singleTender_fullText_wholeSpec() {
        Tender t = ourTender("17000001-1", ProductFitEvaluateTest.ASPIRATOR_SPEC + " " + TAIL, 1);
        String text = brief.forTender(t.getId());
        assertThat(text).startsWith("Ты помогаешь компании West-Med")
                .contains("Тендер № … — Лот № … — Требуется: …")          // формат ответа из запроса
                .contains("=== Тендер № 17000001-1 (goszakup) — https://goszakup.gov.kz/ru/announce/index/17000001")
                .contains("--- Лот № 170000011-ЗЦП1: Аспиратор — 2 шт., до 900")
                .contains("Сверка АИС: ✓ подходит наш товар: Отсасыватель")
                .contains("Требования ТЗ (")
                .contains("производительность отсасывания")
                .contains("Наши товары-кандидаты:")
                .contains("* Отсасыватель медицинский хирургический ОМХ-5/80")
                .contains("/product/omh")
                .contains("РУ РК-1 — действует бессрочно")
                .contains("Характеристики (сайт): Производительность по воздуху: не менее 15 л/мин")
                .contains("Из карточки РУ: назначение — Удаление жидкостей из полостей")
                .contains("очень длинное требование ".repeat(300));            // у одного тендера ТЗ — целиком
    }

    @Test
    void list_capsAtFifteen_skipsOtherMarket_cutsLotSpec() {
        Tender rf = tenders.save(Tender.builder().tenderNumber("ZZ-AI-RF").status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.RF).currency("RUB").build());
        List<Long> ids = new ArrayList<>(List.of(rf.getId()));
        for (int i = 0; i < 16; i++) ids.add(ourTender("170001" + (10 + i) + "-1", ProductFitEvaluateTest.ASPIRATOR_SPEC + " " + TAIL, 1).getId());
        AiBriefResponse r = brief.forTenders(ids);
        assertThat(r.total()).isEqualTo(17);
        assertThat(r.tenders()).isEqualTo(ProductAiBrief.MAX_TENDERS);
        assertThat(r.text()).doesNotContain("ZZ-AI-RF").contains("17000110-1").contains("17000124-1").doesNotContain("17000125-1");
        assertThat(r.text()).as("ТЗ лота в общем списке — до 4000 знаков").doesNotContain("очень длинное требование ".repeat(200));
    }

    /** Весь текст — не длиннее 120 000 знаков, обрезка подписана. */
    @Test
    void list_totalCut() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) ids.add(ourTender("1700020" + i + "-1", ProductFitEvaluateTest.ASPIRATOR_SPEC + " " + TAIL, 6).getId());
        AiBriefResponse r = brief.forTenders(ids);
        assertThat(r.text().length()).isLessThanOrEqualTo(ProductAiBrief.MAX_TEXT);
        assertThat(r.text()).endsWith("…обрезано");
    }

    @Test
    void otherMarketOrPrivate_404() {
        Tender rf = tenders.save(Tender.builder().tenderNumber("ZZ-AI-RF2").status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.RF).currency("RUB").build());
        Tender pr = tenders.save(Tender.builder().tenderNumber("ZZ-AI-ЧЗ").status("NEW").source(Source.PRIVATE_REQUEST)
                .market(Market.KZ).currency("KZT").build());
        assertThatThrownBy(() -> brief.forTender(rf.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> brief.forTender(pr.getId())).isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Прогнать — красный**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.ProductAiBriefTest'`
Expected: FAIL — компиляция (`ProductAiBrief` нет).

- [ ] **Step 3: Сервис**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.dto.response.AiBriefResponse;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.RegistryMatchService;
import com.vladoose.nir.service.TenderService;
import com.vladoose.nir.service.productwatch.fit.*;
import com.vladoose.nir.util.DocFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * «📋 Для ИИ» (спека 2026-10-20-product-spec-fit §9): готовый текст по тендерам с нашими лотами для Claude / Gemini —
 * запрос, тендер, наши лоты (сверка, требования, текст ТЗ), наши товары с РУ и характеристиками, похожие записи реестра.
 * Живых запросов в ndda.kz нет: характеристики реестра — только уже загруженные.
 */
@Service
public class ProductAiBrief {

    public static final int MAX_TENDERS = 15;
    public static final int LIST_SPEC_CHARS = 4000;
    public static final int MAX_TEXT = 120_000;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static final String PROMPT = """
            Ты помогаешь компании West-Med (Казахстан, поставка медицинского оборудования) разобрать государственные тендеры.
            Ниже — тендеры, в которых, возможно, есть наше оборудование: по каждому лоту — требования технической спецификации (ТЗ),
            наша автоматическая сверка, наши товары-кандидаты с характеристиками и регистрацией в РК и похожие изделия из реестра
            НЦЭЛС РК.

            Для каждого лота:
            1. Коротко — какое оборудование требуется и его ключевые параметры.
            2. Под какую модель и производителя похоже написано ТЗ; не уверен — «не определено».
            3. Подходит ли наш товар-кандидат: по каждому ключевому требованию — выполняется / не выполняется / нет данных; итог.
            4. Есть ли у подходящего изделия действующая регистрация в РК (реестр НЦЭЛС: https://oldregister.ndda.kz).
            Если можешь искать в интернете — проверь характеристики моделей по официальным источникам и дай ссылки.
            Ничего не выдумывай: нет данных — так и пиши.

            Ответ — списком в формате:
            Тендер № … — Лот № … — Требуется: … — ТЗ похоже на: … — Наш товар: … (подходит / не подходит: причина / нет данных) — РУ в РК: …
            """;

    private final TenderService tenderService;
    private final TenderLotProductMatchRepository matches;
    private final SiteProductRepository products;
    private final ProductRegistrationRepository registrations;
    private final MedRegistryRepository registry;
    private final RegistryMatchService registryMatch;
    private final String siteUrl;

    public ProductAiBrief(TenderService tenderService, TenderLotProductMatchRepository matches, SiteProductRepository products,
                          ProductRegistrationRepository registrations, MedRegistryRepository registry, RegistryMatchService registryMatch,
                          @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.tenderService = tenderService; this.matches = matches; this.products = products; this.registrations = registrations;
        this.registry = registry; this.registryMatch = registryMatch;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public String forTender(long tenderId) {
        Tender t = tenderService.findPublic(tenderId);
        return total(PROMPT + "\n" + block(t, Integer.MAX_VALUE));
    }

    @Transactional(readOnly = true)
    public AiBriefResponse forTenders(List<Long> ids) {
        StringBuilder b = new StringBuilder(PROMPT);
        int n = 0;
        for (Long id : new LinkedHashSet<>(ids == null ? List.<Long>of() : ids)) {
            if (n >= MAX_TENDERS) break;
            Tender t;
            try {
                t = tenderService.findPublic(id);
            } catch (NotFoundException e) {
                continue;                               // чужой рынок или вид — молча мимо (отбор делает экран)
            }
            b.append('\n').append(block(t, LIST_SPEC_CHARS));
            n++;
            if (b.length() >= MAX_TEXT) break;
        }
        return new AiBriefResponse(total(b.toString()), n, ids == null ? 0 : ids.size());
    }

    private String block(Tender t, int specChars) {
        StringBuilder b = new StringBuilder();
        b.append("=== Тендер № ").append(t.getTenderNumber()).append(" (").append(platform(t)).append(") — ").append(link(t)).append('\n');
        if (t.getDescription() != null) b.append("Предмет: ").append(one(t.getDescription())).append('\n');
        List<String> head = new ArrayList<>();
        if (t.getCustomerName() != null) head.add("Заказчик: " + one(t.getCustomerName()));
        if (t.getRegion() != null) head.add("регион: " + one(t.getRegion()));
        if (t.getDeadlineAt() != null) head.add("приём заявок до " + t.getDeadlineAt().format(DATE_TIME));
        else if (t.getDeadline() != null) head.add("приём заявок до " + t.getDeadline().format(DATE));
        if (t.getTotalCost() != null) head.add("сумма: " + DocFormat.money(t.getTotalCost()) + " ₸");
        if (!head.isEmpty()) b.append(String.join("; ", head)).append('\n');
        Map<Long, List<TenderLotProductMatch>> byLot = matches.findByTenderId(t.getId()).stream()
                .collect(Collectors.groupingBy(TenderLotProductMatch::getLotId, LinkedHashMap::new, Collectors.toList()));
        for (TenderLot l : t.getLots()) {
            List<TenderLotProductMatch> ms = byLot.get(l.getId());
            if (ms == null) continue;
            lot(b, l, ms, specChars);
        }
        return b.toString();
    }

    private void lot(StringBuilder b, TenderLot l, List<TenderLotProductMatch> ms, int specChars) {
        String num = l.getSourceLotCode() != null ? l.getSourceLotCode() : Objects.toString(l.getLotNumber(), "—");   // № лота площадки
        b.append("--- Лот № ").append(one(num)).append(": ").append(one(l.getEquipName()));
        if (l.getQuantity() != null) b.append(" — ").append(l.getQuantity()).append(" шт.");
        if (l.getMaxCost() != null) b.append(", до ").append(DocFormat.money(l.getMaxCost())).append(" ₸ за ед.");
        b.append('\n');
        for (TenderLotProductMatch m : ms) b.append("Сверка АИС: ").append(verdict(m)).append('\n');
        boolean spec = l.getTechSpecStatus() == TechSpecStatus.OK && l.getRequiredSpec() != null;
        String body = ProductMatchService.fitText(spec ? l.getRequiredSpec() : null, spec ? null : l.getRequiredSpec());   // как в сверке
        List<SpecRequirement> reqs = SpecRequirements.extract(body);
        if (!reqs.isEmpty()) {
            b.append("Требования ТЗ (").append(reqs.size()).append("):\n");
            reqs.forEach(r -> b.append("- ").append(one(r.text())).append('\n'));
        }
        if (!body.isBlank()) b.append(spec ? "Текст ТЗ:\n" : "Описание лота с площадки:\n").append(cut(body, specChars)).append('\n');
        Set<Long> ids = new LinkedHashSet<>();
        ms.forEach(m -> m.getFitCandidates().forEach(c -> ids.add(c.productId())));
        ms.stream().map(TenderLotProductMatch::getProductId).filter(Objects::nonNull).forEach(ids::add);
        if (!ids.isEmpty()) {
            b.append("Наши товары-кандидаты:\n");
            for (SiteProduct p : products.findAllById(ids)) product(b, p);
        }
        try {
            LotRegistryMatchResponse rm = registryMatch.matchForLotUi(l.getId(), 3);
            if (rm.getCandidates() != null && !rm.getCandidates().isEmpty()) {
                b.append("Похожие изделия в реестре НЦЭЛС РК:\n");
                for (RegistryCandidateResponse c : rm.getCandidates()) {
                    b.append("* ").append(one(c.getName())).append(" — ").append(one(Objects.toString(c.getProducer(), "")))
                            .append(c.getCountry() == null ? "" : ", " + one(c.getCountry())).append(" — РУ ").append(c.getRegNumber());
                    registry.findByRegNumber(c.getRegNumber()).map(MedRegistry::getTechChars).filter(s -> !s.isBlank())
                            .ifPresent(tc -> b.append(" — характеристики: ").append(cut(one(tc), 600)));
                    b.append('\n');
                }
            }
        } catch (RuntimeException ignored) {
            // подбор реестра — подсказка: без него текст полезен
        }
    }

    private void product(StringBuilder b, SiteProduct p) {
        b.append("* ").append(one(p.getName()));
        if (p.getSlug() != null) b.append(" — ").append(WestmedLeadMapper.productUrl(siteUrl, p.getSlug()));
        String reg = RegNumbers.normalize(p.getRegistrationCert());
        ProductRegistration r = reg == null ? null : registrations.findById(reg).orElse(null);
        b.append(" — ").append(regText(reg, r)).append('\n');
        if (p.getAttributes() != null && !p.getAttributes().isEmpty()) {
            b.append("  Характеристики (сайт): ").append(p.getAttributes().stream()
                    .map(a -> one(a.name()) + ": " + one(a.value())).collect(Collectors.joining("; "))).append('\n');
        }
        if (r != null && (r.getPurpose() != null || r.getTechChars() != null)) {
            b.append("  Из карточки РУ:");
            if (r.getPurpose() != null) b.append(" назначение — ").append(cut(one(r.getPurpose()), 400)).append(';');
            if (r.getTechChars() != null) b.append(" краткие характеристики — ").append(cut(one(r.getTechChars()), 800));
            b.append('\n');
        }
    }

    private static String regText(String reg, ProductRegistration r) {
        if (reg == null) return "РУ не указано на сайте";
        if (r == null) return "РУ " + reg + " — не проверено";
        return "РУ " + reg + " — " + switch (r.getStatus()) {
            case ACTIVE -> "действует" + (Boolean.TRUE.equals(r.getUnlimited()) ? " бессрочно"
                    : r.getExpirationDate() == null ? "" : " до " + r.getExpirationDate().format(DATE));
            case EXPIRED -> "истекло" + (r.getExpirationDate() == null ? "" : " " + r.getExpirationDate().format(DATE));
            case NOT_FOUND -> "не найдено в реестре";
            case NOT_SPECIFIED -> "не указано";
            case UNKNOWN -> "не проверено";
        };
    }

    private static String verdict(TenderLotProductMatch m) {
        if (m.getLevel() == ProductMatchLevel.COPIED) return "★ ТЗ списано с нашего товара";
        if (m.getLevel() == ProductMatchLevel.NAMED) return "★ назван наш товар";
        FitCandidate b = m.getFitCandidates() == null || m.getFitCandidates().isEmpty() ? null : m.getFitCandidates().get(0);
        String foreign = m.getForeignBrand() == null ? "" : "; ТЗ под " + m.getForeignBrand();
        if (m.getFit() == null || b == null) return "🏷 наш вид изделия, сверки нет (требований в ТЗ не найдено)" + foreign;
        String n = b.met() + " из " + b.total() + " требований, нет данных " + b.unknown();
        return switch (m.getFit()) {
            case FITS -> "✓ подходит наш товар: " + b.name() + " (" + n + ")";
            case FITS_NO_RU -> "подходит по ТЗ " + b.name() + ", но " + RegStatus.valueOf(b.regStatus()).noRuText() + " (" + n + ")";
            case PARTIAL -> "◐ похоже — проверить: " + b.name() + " (" + n + ")";
            case NOT_FIT -> "✗ не подходит: " + Objects.toString(b.reason(), b.name());
            case NO_REQUIREMENTS -> "🏷 наш вид изделия, сверки нет";
        } + foreign;
    }

    private static String platform(Tender t) {
        return t.getPlatform() == TenderPlatform.SK_PHARMACY ? "СК-Фармация" : "goszakup";
    }

    /** Ссылка на объявление — как кнопка «Открыть» карточки (market.service.portalLink). */
    private static String link(Tender t) {
        String num = Objects.toString(t.getTenderNumber(), "");
        String id = num.matches("\\d+-\\d+") ? num.substring(0, num.indexOf('-')) : null;
        if (t.getPlatform() == TenderPlatform.SK_PHARMACY) return id == null ? "https://fms.ecc.kz/ru/searchanno" : "https://fms.ecc.kz/ru/announce/index/" + id + "?tab=lots";
        return id == null ? "https://goszakup.gov.kz" : "https://goszakup.gov.kz/ru/announce/index/" + id;
    }

    private static String one(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replaceAll(" {2,}", " ").strip();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Весь текст — не длиннее MAX_TEXT, обрезка подписана (спека §9.1). */
    private static String total(String s) {
        String tail = "\n…обрезано";
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT - tail.length()) + tail;
    }
}
```

`AiBriefResponse` — `public record AiBriefResponse(String text, int tenders, int total) {}`; `AiBriefRequest` — `public record AiBriefRequest(List<Long> ids) {}`. `TenderController`:

```java
    /** «📋 Для ИИ» по одному тендеру (спека 2026-10-20-product-spec-fit §9): текст для вставки в Claude / Gemini. */
    @GetMapping(value = "/{id}/ai-brief", produces = "text/plain;charset=UTF-8")
    public String aiBrief(@PathVariable Long id) {
        return aiBrief.forTender(id);
    }

    @PostMapping("/ai-brief")
    public AiBriefResponse aiBriefList(@RequestBody AiBriefRequest req) {
        return aiBrief.forTenders(req.ids());
    }
```

(`ProductAiBrief aiBrief` — в конструктор контроллера; `ids == null` — пустой ответ.)

- [ ] **Step 4: Прогнать — зелёный; мутации**

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.ProductAiBriefTest'`
Expected: PASS. Мутации: (1) без `MAX_TENDERS` → красный `list_caps…`; (2) без `catch NotFoundException` (чужой рынок роняет весь список) → красный `list_caps…`; (3) `forTender` через `findInMarket` вместо `findPublic` → красный `otherMarketOrPrivate_404` (частная заявка); (4) ТЗ в списке без `LIST_SPEC_CHARS` → красный `list_caps…`; (5) без `total` → красный `list_totalCut`. Потом `./gradlew compileJava`.

- [ ] **Step 5: Фронт — кнопка**

`ApiService`:

```ts
  getAiBrief(tenderId: number): Observable<string> {
    return this.http.get(`${this.base}/tenders/${tenderId}/ai-brief`, { responseType: 'text' });
  }
  postAiBrief(ids: number[]): Observable<{ text: string; tenders: number; total: number }> {
    return this.http.post<{ text: string; tenders: number; total: number }>(`${this.base}/tenders/ai-brief`, { ids });
  }
```

`shared/ai-brief-button.component.ts` — standalone, `selector: 'app-ai-brief-button'`: кнопка (`label`); по нажатию — запрос текста и запись в буфер **из того же нажатия** через `ClipboardItem` с промисом (Safari на iPhone иначе запрещает запись после сетевого ответа):

```ts
  copy(): void {
    if (this.busy) return;
    this.busy = true;
    const text$ = this.tenderId != null
      ? firstValueFrom(this.api.getAiBrief(this.tenderId)).then(t => ({ text: t, tenders: 1, total: 1 }))
      : firstValueFrom(this.api.postAiBrief(this.ids ?? []));
    const done = (r: { text: string; tenders: number; total: number }) => {
      this.busy = false;
      const n = `${r.tenders} ${tendersWord(r.tenders)}`;
      const more = r.total > r.tenders && this.tenderId == null ? ` из ${r.total}` : '';
      this.notify.success(`Скопировано: ${n}${more}, ${Math.round(r.text.length / 1000)} тыс. знаков — вставьте в Claude или Gemini`);
      this.cdr.markForCheck();
    };
    const fail = (r?: { text: string }) => {          // буфер недоступен — текст в окне, «Выделить всё»
      this.busy = false;
      this.fallback = r?.text ?? null;
      if (!this.fallback) this.notify.error('Не удалось собрать текст для ИИ');
      this.cdr.markForCheck();
    };
    const blob = text$.then(r => new Blob([r.text], { type: 'text/plain' }));
    const write = (window as any).ClipboardItem && navigator.clipboard?.write
      ? navigator.clipboard.write([new ClipboardItem({ 'text/plain': blob })])
      : text$.then(r => navigator.clipboard.writeText(r.text));
    write.then(() => text$.then(done), () => text$.then(r => fail(r), () => fail()));
  }
```

Склонение — своей функцией в файле компонента (общий `shared/private-request.ts` не тянуть: страница «Тендеры» — в начальном бандле):

```ts
function tendersWord(n: number): string {
  const m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return 'тендер';
  return m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? 'тендера' : 'тендеров';
}
```

Шаблон компонента: `<button type="button" class="btn ai-btn" [disabled]="busy" (click)="copy()">{{ busy ? 'Собираю…' : label }}</button>` и окно запаса (`*ngIf="fallback"`): затемнение, «Не удалось записать в буфер — выделите текст и скопируйте», `<textarea readonly class="ai-text" (focus)="$any($event.target).select()">{{ fallback }}</textarea>`, «Закрыть» (`fallback = null`); на телефоне окно — во весь экран; классы — с приставкой `ai-` (не совпадают с UI-kit). Результаты — `.then`, не `await` (§14).

`tenders.component.ts`:
- `imports` — `AiBriefButtonComponent`;
- в карточке, в строке «Наш товар» (`<div class="info-item info-wide" *ngIf="selectedTender.foundByProductSearch || selectedTender.ourProduct">`), после `pw-found`: `<app-ai-brief-button *ngIf="selectedTender.ourProduct" [tenderId]="selectedTender.id"></app-ai-brief-button>`;
- после счётчика списка (`<span class="counter" *ngIf="filteredTenders.length">Найдено: …`): `<app-ai-brief-button *ngIf="filterOur && aiIds.length" [ids]="aiIds" [label]="'📋 Для ИИ: ' + aiIds.length + ' ' + aiWord()"></app-ai-brief-button>`;
- поле `aiIds: number[] = []` — в конце `applyTendersFilter()`:

```ts
    // «📋 Для ИИ» по списку (спека §9.1): открытые тендеры с нашей отметкой, ближайший срок — первым; сервер берёт первые 15
    const market = this.market.value;
    this.aiIds = this.filterOur ? this.filteredTenders
      .filter(t => t.ourProduct && !deadlinePassed(t, market))
      .sort((a, b) => (deadlineEnd(a, market) ?? Number.MAX_SAFE_INTEGER) - (deadlineEnd(b, market) ?? Number.MAX_SAFE_INTEGER))
      .map(t => t.id) : [];
```

- `aiWord(): string` — то же склонение («тендер / тендера / тендеров») от `aiIds.length`.

- [ ] **Step 6: Сборка; коммит**

Run: `cd frontend && npm run build && cd /Users/vlad/IdeaProjects/AIS` — без ошибок, начальный бандл ≤ 1,45 МБ.

```bash
git add src/main/java/com/vladoose/nir/service/productwatch/ProductAiBrief.java src/main/java/com/vladoose/nir/dto/response/AiBriefResponse.java \
  src/main/java/com/vladoose/nir/dto/request/AiBriefRequest.java src/main/java/com/vladoose/nir/controller/TenderController.java \
  src/test/java/com/vladoose/nir/productwatch/ProductAiBriefTest.java frontend/src/app/shared/ai-brief-button.component.ts \
  frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(product-fit): «📋 Для ИИ» — текст по тендерам с нашими лотами для Claude / Gemini

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: Набор, гейт, калибровка

**Files:**
- Create: `src/test/resources/product-watch/fit-golden.tsv`, `src/test/resources/product-watch/registrations.json`, `src/test/resources/product-watch/registry-producers.txt`, `src/test/resources/product-watch/site-attributes-extra.json` (JSON задачи 1, пока его нет на сайте), `src/test/java/com/vladoose/nir/productwatch/ProductFitQualityTest.java`
- Modify: `src/test/java/com/vladoose/nir/productwatch/ProductFixtures.java` (`catalogFull` — № РУ и доп. характеристики; `registrations`, `registryBrands`, `fitGolden`), `src/main/java/com/vladoose/nir/service/productwatch/fit/SpecFit.java` (пороги и замеры в javadoc — если калибровка их сдвинет)

**Interfaces:**
- Consumes: `ProductMatchService.fitText / fitFor` (задача 7), `CatalogSnapshot.build(groups, products, registrations, knownBrands)`, `ForeignBrand.registryBrands`, `ProductMatcher.match`, `LotInput`, `ProductFixtures.seedGroups / catalogFull / spec`.
- Produces: `ProductFixtures.registrations()` → `Map<String, RegistrationInfo>`; `ProductFixtures.registryBrands()` → `Set<String>`; `record ProductFixtures.FitGoldenRow(String file, String lot, String announcement, String description, String expected, String product, String foreign, String note)`; `ProductFixtures.fitGolden()`.

- [ ] **Step 1: Снимки РУ наших товаров и производителей реестра**

Локально (sandbox off): бэкенд с `PRODUCT_WATCH_ENABLED=true` и расписаниями «-» (CLAUDE.md §5 «Наша продукция в тендерах — локально»), «Обновить» каталог (`POST /api/site-catalog/sync`) — РУ проверяются живьём в ndda.kz (37 номеров, ~15 с; в логе `Наша продукция: РУ: проверено 37 …`). Затем:

```bash
export PGPASSWORD=admin
/Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -At -c "select coalesce(json_agg(json_build_object('regNumber', reg_number, \
'status', status, 'expires', expiration_date, 'unlimited', unlimited, 'purpose', purpose, 'useArea', use_area, 'techChars', tech_chars, \
'components', (select coalesce(json_agg(c->>'component'), '[]'::json) from jsonb_array_elements(complect) c)) order by reg_number), '[]') \
from product_registration" > src/test/resources/product-watch/registrations.json
/Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -At -c "select distinct producer from med_registry where producer is not null order by 1" \
  > src/test/resources/product-watch/registry-producers.txt
python3 -c "import json;r=json.load(open('src/test/resources/product-watch/registrations.json'));import collections;print(len(r),collections.Counter(x['status'] for x in r))"
```

Expected: 37 номеров; распределение статусов (например, `ACTIVE` … / `EXPIRED` … / `NOT_FOUND` …) — записать в шапку `fit-golden.tsv` (снимок на дату). Реестр и РУ — публичные данные.

- [ ] **Step 2: Фикстуры**

`ProductFixtures`:

```java
    record FullItem(String id, String name, String slug, String brand, String category, String shortDescription,
                    String description, List<SiteProductAttribute> attributes, String registrationCert) {}

    record ExtraAttributes(String siteId, List<SiteProductAttribute> attributes) {}

    /** Снимок каталога с описаниями, характеристиками и № РУ (админ-API) + характеристики Фотек и Ориона (задача 1), пока их нет на сайте. */
    public static List<SiteProduct> catalogFull() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/site-catalog-full.json")) {
            ObjectMapper om = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            List<FullItem> items = om.readValue(in, new TypeReference<>() {});
            Map<String, List<SiteProductAttribute>> extra = new HashMap<>();
            try (InputStream ex = ProductFixtures.class.getResourceAsStream("/product-watch/site-attributes-extra.json")) {
                if (ex != null) for (ExtraAttributes e : om.readValue(ex, new TypeReference<List<ExtraAttributes>>() {})) extra.put(e.siteId(), e.attributes());
            }
            AtomicLong id = new AtomicLong(1000);
            List<SiteProduct> out = new ArrayList<>();
            for (FullItem i : items) {
                List<SiteProductAttribute> attrs = new ArrayList<>(i.attributes() == null ? List.of() : i.attributes());
                for (SiteProductAttribute a : extra.getOrDefault(i.id(), List.of())) {
                    if (attrs.stream().noneMatch(x -> x.name().equalsIgnoreCase(a.name()))) attrs.add(a);
                }
                out.add(SiteProduct.builder().id(id.incrementAndGet()).market(Market.KZ).siteId(UUID.fromString(i.id()))
                        .name(i.name()).slug(i.slug()).brand(i.brand()).category(i.category())
                        .shortDescription(i.shortDescription()).description(i.description()).attributes(attrs)
                        .registrationCert(i.registrationCert()).syncedAt(OffsetDateTime.now()).build());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record RegItem(String regNumber, String status, LocalDate expires, Boolean unlimited, String purpose, String useArea,
                   String techChars, List<String> components) {}

    /** РУ наших товаров (снимок ndda.kz, задача 14) — для гейта сверки. */
    public static Map<String, RegistrationInfo> registrations() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/registrations.json")) {
            List<RegItem> items = new ObjectMapper().registerModule(new JavaTimeModule())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(in, new TypeReference<>() {});
            Map<String, RegistrationInfo> out = new HashMap<>();
            for (RegItem r : items) {
                out.put(r.regNumber(), new RegistrationInfo(r.regNumber(), RegStatus.valueOf(r.status()), r.expires(),
                        Boolean.TRUE.equals(r.unlimited()), r.purpose(), r.useArea(), r.techChars(),
                        r.components() == null ? List.of() : r.components()));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Бренды из производителей реестра (снимок, задача 14) — как `ProductCatalog.refresh` в работе. */
    public static Set<String> registryBrands() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/registry-producers.txt")) {
            return ForeignBrand.registryBrands(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Строка набора сверки (fit-golden.tsv): файл ТЗ, лот, объявление, описание, ожидаемый вердикт, товар, чужой бренд, заметка. */
    public record FitGoldenRow(String file, String lot, String announcement, String description, String expected, String product,
                               String foreign, String note) {}

    public static List<FitGoldenRow> fitGolden() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/fit-golden.tsv")) {
            List<FitGoldenRow> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", -1);
                out.add(new FitGoldenRow(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c.length > 7 ? c[7] : ""));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
```

(импорты — `com.fasterxml.jackson.databind.DeserializationFeature`, `com.fasterxml.jackson.datatype.jsr310.JavaTimeModule`, `com.vladoose.nir.entity.RegStatus`, `com.vladoose.nir.service.productwatch.fit.ForeignBrand`, `com.vladoose.nir.service.productwatch.fit.RegistrationInfo`, `java.nio.charset.StandardCharsets`, `java.time.LocalDate`.) Прежние тесты на `catalogFull()` (`ProductSpecMatchQualityTest`) — прогнать: № РУ и доп. характеристики их уровни менять не должны (там считаются уровни TYPE / NAMED / COPIED).

- [ ] **Step 3: Разметка**

Заготовка из старого набора (те же 37 ТЗ, те же лот, объявление и описание):

```bash
python3 - <<'PY'
rows=[l.rstrip('\n').split('\t') for l in open('src/test/resources/product-watch/golden.tsv',encoding='utf-8') if l.strip() and not l.startswith('#')]
with open('src/test/resources/product-watch/fit-golden.tsv','w',encoding='utf-8') as f:
    f.write('# Набор сверки ТЗ с нашими товарами (задача 14 плана 2026-10-20-product-spec-fit). Колонки: файл | лот | объявление | описание |\n')
    f.write('# ожидание (FITS / FITS_NO_RU / PARTIAL / NOT_FIT / NO_REQUIREMENTS / ANY) | товар (начало названия, для FITS*) | чужой бренд (или -) | заметка.\n')
    f.write('# Размечено по смыслу ТЗ и характеристикам товара (сайт + задача 1 + карточка РУ), не по выдаче правил. ANY — «не наше»: что угодно, кроме FITS.\n')
    for c in rows:
        f.write('\t'.join([c[0], c[4], c[5], c[6] if len(c) > 6 else '', '?', '', '-', 'было '+c[1]])+'\n')
PY
```

Разметить колонки «ожидание / товар / чужой бренд» по каждому ТЗ (`?` в файле не остаётся): ТЗ — глазами (PDF из `specs/`), характеристики товара — по сайту, таблице задачи 1 и карточке РУ. Строки `NONE` старого набора — `ANY`. Добавить ТЗ тендеров, найденных на проде 2026-10-20 (номера — с прода по SSH только чтением с согласия оператора или локальным поиском по 3–4 группам малыми порциями; PDF — `ProductSpecCorpusTool`, в `specs/`). Спорные строки — оператору одним сообщением.

- [ ] **Step 4: Гейт (красный, пока не размечено)**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.LotFit;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гейт сверки ТЗ с нашими товарами (спека 2026-10-20-product-spec-fit §13) на настоящих ТЗ — тем же кодом, что считает отметку
 * (`ProductMatcher.match` → `ProductMatchService.fitText / fitFor`): ложных «подходит» — 0; чужой бренд — как размечено;
 * найдено верных «подходит» — не меньше FOUND_MIN (храповик: упало — разбираться, а не понижать).
 */
class ProductFitQualityTest {

    /** Ставится по итогу калибровки задачи 14 (сколько строк FITS* сверка находит верно), дальше не понижать. */
    static final int FOUND_MIN = 0;

    @Test
    void gate() {
        CatalogSnapshot snap = CatalogSnapshot.build(ProductFixtures.seedGroups(), ProductFixtures.catalogFull(),
                ProductFixtures.registrations(), ProductFixtures.registryBrands());
        int falseFits = 0, found = 0, foreignWrong = 0, unlabeled = 0;
        List<String> report = new ArrayList<>();
        for (ProductFixtures.FitGoldenRow row : ProductFixtures.fitGolden()) {
            if (row.expected().equals("?")) { unlabeled++; continue; }
            String spec = ProductFixtures.spec(row.file());
            String text = ProductMatchService.fitText(spec, row.description());
            FitVerdict best = FitVerdict.NO_REQUIREMENTS;
            String product = null, foreign = null;
            for (LotMatch m : ProductMatcher.match(snap, row.announcement(), List.of(new LotInput(1, row.lot(), row.description(), spec)))) {
                LotFit f = ProductMatchService.fitFor(snap, m.groupId(), text);
                if (f == null) continue;
                if (foreign == null) foreign = f.foreignBrand();
                if (f.verdict().rank() > best.rank()) { best = f.verdict(); product = f.best() == null ? null : f.best().name(); }
            }
            if (best == FitVerdict.FITS && !row.expected().equals("FITS")) falseFits++;
            if (row.expected().startsWith("FITS") && best.name().equals(row.expected()) && product != null && product.startsWith(row.product())) found++;
            if (!Objects.equals(row.foreign().equals("-") ? null : row.foreign(), foreign)) foreignWrong++;
            report.add("REPORT " + row.file() + " | ждали " + row.expected() + " " + row.product() + " | " + best + " " + product
                    + " | чужой: ждали " + row.foreign() + ", " + foreign);
        }
        report.forEach(System.out::println);
        assertThat(unlabeled).as("неразмеченных строк").isZero();
        assertThat(falseFits).as("ложных «подходит»").isZero();
        assertThat(foreignWrong).as("чужой бренд не как размечено").isZero();
        assertThat(found).as("найдено верных «подходит»").isGreaterThanOrEqualTo(FOUND_MIN);
    }
}
```

Run: `./gradlew test --tests 'com.vladoose.nir.productwatch.ProductFitQualityTest' --tests 'com.vladoose.nir.productwatch.ProductSpecMatchQualityTest' -i | grep -E "REPORT|FAILED|PASSED|tests completed"`
Expected до разметки: FAIL «неразмеченных строк»; после — строки `REPORT` для калибровки; `ProductSpecMatchQualityTest` — PASS.

- [ ] **Step 5: Калибровка**

Прогнать гейт, читать строки `REPORT`: ложные FITS — чинить правило (разбор, синонимы, «до X», допуск), а не разметку; ни одно изменение — без нового теста в задачах 2–5 на его случай. Пороги (`FITS_SHARE`, `FITS_MIN_MET`, `APPROX_TOLERANCE`, `FEATURE_SHARE`) двигать только **совместно** и только если ложные FITS не убираются правилами; замер «до / после» — в javadoc `SpecFit` рядом с константой. В конце — `FOUND_MIN` = найденное (храповик).

- [ ] **Step 6: Мутации гейта**

(1) `FITS_SHARE = 0.2` → ложные FITS > 0 (если нет — гейт ничего не держит: добавить в разметку ТЗ «под чужой бренд без нашего аналога», где это так); (2) `ForeignBrand` без `modelCodeAfter` → `foreignWrong` > 0; (3) `card.regActive()` не проверять (всегда FITS) → ложные FITS > 0 на строках `FITS_NO_RU` (ОМХ-5/80 — РУ истекло 28.06.2026). Потом `./gradlew compileJava`.

- [ ] **Step 7: Коммит**

```bash
git add src/test/resources/product-watch/ src/test/java/com/vladoose/nir/productwatch/ProductFitQualityTest.java \
  src/test/java/com/vladoose/nir/productwatch/ProductFixtures.java src/main/java/com/vladoose/nir/service/productwatch/fit/
git commit -m "test(product-fit): набор настоящих ТЗ, гейт «ложных подходит — 0», калибровка порогов

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 15: Характеристики на westmed.kz (после «да» оператора)

Не код АИС. **Только после явного «да» оператора на таблицу задачи 1** (учётка `ais@westmed.kz` — полные права админки сайта). Нет «да» — задача ждёт, задача 16 идёт без неё (фикстура уже с данными задачи 1).

Контракт сайта (сверено по коду westmed, `AdminProductService.update`): `PATCH /api/admin/products/{id}` с полем `attributes` **заменяет весь список** характеристик (`[{name, value}]`), остальные поля `null` не трогает; карточка `GET /api/admin/products/{id}` отдаёт `attributes` как `[{attributeName, attributeValue, sortOrder}]`; вход — `POST /api/auth/login` `{email, password}` → `accessToken` (у сайта 5 входов за 5 минут с IP — скрипт входит один раз).

- [ ] **Step 1: Скрипт (scratchpad, не в репозиторий)**

`<scratchpad>/westmed-attributes.py`:

```python
#!/usr/bin/env python3
"""Разовая запись характеристик Фотек и Ориона на westmed.kz (задача 15). Без --write — только показывает, что добавится."""
import json, os, sys, urllib.request

BASE = 'https://westmed.kz'
WRITE = '--write' in sys.argv
PASSWORD = open(os.path.expanduser('~/.config/ais/westmed-prod.pass'), encoding='utf-8').read().strip()   # не печатать


def call(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    with urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, method=method, headers=headers), timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


token = call('POST', '/api/auth/login', {'email': 'ais@westmed.kz', 'password': PASSWORD})['accessToken']
items = json.load(open('docs/research/2026-10-20-fotek-orion-attributes.json', encoding='utf-8'))
total = 0
for it in items:
    card = call('GET', f"/api/admin/products/{it['siteId']}", token=token)
    have = [{'name': a['attributeName'], 'value': a['attributeValue']}
            for a in sorted(card.get('attributes') or [], key=lambda a: a.get('sortOrder') or 0)]
    names = {a['name'].strip().lower() for a in have}
    add = [a for a in it['attributes'] if a['name'].strip().lower() not in names]   # прежние — как есть, новые — в конец
    print(f"{card['name'][:70]}: было {len(have)}, добавится {len(add)}")
    total += len(add)
    if WRITE and add:
        after = call('PATCH', f"/api/admin/products/{it['siteId']}", {'attributes': have + add}, token=token)
        if len(after.get('attributes') or []) != len(have) + len(add):
            sys.exit(f"после записи «{card['name'][:50]}» число характеристик не сошлось — остановка")
print(('добавлено: ' if WRITE else 'добавится: ') + str(total))
```

- [ ] **Step 2: Сухой прогон**

Run (sandbox off — сеть до westmed.kz): `python3 <scratchpad>/westmed-attributes.py`
Expected: по строке на товар «было N, добавится M» и итог — сверить с таблицей задачи 1 (товаров и строк столько же). Показать оператору итог.

- [ ] **Step 3: Запись**

Run: `python3 <scratchpad>/westmed-attributes.py --write`
Expected: «добавлено: M» (то же число). Повторный сухой прогон — «добавится: 0» у всех товаров. Отчёт оператору: товаров и строк внесено.

- [ ] **Step 4: АИС**

На проде — «Наша продукция → Товары → Обновить» (или загрузка каталога в 6:00): характеристики подтянутся, пересчёт сверки — сам. Локально — снимок каталога заново (`SITE_CATALOG_SNAPSHOT=1 WESTMED_USERNAME=ais@westmed.kz WESTMED_PASSWORD="$(cat ~/.config/ais/westmed-prod.pass)" ./gradlew test --tests '*SiteCatalogSnapshotTool*'`, только чтение, учётка — с согласия оператора), после него `site-attributes-extra.json` удалить (характеристики уже в `site-catalog-full.json`) и прогнать гейт задачи 14 — результат тот же.

---

### Task 16: Документация, живая проверка, ревью ветки, мерж, выкладка

**Files:**
- Modify: `CLAUDE.md` (§5, §7, §8 — блок «Сверка ТЗ с нашими товарами», §12, §13, §15, §16), `DEPLOY.md` §11, `docs/PROGRESS.md`, спека §17

- [ ] **Step 1: Живая проверка**

Локально (sandbox off): `./gradlew devMail` не нужен; бэкенд — как §5 «Наша продукция в тендерах — локально» (расписания «-», Telegram — заглушка `node scripts/telegram-stub.mjs`, `TELEGRAM_PRODUCTS_THREAD_ID=95`); каталог с характеристиками — разовым SQL из `site-catalog-full.json` (+ данные задачи 1) по `site_id` в `site_product.attributes` / `registration_cert` (админ-API сайта — только с согласия оператора); «Обновить» каталог → в логе `Наша продукция: РУ: проверено 37 (…)` и пересчёт; тестовые тендеры: «Аспиратор» с ТЗ Мангистау (17729514-1), «Электрокоагулятор» с ТЗ BOWA (17828560-1), облучатель Абай (17822225-1) — ТЗ из `src/test/resources/product-watch/specs` в `required_spec`, `tech_spec_status = OK`. Браузер (локальный Playwright или MCP; §5 «Браузерная проверка»): карточка тендера — чипы, «Сверка с нашим товаром», «ещё: …», ✗ сверху, «ТЗ под BOWA», «Требования ТЗ» у лота без нашей отметки, «Полный текст ТЗ»; «Наша продукция → Товары» — ОМХ «истекло 28.06.2026», фильтры; «📋 Для ИИ» в карточке и в списке (фильтр «Наш товар») — текст в буфере (`navigator.clipboard.readText()` в браузере с разрешением), тост; окно запаса — при запрете буфера; заглушка Telegram — «✅ Подходит наш товар» в теме 95 только у FITS от 1 млн ₸; 1280 / 390, обе темы, оператор (только просмотр, 0 запросов записи), РФ — без вкладки «Товары»-данных. Уборка — как §5 (тестовые тендеры, `procurement_plan_point` не трогались, `product_group.updated_at` вернуть, если правились).

- [ ] **Step 2: Документация**

CLAUDE.md: §8 — новый блок «Сверка ТЗ с нашими товарами (2026-10-2x, ветка `feature/product-spec-fit`, V39)» по образцу соседних (что умеет, правила разбора и вердикта, РУ, Telegram, «Для ИИ», проверено, ревью); §5 — строка о проверке РУ при загрузке каталога в разделе «Наша продукция в тендерах — локально»; §7 — `product_registration`, колонки сверки, уровень FITS; §12 — `LotFitComponent` под `@defer`, вкладка «Товары», `app-ai-brief-button`; §13 — число тестов полного прогона; §15 — `GET /api/product-catalog`, `GET /api/tenders/{id}/ai-brief`, `POST /api/tenders/ai-brief`, поля сверки у лота; §16 — что осталось (по итогам ревью). DEPLOY.md §11 — V39, проверка РУ в логе, порядок отката (SQL ниже), «Обновить» после внесения характеристик. Спека §17 — решения при реализации.

Откат кода ниже V39 — сначала SQL:

```sql
update tender_lot_product_match set level = 'TYPE' where level = 'FITS';
update tender set product_notified_level = 'TYPE' where product_notified_level = 'FITS';
```

- [ ] **Step 3: Полный прогон**

```bash
lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill
./gradlew cleanTest test
```

Expected: 0 падений (skipped — прежние инструменты калибровки). Число тестов — в CLAUDE.md §13.

- [ ] **Step 4: Ревью ветки — самому (§2)**

`git diff main...feature/product-spec-fit` целиком свежим взглядом: гард рынка (`findPublic`) у путей «Для ИИ»; сеть ndda.kz вне транзакций; JSONB-кандидаты с `@JsonIgnoreProperties`; пустые и кривые ТЗ (нет текста, одни картинки, казахская часть); `@DynamicUpdate` не нужен (запись отметки — одна транзакция); бюджеты фронта. Найденное — тест красный до правки, правка, мутация.

- [ ] **Step 5: Мерж и выкладка**

```bash
git checkout main && git merge --ff-only feature/product-spec-fit && git branch -d feature/product-spec-fit
```

Push — оператор: `! git push origin main`. После деплоя (с согласия оператора, SSH только чтение): V39 применена, в логе при старте `«Наш товар» в Telegram: …` и после загрузки каталога `Наша продукция: РУ: проверено …`; `select fit, count(*) from tender_lot_product_match group by 1;` — вердикты на открытых тендерах; первое «✅ Подходит наш товар» в теме 95 — показать оператору. PROGRESS «▶ Последняя задача» и память — обновить.
