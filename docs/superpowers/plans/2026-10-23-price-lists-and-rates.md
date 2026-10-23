# Прайс-листы поставщиков и курсы валют — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> В этом проекте — **только executing-plans в основной сессии, без субагентов** (CLAUDE.md §2).

**Goal:** прайсы поставщиков (Excel, Word, PDF) заносятся в АИС, их цены видны «у кого и почём» в заявке, тендере, КП и
у наших товаров и берутся закупкой без запроса цен; наценка считается от «нашего курса», а закупка и прибыль — ещё и по
курсу НБ РК сегодня.

**Architecture:** курсы — `service/rates` (`CurrencyRates`: наш курс по валютам рынка из `currency_rate`, курс сегодня из
`official_rate`; `RatesScheduler` берёт курс у Нацбанка через `integration/nbrk`; общий пересчёт `RateMath`). Прайсы —
`service/pricelist` (`PriceListReader` разбирает файл: таблица — `ReplyTableReader`, строки текста — `PriceTextLines`,
PDF по сетке — `PdfTableReader`; `PriceListService` — черновик, правка, «Сохранить прайс», связь с товарами
`PriceListLinker`; `PriceHints` — подсказки, поиск и история). Ответ поставщика и КП хранят исходную валюту, цену и
курс; расчёт КП получает курсы сегодня входом и считает закупку и прибыль по ним.

**Tech Stack:** Spring Boot 3.5.6 / Java 17, JPA (Hibernate 6), Flyway, PostgreSQL 17, Apache POI 5.2.5, PDFBox 3.0.7
(`PDFTextStripper`, `PDFGraphicsStreamEngine`), JDK HttpServer (заглушка Нацбанка); Angular 21 standalone; JUnit 5,
AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-23-price-lists-and-rates-design.md`

## Global Constraints

- Курсы — оба рынка; «курс сегодня» — только KZ (НБ РК): на РФ `CurrencyRates.today` пусто.
- Валюты нашего курса: KZ — RUB, USD, EUR, CNY; RF — USD, EUR, CNY, KZT (`CurrencyRates.currencies`).
- Курс: > 0, ≤ 100 000, не больше 6 знаков после запятой (`RateMath.check`, тексты отказа — как у курса файла ответа).
- Пересчёт: сумма × курс одной дробью, HALF_UP до 0,01; больше 13 цифр до запятой — не пересчитывается (`RateMath.convert`).
- Курс сегодня — последний с датой ≤ сегодня по Уральску; старше 3 дней — `stale` («курс от ДД.ММ»).
- Сеть (Нацбанк) — вне транзакций, `UpstreamHttp.exchange`, дедлайн 20 с, ответ ≤ 1 МБ; `RATES_NBRK_ENABLED` по умолчанию
  `false`, в `.env` пустым не оставлять (пустой булев флаг роняет старт, CLAUDE.md §14).
- `price_list` — рыночная (`@Filter` + `MarketStampingListener` + `MarketScoped`); `price_list_item` — через прайс; нативные
  запросы по строкам — с явным `p.market = :market`; путь по id — явный гард рынка (`findById` фильтр не режет, §14).
- Файлы — до 15 МБ (`SupplierReplyFiles.PARSE_MAX` 5 МБ на разбор — для прайсов свой предел `PriceListReader.PARSE_MAX`
  15 МБ), разбор — вне транзакции, байты — в БД; отдача — `attachment` + RFC 5987, `nosniff`, `no-store`.
- Записи — `@PreAuthorize("hasRole('ADMIN')")`, чтение — любой вошедший.
- Настоящие прайсы (Аксион, Орион-Си) в репозиторий не кладутся (он публичный): тесты — на синтетических файлах той же
  вёрстки, настоящие — локальным инструментом `PriceListCorpusTool` (переменная `PRICE_LIST_CORPUS`).
- Каждый коммит — строка `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- `./gradlew` и psql — с `dangerouslyDisableSandbox: true`; перед прогоном: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill;
  lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`. Команды — из корня: `cd /Users/vlad/IdeaProjects/AIS && …`.
- Фронт: новые страницы и окна — ленивые (`loadComponent`, `@defer`), начальный бандл ≤ 1,5 МБ (сейчас 1,46); результаты
  промисов — `.then`; классы компонентов — не `.empty / .subtitle / .counter / .badge / .form-actions / .field-error`
  (UI-kit); телефон — карточки (`card-grid`), цели 40 px.
- Ветка — `feature/price-lists-and-rates`; мерж в `main` — `--ff-only`; push — оператор (`! git push origin main`).
- Тест — красный до правки по своей причине; после зелёного — мутация (сломать правку → нужный тест краснеет → вернуть
  копией файла, не `git checkout`, CLAUDE.md §14); серию мутаций закончить `./gradlew compileJava`.

## Review Focus

1. **Наш курс сменили (6 → 6,5), а вкладка КП открыта со старым:** строки хранят свой курс и закупку; автосохранение
   старой вкладки шлёт прежний курс — сервер принимает только сохранённый курс строки или текущий наш, другой — 400; сам
   курс у строк не меняется без «Пересчитать» (тесты — задача 4, `purchaseRate_onlyStoredOrCurrentOurs`).
2. **Нацбанк недоступен, отвечает 500, пустым или битым XML:** последний известный курс остаётся, «курс сегодня» —
   с прежней датой, итоги КП «по курсу сегодня» пусты, если курса нет вовсе; приём и экраны не падают (тесты — задача 2,
   `fetchFailure_keepsLastRate_noThrow`, и задача 4, `noTodayRate_todayTotalsEmpty`).
3. **Одна модель в нескольких комплектациях и два прайса одного поставщика (старый и новый):** подсказка берёт строки
   только нового прайса — все комплектации, история — со строками старого; у другого поставщика — свои строки (тест —
   задача 8, `latestListWins_variantsKept_historyHasOld`).
4. **Тот же файл второй раз и чужой рынок:** тот же поставщик + та же SHA-256 — открывается прежний прайс, дубля нет;
   поставщик другого рынка — 400, прайс другого рынка по id — 404 (тесты — задача 7).
5. **PDF без сетки, скан, незнакомая вёрстка, пустая таблица:** черновик без строк с пометкой «строки не найдены…»,
   загрузка не падает, файл сохраняется (тесты — задачи 5, 6 и 7).

## Решения при планировании (где план точнее спеки)

1. **«Загрузить прайс» у поставщика** — ссылка «Прайсы ›» в строке «Дистрибьюторов» ведёт на `/price-lists?supplier=<id>`,
   где загрузка уже с этим поставщиком: меню «⋯» у строки поставщика нет (там кнопки «изменить» и «удалить»).
2. **Строка нашего курса** — суррогатный `id` + `UNIQUE (market, currency)`: составной ключ записью Hibernate 6 не любит.
3. **Курс сегодня** — при старте и раз в 3 часа (`0 7 */3 * * *`, Уральск); хранится история по датам; на дату, на которую
   курс уже есть, — перезапись (Нацбанк поправил курс).
4. **Цена ответа из строки файла в чужой валюте:** исходную цену, валюту и курс сервер берёт из самой строки файла
   (экран присылает `fileId` / `fileRow`), а не из текста примечания.
5. **PUT ответов без валюты** (карточка тендера «Сохранить ответы» шлёт все строки): цена та же — исходная валюта и курс
   остаются; цена другая — снимаются; валюта рынка — снимаются.
6. **Строка КП в чужой валюте:** `purchaseRate` из запроса — только сохранённый курс строки или текущий наш курс этой
   валюты, иначе 400; пусто — сохранённый (та же валюта), иначе наш. Закупку в тенге считает сервер.
7. **PDF-таблица — по линиям сетки, нарисованным в PDF** (у Аксиона: 7 вертикальных границ, строки — горизонтальными
   отрезками; на второй странице сетка своя и шапки нет): колонки — по каждой странице, роли — по шапке первой страницы
   по порядку колонок. Без сетки — строки текста. Объединённая ячейка РУ на несколько строк — её текст у каждой строки.
8. **Ключ модели** — первый код модели наименования без разделителей, нижним регистром («ДКИ-Н-12» → «дкин12»);
   «СК – 16 л» склеивается в «СК-16».
9. **Лучшая строка подсказки** — самая дешёвая закупка в валюте рынка по нашему курсу среди текущих строк поставщиков;
   у поставщика текущие — строки его последнего действующего прайса с этим ключом (все комплектации).
10. **Файлы писем «прочее» на zakup@** — только Excel, Word, PDF до 15 МБ; строка в Telegram — только у похожих на прайс.
11. **Прайс с валютой «не ясна»** (MIXED) — в подсказки не идёт, пока валюту не выбрали в шапке экрана проверки.
12. **Прайс в валюте рынка** (поставщик РФ в рублях на рынке РФ) — без пересчёта, «→ тг» не пишется.
13. **Роль колонки `REG`** видна и на экране сверки ответа («РУ»): заголовок «Регистрационное удостоверение» раньше был
   «Игнорировать» — на цены это не влияет.
14. **Валюта прайса не названа** — при заведении ставится валюта рынка: она видна и правится в шапке экрана проверки
   (как «валюта не указана — считаем тенге» у файлов ответа); «не ясна» (MIXED) — отдельно, решение 11.
15. **Подсказка — `PriceHint {text, rows}`**: строки — лучшая первой, не больше 10; «ещё N» — остальные (вместо поля
   `more` спеки §9). Строка (`PriceHintRow`) — та же форма у позиции заявки, лота, товара, в поиске и «Поставщик ▾».
16. **«Было …», подсказки и история** сравнивают строки по ЛЮБОМУ общему ключу (`PriceListKeys`: «p<товар>»,
   «e<позиция каталога>», «k<код модели>»): у старого прайса связи с товаром могло не быть, а код модели у строк одного
   товара общий.
17. **Плашка «Пришли новые цены»** — у любого черновика (строки со ссылкой на строку прайса бывают и в КП не из заявки);
   ответы поставщиков — как раньше, только у КП из заявки. Ссылка строки КП — либо на ответ, либо на строку прайса: обе
   сразу — 400.

---

### Task 1: Данные (V44), сущности, репозитории, роль колонки «РУ»

**Files:**
- Create: `src/main/resources/db/migration/V44__price_lists_and_rates.sql`
- Create: `src/main/java/com/vladoose/nir/entity/CurrencyRate.java`, `OfficialRate.java`, `PriceListStatus.java`,
  `PriceListColumn.java`, `PriceList.java`, `PriceListItem.java`, `InboundEmailFile.java`
- Modify: `src/main/java/com/vladoose/nir/entity/PriceRequestItem.java` (3 поля после `responseFileRow`),
  `src/main/java/com/vladoose/nir/entity/ClientOfferItem.java` (4 поля после `priceRequestItemId`),
  `src/main/java/com/vladoose/nir/entity/LineField.java` (+ `REG`),
  `src/main/java/com/vladoose/nir/service/supplierreply/HeaderRoles.java` (правило `REG`)
- Create: `src/main/java/com/vladoose/nir/repository/CurrencyRateRepository.java`, `OfficialRateRepository.java`,
  `PriceListRepository.java`, `PriceListItemRepository.java`, `InboundEmailFileRepository.java`
- Test: `src/test/java/com/vladoose/nir/pricelist/PriceListSchemaTest.java`,
  `src/test/java/com/vladoose/nir/pricelist/PriceListFixtures.java`

**Interfaces:**
- Produces: сущности `CurrencyRate {id, market, currency, ourRate, updatedBy, updatedAt}`, `OfficialRate {id, source,
  currency, rateDate, rate, fetchedAt}`, `enum PriceListStatus { DRAFT, ACTIVE }`, `record PriceListColumn(int index,
  String header)`, `PriceList` (рыночная; `distributor`, `status`, `listDate`, `validTo`, `currency`, `retail`, `columns`,
  `ourColumn`, `discountPct`, `terms`, файл `fileName / mimeType / sizeBytes / sha256 / data / kind`, `parseStatus`,
  `parseNote`, `inboundEmailId`, `createdBy / createdAt`, `activatedBy / activatedAt`, `updatedAt`, `items`),
  `PriceListItem {id, priceList, lineNo, section, name, model, article, prices: List<BigDecimal>, ourPrice, regText,
  regNumber, noReg, siteProductId, medEquipmentId, modelKey, fileRow, note}`, `InboundEmailFile {id, inboundEmailId,
  fileName, mimeType, sizeBytes, sha256, data, priceLike}`; у `PriceRequestItem` — `responseCurrency`, `responseOriginal`,
  `responseRate`; у `ClientOfferItem` — `purchaseCurrency`, `purchaseOriginal`, `purchaseRate`, `priceListItemId`;
  `LineField.REG`.
- Produces (репозитории): `CurrencyRateRepository.findByMarket(Market)`, `findByMarketAndCurrency(Market, String)`;
  `OfficialRateRepository.findFirstBySourceAndCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String, String,
  LocalDate)`, `findBySourceAndCurrencyAndRateDate(String, String, LocalDate)`; `PriceListRepository.findByDistributorIdAndSha256
  (Long, String)`, `findRows()` → `List<Object[]>` [id, distributorId, distributorName, status, listDate, validTo, currency,
  fileName, kind, parseStatus, createdBy, createdAt, itemCount], `countByDistributor()` → [distributorId, count];
  `PriceListItemRepository.findByPriceListIdOrderByLineNoAscIdAsc(Long)`; `InboundEmailFileRepository.findInfoByInboundIds
  (Collection<Long>)` → [id, inboundEmailId, fileName, priceLike].

- [ ] **Step 1: Ветка**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout -b feature/price-lists-and-rates
```

- [ ] **Step 2: Тест схемы — красный**

`PriceListFixtures` — помощник тестов блока:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceListRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Поставщик и прайс для тестов блока (имена — «ZZ …» с меткой времени: название поставщика уникально по всем рынкам). */
public final class PriceListFixtures {

    private PriceListFixtures() {}

    public static Distributor supplier(DistributorRepository repo, Market market, String name) {
        return repo.saveAndFlush(Distributor.builder().name("ZZ " + name + " " + System.nanoTime()).market(market)
                .email("zz-" + System.nanoTime() + "@example.kz").build());
    }

    /** Прайс со строками «наименование, цены…»; наша колонка — 0. */
    public static PriceList list(PriceListRepository repo, Distributor d, PriceListStatus status, String currency,
                                 java.time.LocalDate listDate, Object[]... rows) {
        PriceList p = PriceList.builder().market(d.getMarket()).distributor(d).status(status).currency(currency)
                .listDate(listDate).columns(new ArrayList<>(List.of(new PriceListColumn(0, "Цена"))))
                .ourColumn(0).fileName("zz-прайс.xlsx").sha256(Long.toHexString(System.nanoTime()))
                .kind(ReplyFileKind.EXCEL).parseStatus(ReplyParseStatus.PARSED).data(new byte[]{1}).build();
        int n = 0;
        for (Object[] r : rows) {
            BigDecimal price = r[1] == null ? null : new BigDecimal(r[1].toString());
            p.getItems().add(PriceListItem.builder().priceList(p).lineNo(++n).name((String) r[0])
                    .prices(new ArrayList<>(Arrays.asList(price))).ourPrice(price)
                    .modelKey(r.length > 2 ? (String) r[2] : null).build());
        }
        return repo.saveAndFlush(p);
    }
}
```

`PriceListSchemaTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.supplierreply.HeaderRoles;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V44 (спека 2026-10-23-price-lists-and-rates §8): таблицы, JSONB цен, каскад, рынок, роль «РУ». */
@SpringBootTest
@Transactional
class PriceListSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DistributorRepository distributors;
    @Autowired PriceListRepository lists;
    @Autowired PriceListItemRepository items;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void v44Tables() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name in "
                + "('currency_rate', 'official_rate', 'price_list', 'price_list_item', 'inbound_email_file')", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'price_request_item' "
                + "and column_name in ('response_currency', 'response_original', 'response_rate')", String.class)).hasSize(3);
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'client_offer_item' "
                + "and column_name in ('purchase_currency', 'purchase_original', 'purchase_rate', 'price_list_item_id')", String.class)).hasSize(4);
    }

    @Test
    void pricesRoundTrip_withEmptyCell() {
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "Аксион");
        PriceList p = PriceListFixtures.list(lists, d, PriceListStatus.DRAFT, "RUB", LocalDate.of(2026, 9, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-12", "150000", "дкин12"});
        p.getColumns().add(new PriceListColumn(1, "Оптовая"));
        p.getItems().get(0).getPrices().add(null);
        lists.saveAndFlush(p);
        em.clear();
        PriceList back = lists.findById(p.getId()).orElseThrow();
        assertThat(back.getColumns()).extracting(PriceListColumn::header).containsExactly("Цена", "Оптовая");
        assertThat(back.getItems().get(0).getPrices()).hasSize(2);
        assertThat(back.getItems().get(0).getPrices().get(0)).isEqualByComparingTo("150000");
        assertThat(back.getItems().get(0).getPrices().get(1)).isNull();
        assertThat(back.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void deleteList_cascadesItems_offerLinkSetNull() {
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "Каскад");
        PriceList p = PriceListFixtures.list(lists, d, PriceListStatus.ACTIVE, "RUB", null, new Object[]{"Строка", "10"});
        Long itemId = p.getItems().get(0).getId();
        Long offerId = jdbc.queryForObject("insert into client_offer (market, number, offer_date, status, version, origin) "
                + "values ('KZ', null, current_date, 'DRAFT', 0, 'BUILDER') returning id", Long.class);
        jdbc.update("insert into client_offer_item (offer_id, line_no, kind, name, unit, price_list_item_id) values (?, 1, 'ITEM', 'x', 'шт', ?)",
                offerId, itemId);
        lists.delete(p);
        lists.flush();
        assertThat(jdbc.queryForObject("select count(*) from price_list_item where id = ?", Integer.class, itemId)).isZero();
        assertThat(jdbc.queryForObject("select price_list_item_id from client_offer_item where offer_id = ?", Long.class, offerId)).isNull();
    }

    @Test
    void sameFileOfSupplier_twice_isRejected() {
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "Дубль");
        PriceList p = PriceListFixtures.list(lists, d, PriceListStatus.DRAFT, "RUB", null);
        assertThat(lists.findByDistributorIdAndSha256(d.getId(), p.getSha256())).isPresent();
        PriceList twin = PriceList.builder().market(Market.KZ).distributor(d).fileName("x").sha256(p.getSha256())
                .kind(ReplyFileKind.EXCEL).parseStatus(ReplyParseStatus.PARSED).build();
        assertThatThrownBy(() -> lists.saveAndFlush(twin)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void marketFilter_hidesOtherMarket() {
        Distributor kz = PriceListFixtures.supplier(distributors, Market.KZ, "KZ");
        PriceList p = PriceListFixtures.list(lists, kz, PriceListStatus.ACTIVE, "RUB", null);
        MarketContext.set(Market.RF);
        assertThat(lists.findAll()).extracting(PriceList::getId).doesNotContain(p.getId());
        MarketContext.set(Market.KZ);
        assertThat(lists.findAll()).extracting(PriceList::getId).contains(p.getId());
    }

    @Test
    void regRole() {
        Map<String, LineField> none = Map.of();
        assertThat(HeaderRoles.role("Разрешительные документы в Казахстане", none)).isEqualTo(LineField.REG);
        assertThat(HeaderRoles.role("Регистрационное удостоверение (номер, срок действия)", none)).isEqualTo(LineField.REG);
        assertThat(HeaderRoles.role("№ РУ", none)).isEqualTo(LineField.REG);
        assertThat(HeaderRoles.role("Регистрация в РК", none)).isEqualTo(LineField.REG);
        assertThat(HeaderRoles.role("Цена", none)).isEqualTo(LineField.PRICE);
        assertThat(HeaderRoles.role("№ п/п", none)).isEqualTo(LineField.NUMBER);
        assertThat(jdbc.queryForObject("select field from header_synonym where header_norm = 'регистрационное удостоверение'",
                String.class)).isEqualTo("REG");
    }

    @Test
    void currencyRateUnique() {
        jdbc.update("insert into currency_rate (market, currency, our_rate) values ('KZ', 'ZZZ', 6)");
        assertThatThrownBy(() -> jdbc.update("insert into currency_rate (market, currency, our_rate) values ('KZ', 'ZZZ', 7)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select our_rate from currency_rate where market = 'KZ' and currency = 'ZZZ'", BigDecimal.class))
                .isEqualByComparingTo("6");
    }
}
```

Проверка колонок `client_offer` в `insert` — `select column_name from information_schema.columns where table_name =
'client_offer' and is_nullable = 'NO' and column_default is null` (если у таблицы есть ещё обязательные колонки без
умолчания — дописать их в `insert` теста).

- [ ] **Step 3: Прогон — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.PriceListSchemaTest' 2>&1 | tail -25`
Expected: ошибка компиляции — нет `PriceList`, `PriceListColumn`, `LineField.REG`.

- [ ] **Step 4: Миграция V44**

```sql
-- Прайс-листы поставщиков и курсы валют (спека 2026-10-23-price-lists-and-rates §8): наш курс и курс НБ РК, прайсы и их
-- строки, файлы писем «прочее» на zakup@, исходная валюта у цены ответа и у закупки строки КП, роль колонки «РУ».
CREATE TABLE currency_rate (
    id         BIGSERIAL PRIMARY KEY,
    market     VARCHAR(2)     NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    our_rate   NUMERIC(14, 6) NOT NULL,
    updated_by VARCHAR(100),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_currency_rate UNIQUE (market, currency)
);

CREATE TABLE official_rate (
    id         BIGSERIAL PRIMARY KEY,
    source     VARCHAR(10)    NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    rate_date  DATE           NOT NULL,
    rate       NUMERIC(14, 6) NOT NULL,
    fetched_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_official_rate UNIQUE (source, currency, rate_date)
);

CREATE TABLE price_list (
    id               BIGSERIAL PRIMARY KEY,
    market           VARCHAR(2)    NOT NULL,
    distributor_id   BIGINT        NOT NULL REFERENCES distributor (id) ON DELETE CASCADE,
    status           VARCHAR(10)   NOT NULL DEFAULT 'DRAFT',
    list_date        DATE,
    valid_to         DATE,
    currency         VARCHAR(5),
    retail           BOOLEAN       NOT NULL DEFAULT FALSE,
    price_columns    JSONB         NOT NULL DEFAULT '[]',
    our_column       INT,
    discount_pct     NUMERIC(5, 2) NOT NULL DEFAULT 0,
    terms            VARCHAR(1000),
    file_name        VARCHAR(255)  NOT NULL,
    mime_type        VARCHAR(100),
    size_bytes       BIGINT,
    sha256           VARCHAR(64)   NOT NULL,
    data             BYTEA,
    kind             VARCHAR(10)   NOT NULL,
    parse_status     VARCHAR(20)   NOT NULL,
    parse_note       VARCHAR(300),
    inbound_email_id BIGINT REFERENCES inbound_email (id) ON DELETE SET NULL,
    created_by       VARCHAR(100),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    activated_by     VARCHAR(100),
    activated_at     TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    CONSTRAINT uq_price_list_file UNIQUE (distributor_id, sha256)
);
CREATE INDEX idx_price_list_market_status ON price_list (market, status);

CREATE TABLE price_list_item (
    id               BIGSERIAL PRIMARY KEY,
    price_list_id    BIGINT        NOT NULL REFERENCES price_list (id) ON DELETE CASCADE,
    line_no          INT           NOT NULL,
    section          VARCHAR(255),
    name             VARCHAR(1000) NOT NULL,
    model            VARCHAR(100),
    article          VARCHAR(100),
    prices           JSONB         NOT NULL DEFAULT '[]',
    our_price        NUMERIC(15, 2),
    reg_text         VARCHAR(500),
    reg_number       VARCHAR(100),
    no_reg           BOOLEAN       NOT NULL DEFAULT FALSE,
    site_product_id  BIGINT REFERENCES site_product (id) ON DELETE SET NULL,
    med_equipment_id BIGINT REFERENCES med_equipment (id) ON DELETE SET NULL,
    model_key        VARCHAR(100),
    file_row         INT,
    note             VARCHAR(500)
);
CREATE INDEX idx_price_list_item_list ON price_list_item (price_list_id);
CREATE INDEX idx_price_list_item_key ON price_list_item (model_key) WHERE model_key IS NOT NULL;
CREATE INDEX idx_price_list_item_site ON price_list_item (site_product_id) WHERE site_product_id IS NOT NULL;
CREATE INDEX idx_price_list_item_equipment ON price_list_item (med_equipment_id) WHERE med_equipment_id IS NOT NULL;

CREATE TABLE inbound_email_file (
    id               BIGSERIAL PRIMARY KEY,
    inbound_email_id BIGINT       NOT NULL REFERENCES inbound_email (id) ON DELETE CASCADE,
    file_name        VARCHAR(255) NOT NULL,
    mime_type        VARCHAR(100),
    size_bytes       BIGINT,
    sha256           VARCHAR(64)  NOT NULL,
    data             BYTEA        NOT NULL,
    price_like       BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_inbound_email_file_email ON inbound_email_file (inbound_email_id);

ALTER TABLE price_request_item ADD COLUMN response_currency VARCHAR(5);
ALTER TABLE price_request_item ADD COLUMN response_original NUMERIC(15, 2);
ALTER TABLE price_request_item ADD COLUMN response_rate NUMERIC(14, 6);

ALTER TABLE client_offer_item ADD COLUMN purchase_currency VARCHAR(5);
ALTER TABLE client_offer_item ADD COLUMN purchase_original NUMERIC(15, 2);
ALTER TABLE client_offer_item ADD COLUMN purchase_rate NUMERIC(14, 6);
ALTER TABLE client_offer_item ADD COLUMN price_list_item_id BIGINT REFERENCES price_list_item (id) ON DELETE SET NULL;

-- роль «РУ» (колонка «Разрешительные документы в Казахстане» прайса Аксиона и «Регистрационное удостоверение» КП поставщиков)
INSERT INTO header_synonym (header_norm, field) VALUES
    ('ру', 'REG'), ('№ ру', 'REG'), ('рег. удостоверение', 'REG'), ('регистрационное удостоверение', 'REG'),
    ('разрешительные документы', 'REG'), ('разрешительные документы в казахстане', 'REG'), ('регистрация в рк', 'REG')
ON CONFLICT (header_norm) DO NOTHING;
```

- [ ] **Step 5: Сущности**

`PriceListStatus.java`:

```java
package com.vladoose.nir.entity;

/** Прайс поставщика: DRAFT — загружен, проверяется; ACTIVE — сохранён, его цены в подсказках (спека §5.3). */
public enum PriceListStatus { DRAFT, ACTIVE }
```

`PriceListColumn.java`:

```java
package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Колонка цены прайса (JSONB price_list.price_columns): номер среди цен строки и заголовок, как в файле. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceListColumn(int index, String header) {}
```

`CurrencyRate.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Наш курс (спека 2026-10-23 §4.1): 1 единица валюты = ourRate валюты рынка. Не рыночная с явным рынком (как
 * reply_template): читается и пишется по MarketContext, без @Filter.
 */
@Entity
@Table(name = "currency_rate")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CurrencyRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "our_rate", nullable = false, precision = 14, scale = 6)
    private BigDecimal ourRate;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

`OfficialRate.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Официальный курс (спека §4.2): источник NBRK, курс за 1 единицу валюты в тенге на дату; история копится сама. */
@Entity
@Table(name = "official_rate")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OfficialRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal rate;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}
```

`PriceList.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Прайс поставщика (спека 2026-10-23 §5, §8). Рыночная сущность (§6 CLAUDE.md): @Filter + листенер; @FilterDef — только на
 * Tender. Строки — дети с cascade=ALL / orphanRemoval: менять только через items. @DynamicUpdate: правка шапки по полю не
 * переписывает байты файла.
 */
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@DynamicUpdate
@Entity
@Table(name = "price_list")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PriceList implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private PriceListStatus status = PriceListStatus.DRAFT;

    @Column(name = "list_date")
    private LocalDate listDate;

    @Column(name = "valid_to")
    private LocalDate validTo;

    /** Код валюты цен (KZT, RUB, USD, EUR, CNY; MIXED — не ясна; null — не названа). */
    @Column(length = 5)
    private String currency;

    /** Розничный прайс («рекомендованные розничные цены»): закупка — со скидкой. */
    @Column(nullable = false)
    private boolean retail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "price_columns", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<PriceListColumn> columns = new ArrayList<>();

    /** Номер колонки «наша закупка» среди цен строки; null — колонок цен нет. */
    @Column(name = "our_column")
    private Integer ourColumn;

    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal discountPct = BigDecimal.ZERO;

    @Column(length = 1000)
    private String terms;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "data")
    private byte[] data;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReplyFileKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 20)
    private ReplyParseStatus parseStatus;

    @Column(name = "parse_note", length = 300)
    private String parseNote;

    @Column(name = "inbound_email_id")
    private Long inboundEmailId;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "priceList", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC, id ASC")
    @Builder.Default
    private List<PriceListItem> items = new ArrayList<>();

    @PrePersist
    void stamp() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

`PriceListItem.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Строка прайса (спека §5.3): цены по колонкам прайса (null — ячейка пуста), закупка после скидки в валюте прайса,
 * РУ из прайса, связь с нашим товаром или позицией каталога, ключ модели («дкин12»). Не рыночная — через прайс.
 */
@Entity
@Table(name = "price_list_item")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PriceListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false)
    private PriceList priceList;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(length = 255)
    private String section;

    @Column(nullable = false, length = 1000)
    private String name;

    @Column(length = 100)
    private String model;

    @Column(length = 100)
    private String article;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<BigDecimal> prices = new ArrayList<>();

    @Column(name = "our_price", precision = 15, scale = 2)
    private BigDecimal ourPrice;

    @Column(name = "reg_text", length = 500)
    private String regText;

    @Column(name = "reg_number", length = 100)
    private String regNumber;

    @Column(name = "no_reg", nullable = false)
    private boolean noReg;

    @Column(name = "site_product_id")
    private Long siteProductId;

    @Column(name = "med_equipment_id")
    private Long medEquipmentId;

    @Column(name = "model_key", length = 100)
    private String modelKey;

    @Column(name = "file_row")
    private Integer fileRow;

    @Column(length = 500)
    private String note;
}
```

`InboundEmailFile.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

/** Файл письма «прочее» на zakup@ (Excel / Word / PDF) — для «Занести как прайс» во «Входящих» (спека §5.1). */
@Entity
@Table(name = "inbound_email_file")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InboundEmailFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inbound_email_id", nullable = false)
    private Long inboundEmailId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private byte[] data;

    /** «📒 похоже на прайс»: «прайс», «прейскурант», «price» в имени файла или теме письма. */
    @Column(name = "price_like", nullable = false)
    private boolean priceLike;
}
```

`PriceRequestItem.java` — после `responseFileRow`:

```java
    /** Исходная валюта, цена и курс ответа в чужой валюте (V44): цена в рублях × курс → responsePrice; в валюте рынка — пусто. */
    @Column(name = "response_currency", length = 5)
    private String responseCurrency;

    @Column(name = "response_original", precision = 15, scale = 2)
    private BigDecimal responseOriginal;

    @Column(name = "response_rate", precision = 14, scale = 6)
    private BigDecimal responseRate;
```

`ClientOfferItem.java` — после `priceRequestItemId`:

```java
    /** Закупка в чужой валюте (V44): purchasePrice = purchaseOriginal × purchaseRate (наш курс на момент ввода) считает сервер. */
    @Column(name = "purchase_currency", length = 5)
    private String purchaseCurrency;

    @Column(name = "purchase_original", precision = 15, scale = 2)
    private BigDecimal purchaseOriginal;

    @Column(name = "purchase_rate", precision = 14, scale = 6)
    private BigDecimal purchaseRate;

    /** Строка прайса, из которой закупка (V44); ON DELETE SET NULL — прайс удалили, закупка остаётся. */
    @Column(name = "price_list_item_id")
    private Long priceListItemId;
```

`LineField.java`:

```java
public enum LineField {
    NAME, MANUFACT, QUANTITY, IGNORE,
    /** Роли колонок КП поставщика (спека 2026-10-15 §3.2); экраны позиций клиента их не показывают (решение плана 12). */
    NUMBER, ARTICLE, UNIT, PRICE, SUM, VAT, CURRENCY, NOTE,
    /** РУ в РК — колонка прайса (спека 2026-10-23 §5.2); в цены не идёт. */
    REG
}
```

`HeaderRoles.heuristic` — вторым правилом, сразу после цены (иначе «…(номер, срок действия)» ушло бы в примечание по «срок»,
а «№ РУ» — в номер по «№»):

```java
        if (has(h, "цена", "стоимость за ед", "стоимость ед", "стоимость за 1")) return LineField.PRICE;
        if (has(h, "регистрац", "разрешительн", "рег. уд", "рег.уд") || h.equals("ру") || h.equals("№ ру")
                || h.startsWith("ру ") || h.endsWith(" ру")) return LineField.REG;
```

- [ ] **Step 6: Репозитории**

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.CurrencyRate;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CurrencyRateRepository extends JpaRepository<CurrencyRate, Long> {
    List<CurrencyRate> findByMarket(Market market);
    Optional<CurrencyRate> findByMarketAndCurrency(Market market, String currency);
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.OfficialRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface OfficialRateRepository extends JpaRepository<OfficialRate, Long> {
    /** Курс на дату не позже date — последний известный. */
    Optional<OfficialRate> findFirstBySourceAndCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String source, String currency, LocalDate date);
    Optional<OfficialRate> findBySourceAndCurrencyAndRateDate(String source, String currency, LocalDate date);
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PriceListRepository extends JpaRepository<PriceList, Long> {

    Optional<PriceList> findByDistributorIdAndSha256(Long distributorId, String sha256);

    /**
     * Прайсы рынка без байтов файла (фильтр рынка — аспектом): [id, distributorId, distributorName, status, listDate,
     * validTo, currency, fileName, kind, parseStatus, createdBy, createdAt, itemCount], новые сверху.
     */
    @Query("SELECT p.id, d.id, d.name, p.status, p.listDate, p.validTo, p.currency, p.fileName, p.kind, p.parseStatus, "
            + "p.createdBy, p.createdAt, (SELECT count(i) FROM PriceListItem i WHERE i.priceList = p) "
            + "FROM PriceList p JOIN p.distributor d ORDER BY p.createdAt DESC, p.id DESC")
    List<Object[]> findRows();

    /** Сколько прайсов у поставщиков рынка: [distributorId, count]. */
    @Query("SELECT p.distributor.id, count(p) FROM PriceList p GROUP BY p.distributor.id")
    List<Object[]> countByDistributor();
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.PriceListItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, Long> {
    List<PriceListItem> findByPriceListIdOrderByLineNoAscIdAsc(Long priceListId);
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.InboundEmailFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface InboundEmailFileRepository extends JpaRepository<InboundEmailFile, Long> {
    /** Без байтов: [id, inboundEmailId, fileName, priceLike]. */
    @Query("SELECT f.id, f.inboundEmailId, f.fileName, f.priceLike FROM InboundEmailFile f WHERE f.inboundEmailId IN :ids ORDER BY f.id")
    List<Object[]> findInfoByInboundIds(@Param("ids") Collection<Long> ids);
}
```

- [ ] **Step 7: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.PriceListSchemaTest' --tests 'com.vladoose.nir.supplierreply.*' 2>&1 | tail -25`
Expected: PASS (тесты разбора ответов поставщика не должны измениться: роль `REG` у их заголовков не встречалась; если
какой-то тест закреплял «Регистрационное удостоверение» как `IGNORE` — это и есть решение плана 13: поправить ожидание).

- [ ] **Step 8: Мутации**

1. Правило `REG` в `HeaderRoles` — после правила «срок» (в конец) → `regRole` краснеет («…(номер, срок действия)» → NOTE).
2. `@Filter` с `PriceList` снять → `marketFilter_hidesOtherMarket` краснеет.
Вернуть копией, `./gradlew compileJava`.

- [ ] **Step 9: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V44__price_lists_and_rates.sql src/main/java/com/vladoose/nir/entity src/main/java/com/vladoose/nir/repository src/main/java/com/vladoose/nir/service/supplierreply/HeaderRoles.java src/test/java/com/vladoose/nir/pricelist && git commit -m "feat(price-lists): V44 — курсы, прайсы, файлы писем, исходная валюта ответа и закупки, роль колонки «РУ»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 2: Курсы — наш курс, курс НБ РК, путь `/api/currency-rates`

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/rates/RateMath.java`, `CurrencyRates.java`, `RatesWriter.java`,
  `RatesScheduler.java`
- Create: `src/main/java/com/vladoose/nir/integration/nbrk/NbrkRate.java`, `NbrkRatesClient.java`,
  `NbrkRatesHttpClient.java`, `NbrkRatesParser.java`
- Create: `src/main/java/com/vladoose/nir/controller/CurrencyRateController.java`,
  `src/main/java/com/vladoose/nir/dto/request/CurrencyRatesRequest.java`,
  `src/main/java/com/vladoose/nir/dto/response/CurrencyRatesResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/supplierreply/SupplierReplyService.java` (`convert`, `rateText`, проверка
  курса в `setRate` — через `RateMath`), `src/main/resources/application.yaml` (блок `rates.nbrk`), `.env.example`
- Test: `src/test/java/com/vladoose/nir/rates/RateMathTest.java`, `NbrkRatesParserTest.java`, `NbrkRatesHttpClientTest.java`,
  `CurrencyRatesTest.java`, `RatesSchedulerTest.java`, `CurrencyRateApiTest.java`

**Interfaces:**
- Consumes: `CurrencyRateRepository`, `OfficialRateRepository` (задача 1); `UpstreamHttp.newClient / exchange`,
  `CronSchedules.register`, `ErrorText.of`, `CurrentUser.name`.
- Produces: `RateMath.convert(BigDecimal amount, BigDecimal rate) → BigDecimal|null`, `RateMath.text(BigDecimal) → «6»,
  «5,48»`, `RateMath.check(BigDecimal) → BigDecimal` (400 со своим текстом), `RateMath.marginPct(our, today) → BigDecimal|null`;
  `CurrencyRates.currencies(Market) → List<String>`, `our(Market, String) → Optional<BigDecimal>`, `ours(Market) →
  Map<String, BigDecimal>`, `today(Market, String) → Optional<CurrencyRates.Today>`, `today(Market) → Map<String, Today>`,
  `view(Market) → CurrencyRatesResponse`, `save(Market, CurrencyRatesRequest, String user) → CurrencyRatesResponse`;
  `record CurrencyRates.Today(BigDecimal rate, LocalDate date, boolean stale)`; `NbrkRatesClient.fetch(LocalDate) →
  List<NbrkRate>` (`record NbrkRate(String currency, BigDecimal rate)` — за 1 единицу); `RatesScheduler.run() → int`.

- [ ] **Step 1: Тесты пересчёта и разбора XML — красные**

`RateMathTest`:

```java
package com.vladoose.nir.rates;

import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.rates.RateMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateMathTest {

    @Test
    void convert_oneFraction_halfUp() {
        assertThat(RateMath.convert(new BigDecimal("277025"), new BigDecimal("6"))).isEqualByComparingTo("1662150.00");
        assertThat(RateMath.convert(new BigDecimal("150000"), new BigDecimal("5.48"))).isEqualByComparingTo("822000.00");
        // 0,125 × 1 = 0,125 → 0,13 (HALF_UP), а не 0,12
        assertThat(RateMath.convert(new BigDecimal("0.125"), BigDecimal.ONE)).isEqualByComparingTo("0.13");
        assertThat(RateMath.convert(new BigDecimal("9999999999999"), new BigDecimal("10"))).isNull();   // больше 13 цифр
        assertThat(RateMath.convert(null, BigDecimal.ONE)).isNull();
    }

    /** Оракул на целых (спека §12, как у расчёта КП): тиыны × миллионные доли курса — до тиына HALF_UP. */
    @Test
    void convert_matchesIntegerOracle() {
        java.util.Random rnd = new java.util.Random(20261023);
        for (int i = 0; i < 200_000; i++) {
            long cents = rnd.nextLong(0, 100_000_000_000L);           // до 1 млрд в валюте
            long micro = rnd.nextLong(1, 100_000_000_000L);           // курс до 100 000 с 6 знаками
            java.math.BigInteger exact = java.math.BigInteger.valueOf(cents).multiply(java.math.BigInteger.valueOf(micro))
                    .add(java.math.BigInteger.valueOf(500_000)).divide(java.math.BigInteger.valueOf(1_000_000));
            BigDecimal got = RateMath.convert(BigDecimal.valueOf(cents, 2), BigDecimal.valueOf(micro, 6));
            if (got == null) continue;                                 // больше 13 цифр — не пересчитывается
            assertThat(got.setScale(2).unscaledValue()).isEqualTo(exact);
        }
    }

    @Test
    void check_limits() {
        assertThat(RateMath.check(new BigDecimal("5.485555"))).isEqualByComparingTo("5.485555");
        assertThat(RateMath.check(null)).isNull();
        assertThatThrownBy(() -> RateMath.check(BigDecimal.ZERO)).isInstanceOf(BadRequestException.class).hasMessage("Курс — число больше нуля");
        assertThatThrownBy(() -> RateMath.check(new BigDecimal("100000.1"))).hasMessage("Курс слишком большой — не больше 100 000");
        assertThatThrownBy(() -> RateMath.check(new BigDecimal("1.1234567"))).hasMessage("Курс — не больше 6 знаков после запятой");
    }

    @Test
    void textAndMargin() {
        assertThat(RateMath.text(new BigDecimal("6.000000"))).isEqualTo("6");
        assertThat(RateMath.text(new BigDecimal("5.480000"))).isEqualTo("5,48");
        assertThat(RateMath.marginPct(new BigDecimal("6"), new BigDecimal("5.48"))).isEqualByComparingTo("9.5");
        assertThat(RateMath.marginPct(new BigDecimal("6"), null)).isNull();
    }
}
```

`NbrkRatesParserTest` (форма ответа — как у `nationalbank.kz/rss/get_rates.cfm` 22.10.2026):

```java
package com.vladoose.nir.rates;

import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.integration.nbrk.NbrkRatesParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NbrkRatesParserTest {

    static final String XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <rates>
                <generator>Alternate RSS Builder</generator>
                <title>Official exchange rates of National Bank of Republic Kazakhstan</title>
                <date>22.10.2026</date>
                <item><fullname>АРМЯНСКИЙ ДРАМ</fullname><title>AMD</title><description>12.5</description><quant>10</quant><index>DOWN</index><change>-0.05</change></item>
                <item><fullname>РОССИЙСКИЙ РУБЛЬ</fullname><title>RUB</title><description>5.48</description><quant>1</quant><index>UP</index><change>+0.01</change></item>
                <item><fullname>ДОЛЛАР США</fullname><title>USD</title><description>448.61</description><quant>1</quant></item>
                <item><fullname>ЕВРО</fullname><title>EUR</title><description>503.83</description><quant>1</quant></item>
                <item><fullname>ЮАНЬ ЖЭНЬМИНЬБИ</fullname><title>CNY</title><description>67.2</description><quant>1</quant></item>
                <item><title>XXX</title><description>не число</description><quant>1</quant></item>
            </rates>
            """;

    @Test
    void parse_perUnit() {
        List<NbrkRate> r = NbrkRatesParser.parse(XML.getBytes(StandardCharsets.UTF_8));
        assertThat(r).extracting(NbrkRate::currency).containsExactly("AMD", "RUB", "USD", "EUR", "CNY");
        assertThat(r.get(0).rate()).isEqualByComparingTo("1.25");          // 12,5 за 10 драм
        assertThat(r.get(1).rate()).isEqualByComparingTo("5.48");
    }

    @Test
    void garbage_isUpstreamError() {
        assertThatThrownBy(() -> NbrkRatesParser.parse("<html>Сервис недоступен".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("Нацбанк РК");
        assertThatThrownBy(() -> NbrkRatesParser.parse("<rates></rates>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("курсов нет");
    }

    @Test
    void doctype_isRejected() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rates><item><title>RUB</title>"
                + "<description>&x;</description><quant>1</quant></item></rates>";
        assertThatThrownBy(() -> NbrkRatesParser.parse(xxe.getBytes(StandardCharsets.UTF_8))).isInstanceOf(UpstreamException.class);
    }
}
```

- [ ] **Step 2: Прогон — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.rates.*' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `RateMath`, `NbrkRatesParser`.

- [ ] **Step 3: `RateMath` и разбор XML**

```java
package com.vladoose.nir.service.rates;

import com.vladoose.nir.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Пересчёт по курсу (спека 2026-10-23 §4.3) — один на всё: ответы поставщиков, прайсы, закупка КП. */
public final class RateMath {

    public static final BigDecimal MAX_RATE = new BigDecimal("100000");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private RateMath() {}

    /** Сумма × курс одной дробью до 0,01 (HALF_UP); не помещается в NUMERIC(15,2) — null (не пересчитывать). */
    public static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        if (amount == null || rate == null) return null;
        BigDecimal v = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return v.precision() - v.scale() > 13 ? null : v;
    }

    /** «6», «5,48» — курс без лишних нулей. */
    public static String text(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Курс из ввода: > 0, ≤ 100 000, ≤ 6 знаков после запятой; иначе 400. null — курса нет (снять). */
    public static BigDecimal check(BigDecimal rate) {
        if (rate == null) return null;
        if (rate.signum() <= 0) throw new BadRequestException("Курс — число больше нуля");
        if (rate.compareTo(MAX_RATE) > 0) throw new BadRequestException("Курс слишком большой — не больше 100 000");
        if (rate.stripTrailingZeros().scale() > 6) throw new BadRequestException("Курс — не больше 6 знаков после запятой");
        return rate;
    }

    /** Запас нашего курса к курсу сегодня, % до десятых: 6 при 5,48 — 9,5. */
    public static BigDecimal marginPct(BigDecimal our, BigDecimal today) {
        if (our == null || today == null || today.signum() <= 0) return null;
        return our.subtract(today).multiply(HUNDRED).divide(today, 1, RoundingMode.HALF_UP);
    }
}
```

```java
package com.vladoose.nir.integration.nbrk;

import java.math.BigDecimal;

/** Официальный курс НБ РК: тенге за 1 единицу валюты (у драма Нацбанк пишет «за 10» — делится при разборе). */
public record NbrkRate(String currency, BigDecimal rate) {}
```

```java
package com.vladoose.nir.integration.nbrk;

import java.time.LocalDate;
import java.util.List;

/** Курсы НБ РК на дату (спека §4.2); сбой — UpstreamException со своим текстом. */
public interface NbrkRatesClient {
    List<NbrkRate> fetch(LocalDate date);
}
```

```java
package com.vladoose.nir.integration.nbrk;

import com.vladoose.nir.exception.UpstreamException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Разбор `rss/get_rates.cfm`: item → title (код), description (курс), quant (за сколько единиц). DOCTYPE запрещён (XXE). */
public final class NbrkRatesParser {

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private NbrkRatesParser() {}

    public static List<NbrkRate> parse(byte[] xml) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new UpstreamException("Нацбанк РК: ответ не разобран");
        }
        List<NbrkRate> out = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element it = (Element) items.item(i);
            String code = text(it, "title");
            BigDecimal value = number(text(it, "description"));
            BigDecimal quant = number(text(it, "quant"));
            if (code == null || !CODE.matcher(code).matches() || value == null || value.signum() <= 0) continue;
            if (quant == null || quant.signum() <= 0) quant = BigDecimal.ONE;
            out.add(new NbrkRate(code, value.divide(quant, 6, RoundingMode.HALF_UP)));
        }
        if (out.isEmpty()) throw new UpstreamException("Нацбанк РК: курсов нет в ответе");
        return out;
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }

    private static BigDecimal number(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Прогон разбора — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.rates.RateMathTest' --tests 'com.vladoose.nir.rates.NbrkRatesParserTest' 2>&1 | tail -15`
Expected: PASS.

- [ ] **Step 5: Тест клиента Нацбанка — красный**

```java
package com.vladoose.nir.rates;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.http.UpstreamHttp;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.integration.nbrk.NbrkRatesHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NbrkRatesHttpClientTest {

    static HttpServer server;
    static final AtomicReference<String> query = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "nbrk-stub"); t.setDaemon(true); return t; }));
        server.createContext("/ok/rss/get_rates.cfm", ex -> {
            query.set(ex.getRequestURI().getQuery());
            send(ex, 200, NbrkRatesParserTest.XML);
        });
        server.createContext("/e500/rss/get_rates.cfm", ex -> send(ex, 500, "boom"));
        server.createContext("/stall/rss/get_rates.cfm", ex -> {
            ex.sendResponseHeaders(200, 1000);
            ex.getResponseBody().write("<rates>".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) { }
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static NbrkRatesHttpClient client(String path) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + path;
        return new NbrkRatesHttpClient(base, UpstreamHttp.newClient(HttpClient.Redirect.NEVER), Duration.ofMillis(800));
    }

    @Test
    void fetch_byDate() {
        List<NbrkRate> r = client("ok").fetch(LocalDate.of(2026, 10, 22));
        assertThat(query.get()).isEqualTo("fdate=22.10.2026");
        assertThat(r).extracting(NbrkRate::currency).contains("RUB", "USD");
    }

    @Test
    void http500_andStall_areUpstreamErrors() {
        assertThatThrownBy(() -> client("e500").fetch(LocalDate.of(2026, 10, 22)))
                .isInstanceOf(UpstreamException.class).hasMessage("Нацбанк РК: HTTP 500");
        assertThatThrownBy(() -> client("stall").fetch(LocalDate.of(2026, 10, 22)))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("нет ответа");
    }
}
```

- [ ] **Step 6: Клиент — зелёный**

```java
package com.vladoose.nir.integration.nbrk;

import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.http.UpstreamHttp;
import com.vladoose.nir.integration.http.UpstreamIoException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Официальные курсы НБ РК без ключа (спека §3): `GET {base}/rss/get_rates.cfm?fdate=ДД.ММ.ГГГГ`; дедлайн на весь ответ. */
@Component
public class NbrkRatesHttpClient implements NbrkRatesClient {

    static final Duration DEADLINE = Duration.ofSeconds(20);
    static final long MAX_BYTES = 1024 * 1024;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final String baseUrl;
    private final HttpClient http;
    private final Duration deadline;

    @Autowired
    public NbrkRatesHttpClient(@Value("${rates.nbrk.base-url:https://nationalbank.kz}") String baseUrl) {
        this(baseUrl, UpstreamHttp.newClient(HttpClient.Redirect.NORMAL), DEADLINE);
    }

    public NbrkRatesHttpClient(String baseUrl, HttpClient http, Duration deadline) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.http = http;
        this.deadline = deadline;
    }

    @Override
    public List<NbrkRate> fetch(LocalDate date) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(baseUrl + "/rss/get_rates.cfm?fdate=" + date.format(DATE)))
                    .header("Accept", "application/xml, text/xml").GET().build();
        } catch (IllegalArgumentException e) {
            throw new UpstreamException("Нацбанк РК: адрес не собирается — проверьте RATES_NBRK_BASE_URL");
        }
        HttpResponse<byte[]> r;
        try {
            r = UpstreamHttp.exchange(http, req, MAX_BYTES, deadline);
        } catch (UpstreamIoException e) {
            throw new UpstreamException("Нацбанк РК: " + e.getMessage());
        }
        if (r.statusCode() != 200) throw new UpstreamException("Нацбанк РК: HTTP " + r.statusCode());
        return NbrkRatesParser.parse(r.body());
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.rates.NbrkRatesHttpClientTest' 2>&1 | tail -15`
Expected: PASS.

- [ ] **Step 7: Тесты курсов и планировщика — красные**

`CurrencyRatesTest`:

```java
package com.vladoose.nir.rates;

import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.response.CurrencyRatesResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.OfficialRate;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.CurrencyRateRepository;
import com.vladoose.nir.repository.OfficialRateRepository;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Наш курс и курс сегодня (спека §4.1–4.2). Строки курса — общие для рынка: в откатываемой транзакции сперва чистим. */
@SpringBootTest
@Transactional
class CurrencyRatesTest {

    @Autowired CurrencyRateRepository ours;
    @Autowired OfficialRateRepository official;
    CurrencyRates rates;

    /** 23.10.2026 10:00 по Уральску. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T05:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        ours.deleteAllInBatch();
        official.deleteAllInBatch();
        rates = new CurrencyRates(ours, official, true, CLOCK);
    }

    private void nbrk(String currency, LocalDate date, String rate) {
        official.saveAndFlush(OfficialRate.builder().source(CurrencyRates.NBRK).currency(currency).rateDate(date)
                .rate(new BigDecimal(rate)).fetchedAt(OffsetDateTime.now()).build());
    }

    @Test
    void save_validatesAllBeforeWriting() {
        assertThatThrownBy(() -> rates.save(Market.KZ, new CurrencyRatesRequest(List.of(
                new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")),
                new CurrencyRatesRequest.Entry("GBP", new BigDecimal("600")))), "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Валюта «GBP» — не для этого рынка");
        assertThat(rates.our(Market.KZ, "RUB")).isEmpty();                 // ни одной записи: проверка — до записи
        assertThatThrownBy(() -> rates.save(Market.KZ, new CurrencyRatesRequest(List.of(
                new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")),
                new CurrencyRatesRequest.Entry("rub", new BigDecimal("7")))), "vlad")).hasMessage("Валюта RUB — дважды");
        assertThatThrownBy(() -> rates.save(Market.KZ, new CurrencyRatesRequest(List.of(
                new CurrencyRatesRequest.Entry("RUB", BigDecimal.ZERO))), "vlad")).hasMessage("Курс — число больше нуля");
    }

    @Test
    void save_upsertAndRemove() {
        CurrencyRatesResponse r = rates.save(Market.KZ, new CurrencyRatesRequest(List.of(
                new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        assertThat(rates.our(Market.KZ, "RUB").orElseThrow()).isEqualByComparingTo("6");
        assertThat(r.rates()).extracting(CurrencyRatesResponse.Row::currency).containsExactly("RUB", "USD", "EUR", "CNY");
        assertThat(r.rates().get(0).updatedBy()).isEqualTo("vlad");
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6.5")))), "vlad");
        assertThat(rates.our(Market.KZ, "RUB").orElseThrow()).isEqualByComparingTo("6.5");
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", null))), "vlad");
        assertThat(rates.our(Market.KZ, "RUB")).isEmpty();
        assertThat(rates.our(Market.RF, "RUB")).isEmpty();
        assertThat(rates.our(Market.KZ, "KZT")).contains(BigDecimal.ONE);  // валюта рынка — 1
    }

    @Test
    void today_lastNotAfterToday_staleAfter3Days() {
        nbrk("RUB", LocalDate.of(2026, 10, 22), "5.48");
        nbrk("RUB", LocalDate.of(2026, 10, 24), "5.50");                   // завтрашний — не сегодня
        CurrencyRates.Today t = rates.today(Market.KZ, "RUB").orElseThrow();
        assertThat(t.rate()).isEqualByComparingTo("5.48");
        assertThat(t.date()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(t.stale()).isFalse();
        nbrk("USD", LocalDate.of(2026, 10, 19), "448");
        assertThat(rates.today(Market.KZ, "USD").orElseThrow().stale()).isTrue();
        assertThat(rates.today(Market.RF, "USD")).isEmpty();               // у РФ курса сегодня нет
        assertThat(rates.today(Market.KZ, "KZT")).isEmpty();
    }

    @Test
    void view_marginToToday() {
        nbrk("RUB", LocalDate.of(2026, 10, 23), "5.48");
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        CurrencyRatesResponse.Row rub = rates.view(Market.KZ).rates().get(0);
        assertThat(rub.today().rate()).isEqualByComparingTo("5.48");
        assertThat(rub.marginPct()).isEqualByComparingTo("9.5");
        assertThat(rates.view(Market.RF).rates()).extracting(CurrencyRatesResponse.Row::currency).containsExactly("USD", "EUR", "CNY", "KZT");
    }
}
```

`RatesSchedulerTest`:

```java
package com.vladoose.nir.rates;

import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.integration.nbrk.NbrkRatesClient;
import com.vladoose.nir.service.rates.RatesScheduler;
import com.vladoose.nir.service.rates.RatesWriter;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RatesSchedulerTest {

    /** 22.10.2026 23:30 UTC = 23.10 04:30 по Уральску: дата курса — по Уральску. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T23:30:00Z"), ZoneOffset.UTC);

    @Test
    void run_fetchesTodayByOral_andStores() {
        AtomicReference<LocalDate> asked = new AtomicReference<>();
        NbrkRatesClient client = d -> { asked.set(d); return List.of(new NbrkRate("RUB", new BigDecimal("5.48"))); };
        RatesWriter writer = mock(RatesWriter.class);
        when(writer.store(any(), anyList(), any())).thenReturn(1);
        RatesScheduler s = new RatesScheduler(client, writer, true, "", CLOCK);
        assertThat(s.run()).isEqualTo(1);
        assertThat(asked.get()).isEqualTo(LocalDate.of(2026, 10, 23));
        verify(writer).store(eq(LocalDate.of(2026, 10, 23)), anyList(), any());
    }

    @Test
    void fetchFailure_keepsLastRate_noThrow() {
        NbrkRatesClient client = d -> { throw new UpstreamException("Нацбанк РК: HTTP 500"); };
        RatesWriter writer = mock(RatesWriter.class);
        RatesScheduler s = new RatesScheduler(client, writer, true, "", CLOCK);
        assertThatCode(s::runSafe).doesNotThrowAnyException();
        verifyNoInteractions(writer);                                       // прежние курсы не тронуты
    }

    @Test
    void disabled_registersNothing() {
        ScheduledTaskRegistrar reg = new ScheduledTaskRegistrar();
        new RatesScheduler(d -> List.of(), mock(RatesWriter.class), false, "", CLOCK).configureTasks(reg);
        assertThat(reg.getCronTaskList()).isEmpty();
        new RatesScheduler(d -> List.of(), mock(RatesWriter.class), true, "", CLOCK).configureTasks(reg);
        assertThat(reg.getCronTaskList()).hasSize(1);
    }
}
```

`CurrencyRateApiTest`:

```java
package com.vladoose.nir.rates;

import com.vladoose.nir.repository.CurrencyRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class CurrencyRateApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired CurrencyRateRepository ours;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ours.deleteAllInBatch();
    }

    @Test
    @WithMockUser(username = "operator", roles = "USER")
    void operator_readsButCannotWrite() throws Exception {
        mvc.perform(get("/api/currency-rates").header("X-Market", "KZ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].currency").value("RUB"));
        mvc.perform(put("/api/currency-rates").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rates\":[{\"currency\":\"RUB\",\"ourRate\":6}]}")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void admin_saves_marketsSeparate() throws Exception {
        mvc.perform(put("/api/currency-rates").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rates\":[{\"currency\":\"RUB\",\"ourRate\":6}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].ourRate").value(6.0))
                .andExpect(jsonPath("$.rates[0].updatedBy").value("vlad"));
        mvc.perform(get("/api/currency-rates").header("X-Market", "RF"))
                .andExpect(jsonPath("$.rates[0].currency").value("USD"))
                .andExpect(jsonPath("$.rates[0].ourRate").doesNotExist());
        mvc.perform(put("/api/currency-rates").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rates\":[{\"currency\":\"RUB\",\"ourRate\":-1}]}")).andExpect(status().isBadRequest());
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.rates.*' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `CurrencyRates`, `RatesScheduler`, `CurrencyRatesRequest`.

- [ ] **Step 8: Курсы, запись, планировщик, путь**

`dto/request/CurrencyRatesRequest.java`:

```java
package com.vladoose.nir.dto.request;

import java.math.BigDecimal;
import java.util.List;

/** «Курсы валют» — весь список одним PUT (спека §4.1); ourRate пусто — курс снят. */
public record CurrencyRatesRequest(List<Entry> rates) {
    public record Entry(String currency, BigDecimal ourRate) {}
}
```

`dto/response/CurrencyRatesResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * «Система → Курсы валют» (спека §4.1): по валюте рынка — наш курс, курс НБ РК сегодня (KZ), запас в %. todayEnabled —
 * курс подтягивается (RATES_NBRK_ENABLED).
 */
public record CurrencyRatesResponse(String market, String marketCurrency, boolean todayEnabled, List<Row> rates) {
    public record Row(String currency, BigDecimal ourRate, String updatedBy, OffsetDateTime updatedAt, Today today, BigDecimal marginPct) {}
    public record Today(BigDecimal rate, LocalDate date, boolean stale) {}
}
```

`service/rates/CurrencyRates.java`:

```java
package com.vladoose.nir.service.rates;

import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.response.CurrencyRatesResponse;
import com.vladoose.nir.entity.CurrencyRate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.CurrencyRateRepository;
import com.vladoose.nir.repository.OfficialRateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Наш курс и курс сегодня (спека 2026-10-23 §4). Наш курс — от него наценка и цена клиенту; курс сегодня (НБ РК, только
 * KZ) — настоящая закупка и прибыль. Валюта рынка — курс 1, курса сегодня у неё нет.
 */
@Service
public class CurrencyRates {

    public static final String NBRK = "NBRK";
    static final int STALE_DAYS = 3;

    public record Today(BigDecimal rate, LocalDate date, boolean stale) {}

    private final CurrencyRateRepository ours;
    private final OfficialRateRepository official;
    private final boolean todayEnabled;
    private final Clock clock;

    @Autowired
    public CurrencyRates(CurrencyRateRepository ours, OfficialRateRepository official,
                         @Value("${rates.nbrk.enabled:false}") boolean todayEnabled) {
        this(ours, official, todayEnabled, Clock.systemUTC());
    }

    public CurrencyRates(CurrencyRateRepository ours, OfficialRateRepository official, boolean todayEnabled, Clock clock) {
        this.ours = ours;
        this.official = official;
        this.todayEnabled = todayEnabled;
        this.clock = clock;
    }

    /** Валюты нашего курса рынка (в таком порядке — на странице). */
    public static List<String> currencies(Market m) {
        return m == Market.KZ ? List.of("RUB", "USD", "EUR", "CNY") : List.of("USD", "EUR", "CNY", "KZT");
    }

    public Optional<BigDecimal> our(Market m, String currency) {
        if (m == null || currency == null) return Optional.empty();
        if (currency.equals(m.currencyCode())) return Optional.of(BigDecimal.ONE);
        return ours.findByMarketAndCurrency(m, currency).map(CurrencyRate::getOurRate);
    }

    public Map<String, BigDecimal> ours(Market m) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (CurrencyRate r : ours.findByMarket(m)) out.put(r.getCurrency(), r.getOurRate());
        return out;
    }

    public Optional<Today> today(Market m, String currency) {
        if (m != Market.KZ || currency == null || currency.equals(m.currencyCode())) return Optional.empty();
        LocalDate now = LocalDate.now(clock.withZone(m.zone()));
        return official.findFirstBySourceAndCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(NBRK, currency, now)
                .map(r -> new Today(r.getRate(), r.getRateDate(), r.getRateDate().isBefore(now.minusDays(STALE_DAYS))));
    }

    public Map<String, Today> today(Market m) {
        Map<String, Today> out = new LinkedHashMap<>();
        for (String c : currencies(m)) today(m, c).ifPresent(t -> out.put(c, t));
        return out;
    }

    @Transactional(readOnly = true)
    public CurrencyRatesResponse view(Market m) {
        Map<String, CurrencyRate> stored = new HashMap<>();
        for (CurrencyRate r : ours.findByMarket(m)) stored.put(r.getCurrency(), r);
        List<CurrencyRatesResponse.Row> rows = new ArrayList<>();
        for (String c : currencies(m)) {
            CurrencyRate r = stored.get(c);
            Today t = today(m, c).orElse(null);
            rows.add(new CurrencyRatesResponse.Row(c, r == null ? null : r.getOurRate(), r == null ? null : r.getUpdatedBy(),
                    r == null ? null : r.getUpdatedAt(), t == null ? null : new CurrencyRatesResponse.Today(t.rate(), t.date(), t.stale()),
                    RateMath.marginPct(r == null ? null : r.getOurRate(), t == null ? null : t.rate())));
        }
        return new CurrencyRatesResponse(m.name(), m.currencyCode(), todayEnabled && m == Market.KZ, rows);
    }

    /** Весь список: сначала проверка всего ввода, потом запись (спека §4.1); пустой курс — строка удаляется. */
    @Transactional
    public CurrencyRatesResponse save(Market m, CurrencyRatesRequest req, String user) {
        Map<String, BigDecimal> wanted = new LinkedHashMap<>();
        for (CurrencyRatesRequest.Entry e : req == null || req.rates() == null ? List.<CurrencyRatesRequest.Entry>of() : req.rates()) {
            if (e == null || e.currency() == null || e.currency().isBlank()) throw new BadRequestException("Не указана валюта");
            String c = e.currency().trim().toUpperCase(Locale.ROOT);
            if (!currencies(m).contains(c)) throw new BadRequestException("Валюта «" + c + "» — не для этого рынка");
            if (wanted.containsKey(c)) throw new BadRequestException("Валюта " + c + " — дважды");
            wanted.put(c, RateMath.check(e.ourRate()));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Map.Entry<String, BigDecimal> w : wanted.entrySet()) {
            Optional<CurrencyRate> row = ours.findByMarketAndCurrency(m, w.getKey());
            if (w.getValue() == null) {
                row.ifPresent(ours::delete);
                continue;
            }
            CurrencyRate r = row.orElseGet(() -> CurrencyRate.builder().market(m).currency(w.getKey()).build());
            r.setOurRate(w.getValue());
            r.setUpdatedBy(user);
            r.setUpdatedAt(now);
            ours.save(r);
        }
        ours.flush();
        return view(m);
    }
}
```

`service/rates/RatesWriter.java`:

```java
package com.vladoose.nir.service.rates;

import com.vladoose.nir.entity.OfficialRate;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.repository.OfficialRateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Запись курсов НБ РК на дату (отдельный бин — сеть вне транзакции, CLAUDE.md §6); курс на ту же дату перезаписывается. */
@Component
public class RatesWriter {

    static final Set<String> WANTED = Set.of("RUB", "USD", "EUR", "CNY");

    private final OfficialRateRepository repo;

    public RatesWriter(OfficialRateRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public int store(LocalDate date, List<NbrkRate> rates, OffsetDateTime now) {
        int n = 0;
        for (NbrkRate r : rates) {
            if (!WANTED.contains(r.currency())) continue;
            OfficialRate row = repo.findBySourceAndCurrencyAndRateDate(CurrencyRates.NBRK, r.currency(), date)
                    .orElseGet(() -> OfficialRate.builder().source(CurrencyRates.NBRK).currency(r.currency()).rateDate(date).build());
            row.setRate(r.rate());
            row.setFetchedAt(now);
            repo.save(row);
            n++;
        }
        return n;
    }
}
```

`service/rates/RatesScheduler.java`:

```java
package com.vladoose.nir.service.rates;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.CronSchedules;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.integration.nbrk.NbrkRatesClient;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Курс НБ РК (спека §4.2): при старте и по расписанию RATES_NBRK_CRON (умолчание — раз в 3 часа в :07, Уральск), свой поток
 * `rates-nbrk`. Выключатель RATES_NBRK_ENABLED (по умолчанию выключено). Сбой — WARN, прежние курсы остаются.
 */
@Component
public class RatesScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RatesScheduler.class);
    static final String DEFAULT_CRON = "0 7 */3 * * *";
    static final ZoneId ZONE = ZoneId.of("Asia/Oral");

    private final NbrkRatesClient client;
    private final RatesWriter writer;
    private final boolean enabled;
    private final String cron;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rates-nbrk");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public RatesScheduler(NbrkRatesClient client, RatesWriter writer, @Value("${rates.nbrk.enabled:false}") boolean enabled,
                          @Value("${rates.nbrk.cron:}") String cron) {
        this(client, writer, enabled, cron, Clock.systemUTC());
    }

    public RatesScheduler(NbrkRatesClient client, RatesWriter writer, boolean enabled, String cron, Clock clock) {
        this.client = client;
        this.writer = writer;
        this.enabled = enabled;
        this.cron = cron;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        if (enabled) executor.execute(this::runSafe);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) return;
        CronSchedules.register(registrar, "Курс НБ РК", cron, DEFAULT_CRON, ZONE, () -> executor.execute(this::runSafe), log);
    }

    /** Один проход: курс на сегодня по Уральску. Ошибка — исключением (её ловит runSafe). */
    public int run() {
        LocalDate today = LocalDate.now(clock.withZone(Market.KZ.zone()));
        List<NbrkRate> rates = client.fetch(today);
        int n = writer.store(today, rates, OffsetDateTime.now(clock));
        log.info("Курс НБ РК на {}: {}", today, rates.stream().filter(r -> RatesWriter.WANTED.contains(r.currency()))
                .map(r -> r.currency() + " " + RateMath.text(r.rate())).collect(Collectors.joining(", ")));
        return n;
    }

    public void runSafe() {
        try {
            run();
        } catch (Throwable e) {
            log.warn("Курс НБ РК не получен, остаётся прежний: {}", ErrorText.of(e));
        }
    }
}
```

`controller/CurrencyRateController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.response.CurrencyRatesResponse;
import com.vladoose.nir.service.rates.CurrencyRates;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** «Система → Курсы валют» (спека §4.1, §9): читать — все, менять — администратор. */
@RestController
@RequestMapping("/api/currency-rates")
public class CurrencyRateController {

    private final CurrencyRates rates;

    public CurrencyRateController(CurrencyRates rates) {
        this.rates = rates;
    }

    @GetMapping
    public CurrencyRatesResponse get() {
        return rates.view(MarketContext.get());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public CurrencyRatesResponse save(@RequestBody CurrencyRatesRequest req) {
        return rates.save(MarketContext.get(), req, CurrentUser.name());
    }
}
```

`application.yaml` — после блока `tender-results`:

```yaml
rates:
  nbrk:
    enabled: ${RATES_NBRK_ENABLED:false}    # курс НБ РК сегодня; в .env пустым не оставлять — пустой булев флаг роняет старт
    cron: ${RATES_NBRK_CRON:}               # пусто — раз в 3 часа в :07 по Уральску; «-» выключает расписание
    base-url: ${RATES_NBRK_BASE_URL:https://nationalbank.kz}
```

`.env.example` — после блока итогов тендеров:

```
# Курс НБ РК сегодня (спека 2026-10-23-price-lists-and-rates §4.2): true — при старте и раз в 3 часа; пустым не оставлять
RATES_NBRK_ENABLED=false
```

`SupplierReplyService`: в `setRate` три проверки курса заменить вызовом `RateMath.check(rate)` (тексты те же); `convert` —
`return RateMath.convert(price, rate);`; `rateText` — `return RateMath.text(rate);`; `MAX_RATE` — `RateMath.MAX_RATE`.

- [ ] **Step 9: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.rates.*' --tests 'com.vladoose.nir.supplierreply.*' 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 10: Мутации**

1. `today`: `isBefore(now.minusDays(STALE_DAYS))` → `isAfter(…)` → `today_lastNotAfterToday_staleAfter3Days` краснеет.
2. `today`: `LessThanEqual` по `now.plusDays(1)` → тот же тест (взял бы завтрашний 5,50).
3. `save`: запись в первом цикле (без отдельной проверки) → `save_validatesAllBeforeWriting` краснеет.
4. `RatesScheduler.run`: дата по UTC вместо Уральска → `run_fetchesTodayByOral_andStores` краснеет.
5. `@PreAuthorize` снять с `save` → `operator_readsButCannotWrite` краснеет.
6. Парсер: убрать `disallow-doctype-decl` → `doctype_isRejected` краснеет (или падает чтением файла — тоже красный).
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 11: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/rates src/main/java/com/vladoose/nir/integration/nbrk src/main/java/com/vladoose/nir/controller/CurrencyRateController.java src/main/java/com/vladoose/nir/dto/request/CurrencyRatesRequest.java src/main/java/com/vladoose/nir/dto/response/CurrencyRatesResponse.java src/main/java/com/vladoose/nir/service/supplierreply/SupplierReplyService.java src/main/resources/application.yaml .env.example src/test/java/com/vladoose/nir/rates && git commit -m "feat(rates): наш курс по валютам рынка и курс НБ РК сегодня, путь /api/currency-rates

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 3: Ответ поставщика в чужой валюте — наш курс сам, исходная цена, ввод цены в рублях

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/supplierreply/SupplierReplyService.java` (наш курс в `inheritRate`,
  исходная цена в `apply` и `clear`, `ReplyOutcome` + `ourRate` / `today`, `foreignFromRow`, `tableCurrency` — public)
- Modify: `src/main/java/com/vladoose/nir/service/supplierreply/SupplierReplyViewBuilder.java` (`Rate` + наш курс и курс
  сегодня, `Position` + валюта, исходная цена, курс, цена по курсу сегодня),
  `src/main/java/com/vladoose/nir/dto/response/SupplierReplyView.java`
- Modify: `src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java` (`fileLine`: «по нашему курсу 6 (сегодня 5,48)»)
- Modify: `src/main/java/com/vladoose/nir/controller/PriceRequestController.java` (`ItemResponseDto` + `responseCurrency`,
  `responseOriginal`; правила валюты в `updateResponses`)
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestPricesService.java`,
  `src/main/java/com/vladoose/nir/dto/response/PrivateRequestPricesResponse.java` (у ячейки — валюта, исходная цена, курс,
  цена по курсу сегодня)
- Modify: `src/main/java/com/vladoose/nir/dto/response/PriceRequestItemResponse.java` (3 поля — для карточки тендера)
- Test: `src/test/java/com/vladoose/nir/service/supplierreply/SupplierReplyOurRateTest.java`,
  `src/test/java/com/vladoose/nir/service/supplierreply/PriceResponseCurrencyTest.java`,
  `src/test/java/com/vladoose/nir/service/mail/MailFileLineTest.java` (новый тест + новый конструктор `ReplyOutcome`),
  `src/test/java/com/vladoose/nir/service/supplierreply/SupplierReplyApiTest.java` (в `setUp` — `currency_rate` пуст)

**Interfaces:**
- Consumes: `CurrencyRates.our / today`, `RateMath.convert / text` (задача 2); поля `PriceRequestItem.response*` (задача 1).
- Produces: `SupplierReplyService.foreignFromRow(PriceRequestItem, SupplierReplyFile, int fileRow, String market)`,
  `SupplierReplyService.tableCurrency(List<ReplyRowValues>, ReplyTable)` — public; `ReplyOutcome(…, boolean ourRate,
  BigDecimal today)` (старый конструктор оставлен); `SupplierReplyView.Rate(currency, value, suggestion, suggestedAt, our,
  today, todayDate)`; `SupplierReplyView.Position(…, currency, original, rate, today)`; `ItemResponseDto(itemId,
  responsePrice, responseNote, fileId, fileRow, clearFile, responseCurrency, responseOriginal)`;
  `PrivateRequestPricesResponse.Cell(…, currency, original, rate, today)`.

- [ ] **Step 1: Тесты — красные**

`SupplierReplyOurRateTest` — та же частная заявка, что в `SupplierReplyApiTest.newRequest`:

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.response.SupplierReplyView;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static com.vladoose.nir.service.supplierreply.ReplyFixtures.parsed;
import static org.assertj.core.api.Assertions.assertThat;

/** Наш курс в ответе поставщика (спека 2026-10-23 §4.4): цены сразу, исходная цена у позиции, курс сегодня на экране. */
@SpringBootTest
@Transactional
class SupplierReplyOurRateTest {

    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository items;
    @Autowired SupplierReplyService replies;
    @Autowired SupplierReplyViewBuilder views;
    @Autowired CurrencyRates rates;
    @Autowired CurrencyRateRepository ours;
    @Autowired OfficialRateRepository official;
    PriceRequest pr;

    @BeforeEach
    void setUp() {
        ours.deleteAllInBatch();
        official.deleteAllInBatch();
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Дефибриллятор ДКИ-Н-12", "Аксион", 1, null, null, null)), LinesMode.APPEND);
        Distributor d = distributors.save(Distributor.builder().name("ZZ Аксион курс " + System.nanoTime()).build());
        pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").sentAt(OffsetDateTime.now()).build());
        pr.getItems().add(items.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(t.getLots().get(0)).requestedQuantity(1).build()));
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ReplyTable rub(String price) {
        return new ReplyTable("Лист1", 4, 3, "RUB", List.of(
                new ReplyTable.Column(0, "Наименование", LineField.NAME), new ReplyTable.Column(1, "Кол-во", LineField.QUANTITY),
                new ReplyTable.Column(2, "Цена, руб.", LineField.PRICE)),
                List.of(new ReplyTable.Row(5, List.of("Дефибриллятор ДКИ-Н-12 Аксион", "1", price))));
    }

    private void ourRub(String rate) {
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal(rate)))), "vlad");
    }

    @Test
    void ourRate_appliesAtOnce_originalStored() {
        ourRub("6");
        official.saveAndFlush(OfficialRate.builder().source(CurrencyRates.NBRK).currency("RUB").rateDate(LocalDate.now(Market.KZ.zone()))
                .rate(new BigDecimal("5.48")).fetchedAt(OffsetDateTime.now()).build());
        SupplierReplyService.ReplyOutcome o = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП руб.xlsx", rub("150 000"))), null);
        assertThat(o.applied()).isEqualTo(1);
        assertThat(o.rate()).isEqualByComparingTo("6");
        assertThat(o.ourRate()).isTrue();
        assertThat(o.today()).isEqualByComparingTo("5.48");
        PriceRequestItem it = items.findById(pr.getItems().get(0).getId()).orElseThrow();
        assertThat(it.getResponsePrice()).isEqualByComparingTo("900000");
        assertThat(it.isResponseAuto()).isTrue();
        assertThat(it.getResponseCurrency()).isEqualTo("RUB");
        assertThat(it.getResponseOriginal()).isEqualByComparingTo("150000");
        assertThat(it.getResponseRate()).isEqualByComparingTo("6");

        SupplierReplyView v = views.view(pr.getId(), null);
        assertThat(v.rate().our()).isEqualByComparingTo("6");
        assertThat(v.rate().today()).isEqualByComparingTo("5.48");
        assertThat(v.rate().suggestion()).isEqualByComparingTo("6");          // подсказка — наш курс, не «в прошлый раз»
        assertThat(v.positions().get(0).original()).isEqualByComparingTo("150000");
        assertThat(v.positions().get(0).today()).isEqualByComparingTo("822000");
    }

    @Test
    void withoutOurRate_asBefore_pricesWait() {
        SupplierReplyService.ReplyOutcome o = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП руб.xlsx", rub("150 000"))), null);
        assertThat(o.applied()).isZero();
        assertThat(o.foreignCurrency()).isEqualTo("RUB");
        assertThat(items.findById(pr.getItems().get(0).getId()).orElseThrow().getResponsePrice()).isNull();
    }

    @Test
    void rateOfThisRequest_winsOverOurs() {
        ourRub("6");
        Long first = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП руб.xlsx", rub("150 000"))), null).fileIds().get(0);
        replies.setRate(pr.getId(), first, new BigDecimal("5.9"));
        SupplierReplyService.ReplyOutcome o = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП руб 2.xlsx", rub("151 000"))), null);
        assertThat(o.rate()).isEqualByComparingTo("5.9");
        assertThat(o.ourRate()).isFalse();
        assertThat(items.findById(pr.getItems().get(0).getId()).orElseThrow().getResponsePrice()).isEqualByComparingTo("890900");
    }

    @Test
    void revertedAuto_clearsOriginal() {
        ourRub("6");
        Long f = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП руб.xlsx", rub("150 000"))), null).fileIds().get(0);
        replies.setRate(pr.getId(), f, null);                                // «Снять курс»
        PriceRequestItem it = items.findById(pr.getItems().get(0).getId()).orElseThrow();
        assertThat(it.getResponsePrice()).isNull();
        assertThat(it.getResponseCurrency()).isNull();
        assertThat(it.getResponseOriginal()).isNull();
    }
}
```

`PriceResponseCurrencyTest` — путь `PUT /api/price-requests/{id}/responses` (решения плана 4–5):

```java
package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static com.vladoose.nir.service.supplierreply.ReplyFixtures.parsed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class PriceResponseCurrencyTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository items;
    @Autowired SupplierReplyService replies;
    @Autowired CurrencyRates rates;
    @Autowired CurrencyRateRepository ours;
    MockMvc mvc;
    PriceRequest pr;
    Long itemId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ours.deleteAllInBatch();
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Дефибриллятор ДКИ-Н-12", "Аксион", 1, null, null, null)), LinesMode.APPEND);
        Distributor d = distributors.save(Distributor.builder().name("ZZ Ввод в рублях " + System.nanoTime()).build());
        pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").sentAt(OffsetDateTime.now()).build());
        PriceRequestItem it = items.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(t.getLots().get(0)).requestedQuantity(1).build());
        pr.getItems().add(it);
        itemId = it.getId();
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private void putJson(String body, int status) throws Exception {
        MarketContext.clear();
        mvc.perform(put("/api/price-requests/" + pr.getId() + "/responses").header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status));
        MarketContext.set(Market.KZ);
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void rubles_byOurRate_or400() throws Exception {
        putJson("[{\"itemId\":" + itemId + ",\"responseCurrency\":\"RUB\",\"responseOriginal\":150000}]", 400);   // нашего курса нет
        MarketContext.set(Market.KZ);
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        putJson("[{\"itemId\":" + itemId + ",\"responseCurrency\":\"RUB\",\"responseOriginal\":150000}]", 200);
        PriceRequestItem it = items.findById(itemId).orElseThrow();
        assertThat(it.getResponsePrice()).isEqualByComparingTo("900000");
        assertThat(it.getResponseCurrency()).isEqualTo("RUB");
        assertThat(it.getResponseOriginal()).isEqualByComparingTo("150000");
        assertThat(it.getResponseRate()).isEqualByComparingTo("6");
    }

    /** «Сохранить ответы» карточки тендера шлёт строку без валюты: цена та же — рубли остаются, другая — снимаются. */
    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void samePriceWithoutCurrency_keepsOriginal_changedClears() throws Exception {
        MarketContext.set(Market.KZ);
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        putJson("[{\"itemId\":" + itemId + ",\"responseCurrency\":\"RUB\",\"responseOriginal\":150000}]", 200);
        putJson("[{\"itemId\":" + itemId + ",\"responsePrice\":900000.00,\"responseNote\":\"срок 30 дней\"}]", 200);
        assertThat(items.findById(itemId).orElseThrow().getResponseCurrency()).isEqualTo("RUB");
        putJson("[{\"itemId\":" + itemId + ",\"responsePrice\":880000}]", 200);
        PriceRequestItem it = items.findById(itemId).orElseThrow();
        assertThat(it.getResponsePrice()).isEqualByComparingTo("880000");
        assertThat(it.getResponseCurrency()).isNull();
        assertThat(it.getResponseOriginal()).isNull();
    }

    /** Выбор строки файла в рублях («Другая строка ▾»): исходная цена и курс — из самой строки файла (решение плана 4). */
    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void pickedFileRow_originalFromRow() throws Exception {
        MarketContext.set(Market.KZ);
        Long fileId = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(),
                List.of(parsed("КП руб.xlsx", SupplierReplyOurRateTest.rub("150 000"))), null).fileIds().get(0);
        replies.setRate(pr.getId(), fileId, new BigDecimal("5.9"));
        putJson("[{\"itemId\":" + itemId + ",\"responsePrice\":1,\"fileId\":" + fileId + ",\"fileRow\":5}]", 200);
        PriceRequestItem it = items.findById(itemId).orElseThrow();
        assertThat(it.getResponsePrice()).isEqualByComparingTo("885000");    // 150 000 × 5,9 — сервер считает сам
        assertThat(it.getResponseOriginal()).isEqualByComparingTo("150000");
        assertThat(it.getResponseRate()).isEqualByComparingTo("5.9");
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void marketCurrency_clearsForeign() throws Exception {
        MarketContext.set(Market.KZ);
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        putJson("[{\"itemId\":" + itemId + ",\"responseCurrency\":\"RUB\",\"responseOriginal\":150000}]", 200);
        putJson("[{\"itemId\":" + itemId + ",\"responseCurrency\":\"KZT\",\"responsePrice\":900000}]", 200);
        assertThat(items.findById(itemId).orElseThrow().getResponseCurrency()).isNull();
        putJson("[{\"itemId\":" + itemId + ",\"responseCurrency\":\"RUB\",\"responseOriginal\":-5}]", 400);
    }
}
```

`MailFileLineTest` — новый тест (старые вызовы `ReplyOutcome` — прежним конструктором, он остаётся):

```java
    @Test
    void ourRate_withToday() {
        var o = new ReplyOutcome(List.of(1L), "КП руб.xlsx", "КП руб.xlsx", 3, 3, 0, null, false, false, null, false,
                new BigDecimal("6"), null, List.of(), true, new BigDecimal("5.48"));
        assertThat(MailIngestWriter.fileLine(o)).isEqualTo("💡 Цены из файла «КП руб.xlsx»: подставлено 3 из 3 по нашему курсу 6 (сегодня 5,48) — сверьте");
    }
```

В `SupplierReplyApiTest.setUp` первой строкой — `ours.deleteAllInBatch();` (поле `@Autowired CurrencyRateRepository ours;`):
его тесты курса проверяют поведение без нашего курса, а живая проверка оставляет строки курса в nirdb.

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.supplierreply.SupplierReplyOurRateTest' --tests 'com.vladoose.nir.service.supplierreply.PriceResponseCurrencyTest' --tests 'com.vladoose.nir.service.mail.MailFileLineTest' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `ReplyOutcome.ourRate()`, `Rate.our()`, `Position.original()`.

- [ ] **Step 2: `SupplierReplyService` — наш курс, исходная цена**

Поле и конструктор: `private final CurrencyRates rates;` — последним параметром конструктора (Spring собирает сам).

`ReplyOutcome` — два поля в конце и прежний конструктор:

```java
    public record ReplyOutcome(List<Long> fileIds, String fileName, String parsedName, int applied, int positions,
                               int manualKept, String foreignCurrency, boolean viewOnly, boolean image, String unreadable,
                               boolean closed, BigDecimal rate, String textPricesFile, List<TextPrice> textPrices,
                               boolean ourRate, BigDecimal today) {
        public ReplyOutcome(List<Long> fileIds, String fileName, String parsedName, int applied, int positions, int manualKept,
                            String foreignCurrency, boolean viewOnly, boolean image, String unreadable, boolean closed,
                            BigDecimal rate, String textPricesFile, List<TextPrice> textPrices) {
            this(fileIds, fileName, parsedName, applied, positions, manualKept, foreignCurrency, viewOnly, image, unreadable,
                    closed, rate, textPricesFile, textPrices, false, null);
        }

        public static ReplyOutcome none() {
            return new ReplyOutcome(List.of(), null, null, 0, 0, 0, null, false, false, null, false, null, null, List.of());
        }
    }
```

В `ingest` — последний `return`:

```java
        ApplyResult a = apply(pr, best.get());
        String rateCur = best.get().getRateCurrency();
        boolean ourRate = a.rate() != null && rates.our(pr.getMarket(), rateCur).map(o -> o.compareTo(a.rate()) == 0).orElse(false);
        BigDecimal today = a.rate() == null ? null : rates.today(pr.getMarket(), rateCur).map(CurrencyRates.Today::rate).orElse(null);
        return new ReplyOutcome(ids, name, parsedName, a.applied(), a.positions(), a.manualKept(), a.foreignCurrency(),
                view, image, unreadable, false, a.rate(), null, List.of(), ourRate, today);
```

`inheritRate` — после «курса этого запроса» наш курс (спека §4.4: вписывать курс у каждого файла больше не нужно):

```java
    private void inheritRate(PriceRequest pr, SupplierReplyFile f) {
        if (f.getParsed() == null || f.getRate() != null) return;
        String market = pr.getMarket() == null ? null : pr.getMarket().currencyCode();
        String cur = tableCurrency(ReplyRowValues.of(f.getParsed()), f.getParsed());
        if (cur == null || market == null || cur.equals(market) || MoneyText.MIXED.equals(cur)) return;
        List<Object[]> requestRates = files.findRatesOfRequest(pr.getId(), cur);
        if (!requestRates.isEmpty()) {
            f.setRate((BigDecimal) requestRates.get(0)[0]);
            f.setRateCurrency(cur);
            f.setRateSetAt((OffsetDateTime) requestRates.get(0)[1]);
            return;
        }
        rates.our(pr.getMarket(), cur).ifPresent(r -> {
            f.setRate(r);
            f.setRateCurrency(cur);
            f.setRateSetAt(OffsetDateTime.now());
        });
    }
```

В `apply` — после `it.setResponsePrice(price);`:

```java
            boolean conv = converted(r, market, rate);
            it.setResponseCurrency(conv ? r.currency() : null);
            it.setResponseOriginal(conv ? r.price() : null);
            it.setResponseRate(conv ? rate.rate() : null);
```

`clear(PriceRequestItem it)` — ещё три строки:

```java
        it.setResponseCurrency(null);
        it.setResponseOriginal(null);
        it.setResponseRate(null);
```

`tableCurrency` — `public static`. Новый помощник (рядом с `marketPrice`):

```java
    /**
     * Цена из строки файла в чужой валюте (решение плана 4): исходная цена, валюта и курс — из самой строки и курса файла,
     * цена в валюте запроса — пересчётом. Строка в валюте запроса или без курса — ничего не меняет.
     */
    public static void foreignFromRow(PriceRequestItem it, SupplierReplyFile f, int fileRow, String market) {
        if (f.getParsed() == null || market == null) return;
        List<ReplyRowValues> rows = ReplyRowValues.of(f.getParsed());
        ReplyRowValues r = rows.stream().filter(x -> x.fileRow() == fileRow).findFirst().orElse(null);
        if (r == null || r.price() == null) return;
        FileRate rate = fileRate(f.getRate(), f.getRateCurrency(), tableCurrency(rows, f.getParsed()), market);
        if (!converted(r, market, rate)) return;
        BigDecimal price = RateMath.convert(r.price(), rate.rate());
        if (price == null) return;
        it.setResponsePrice(price);
        it.setResponseCurrency(r.currency());
        it.setResponseOriginal(r.price());
        it.setResponseRate(rate.rate());
    }
```

- [ ] **Step 3: Экран сверки — наш курс и курс сегодня**

`SupplierReplyView`:

```java
    /** row — строка файла, из которой цена; suggestion — подсказка для позиции без цены; currency / original / rate — исходная
     *  цена в чужой валюте и курс (V44); today — она же по курсу сегодня. */
    public record Position(Long itemId, int ordinal, String name, String brand, String model, Integer quantity, String unit,
                           BigDecimal price, boolean auto, String note, Long fileId, Integer fileRow, RowRef row,
                           RowRef suggestion, List<String> flags, String currency, BigDecimal original, BigDecimal rate,
                           BigDecimal today) {}

    /**
     * Курс выбранного файла: 1 currency = value валюты запроса; suggestion — наш курс, иначе последний вписанный курс этой
     * валюты на рынке (suggestedAt — когда); our — наш курс; today / todayDate — курс НБ РК сегодня (спека 2026-10-23 §4.4).
     */
    public record Rate(String currency, BigDecimal value, BigDecimal suggestion, OffsetDateTime suggestedAt, BigDecimal our,
                       BigDecimal today, LocalDate todayDate) {}
```

`SupplierReplyViewBuilder` — поле `private final CurrencyRates rates;` в конструкторе. Позиция:

```java
            BigDecimal todayPrice = null;
            if (it.getResponseCurrency() != null && it.getResponseOriginal() != null) {
                todayPrice = rates.today(pr.getMarket(), it.getResponseCurrency())
                        .map(td -> RateMath.convert(it.getResponseOriginal(), td.rate())).orElse(null);
            }
            positions.add(new Position(it.getId(), i + 1, l == null ? "" : l.getEquipName(), l == null ? null : l.getManufact(),
                    e == null ? null : e.getName(), it.getRequestedQuantity(), l == null ? null : l.getUnit(),
                    it.getResponsePrice(), it.isResponseAuto(), it.getResponseNote(), it.getResponseFileId(),
                    it.getResponseFileRow(), row, suggestion, flags, it.getResponseCurrency(), it.getResponseOriginal(),
                    it.getResponseRate(), todayPrice));
```

Блок курса:

```java
        if (table != null && cur != null && market != null && !cur.equals(market) && !MoneyText.MIXED.equals(cur)) {
            BigDecimal our = rates.our(pr.getMarket(), cur).orElse(null);
            CurrencyRates.Today td = rates.today(pr.getMarket(), cur).orElse(null);
            List<Object[]> last = our != null ? List.of() : files.findLastRate(pr.getMarket().name(), cur);
            rateView = new Rate(cur, rate == null ? null : rate.rate(),
                    our != null ? our : last.isEmpty() ? null : (BigDecimal) last.get(0)[0],
                    our != null || last.isEmpty() ? null : at(last.get(0)[1]),
                    our, td == null ? null : td.rate(), td == null ? null : td.date());
        }
```

- [ ] **Step 4: Telegram — «по нашему курсу 6 (сегодня 5,48)»**

`MailIngestWriter.fileLine`, ветка `o.applied() > 0`:

```java
        if (o.applied() > 0) {
            return "💡 Цены из файла «" + name + "»: подставлено " + o.applied() + " из " + o.positions() + rateLine(o) + " — сверьте"
                    + (o.manualKept() > 0 ? "; ручные цены не тронуты: " + o.manualKept() : "");
        }
```

```java
    /** « по нашему курсу 6 (сегодня 5,48)» / « по курсу 5,9»; без курса — пусто. */
    static String rateLine(SupplierReplyService.ReplyOutcome o) {
        if (o.rate() == null) return "";
        return " по " + (o.ourRate() ? "нашему " : "") + "курсу " + RateMath.text(o.rate())
                + (o.today() == null ? "" : " (сегодня " + RateMath.text(o.today()) + ")");
    }
```

- [ ] **Step 5: Ввод цены в чужой валюте — `PUT /responses`**

`ItemResponseDto`:

```java
    public record ItemResponseDto(Long itemId, BigDecimal responsePrice, String responseNote,
                                  Long fileId, Integer fileRow, Boolean clearFile,
                                  String responseCurrency, BigDecimal responseOriginal) {
        public ItemResponseDto(Long itemId, BigDecimal responsePrice, String responseNote) {
            this(itemId, responsePrice, responseNote, null, null, null, null, null);
        }

        public ItemResponseDto(Long itemId, BigDecimal responsePrice, String responseNote, Long fileId, Integer fileRow, Boolean clearFile) {
            this(itemId, responsePrice, responseNote, fileId, fileRow, clearFile, null, null);
        }
    }
```

В контроллер — `private final CurrencyRates rates;` (конструктор). В цикле `updateResponses` вместо
`item.setResponsePrice(u.responsePrice());`:

```java
            Market market = pr.getMarket() == null ? MarketContext.get() : pr.getMarket();
            setPrice(item, u, market);
```

и после блока ссылки на файл (`item.setResponseFileRow(u.fileRow());`) — исходная цена из строки файла:

```java
                SupplierReplyService.foreignFromRow(item, f, u.fileRow(), market.currencyCode());
```

Помощник контроллера:

```java
    /**
     * Цена ответа (спека 2026-10-23 §4.4, решения плана 4–5): валюта не рынка — исходная цена × наш курс (нет курса — 400);
     * валюта рынка — цена как есть, рубли снимаются; валюты нет — цена та же — рубли остаются («Сохранить ответы» карточки
     * тендера шлёт все строки), другая — снимаются.
     */
    private void setPrice(PriceRequestItem item, ItemResponseDto u, Market market) {
        String cur = u.responseCurrency() == null || u.responseCurrency().isBlank() ? null : u.responseCurrency().trim().toUpperCase(Locale.ROOT);
        if (cur != null && !cur.equals(market.currencyCode())) {
            if (u.responseOriginal() == null || u.responseOriginal().signum() < 0) {
                throw new BadRequestException("Цена в " + MoneyText.label(cur) + " — число не меньше нуля (позиция " + u.itemId() + ")");
            }
            BigDecimal rate = rates.our(market, cur).orElseThrow(() -> new BadRequestException(
                    "Впишите наш курс для " + MoneyText.label(cur) + " в «Система → Курсы валют»"));
            BigDecimal original = u.responseOriginal().setScale(2, RoundingMode.HALF_UP);
            BigDecimal price = RateMath.convert(original, rate);
            if (price == null) throw new BadRequestException("Цена слишком большая (позиция " + u.itemId() + ")");
            item.setResponsePrice(price);
            item.setResponseCurrency(cur);
            item.setResponseOriginal(original);
            item.setResponseRate(rate);
            return;
        }
        BigDecimal before = item.getResponsePrice();
        boolean same = before != null && u.responsePrice() != null && before.compareTo(u.responsePrice()) == 0;
        item.setResponsePrice(u.responsePrice());
        if (cur != null || !same) {
            item.setResponseCurrency(null);
            item.setResponseOriginal(null);
            item.setResponseRate(null);
        }
    }
```

`PriceRequestItemResponse` — поля `responseCurrency`, `responseOriginal`, `responseRate` (MapStruct заполнит по именам; если у
маппера `unmappedTargetPolicy = ERROR` — добавить их же в `@Mapping` не нужно, имена совпадают).

- [ ] **Step 6: Таблица цен заявки — ячейка с рублями и курсом сегодня**

`PrivateRequestPricesResponse.Cell`:

```java
    public record Cell(Long distributorId, String state, BigDecimal price, String note, Long priceRequestId,
                       Long itemId, boolean best, boolean emailOverride, boolean manual, boolean auto,
                       String currency, BigDecimal original, BigDecimal rate, BigDecimal today) {
        public Cell withBest() {
            return new Cell(distributorId, state, price, note, priceRequestId, itemId, true, emailOverride, manual, auto, currency, original, rate, today);
        }

        public Cell withToday(BigDecimal t) {
            return new Cell(distributorId, state, price, note, priceRequestId, itemId, best, emailOverride, manual, auto, currency, original, rate, t);
        }
    }
```

`PrivateRequestPricesService.cell` — пустая ячейка: `new Cell(distributorId, "NONE", null, null, null, null, false, false,
false, false, null, null, null, null)`; заполненная — последними параметрами `i.getResponseCurrency(), i.getResponseOriginal(),
i.getResponseRate(), null`. Лучшая — `c == winner ? c.withBest() : c`. Курс сегодня — в `build` (поле `CurrencyRates rates`
в конструкторе), до `withBest`:

```java
                Cell c = cell(s.distributorId(), latest.get(key(lot.getId(), s.distributorId())), bounced);
                if (c.currency() != null && c.original() != null) {
                    BigDecimal today = rates.today(t.getMarket(), c.currency()).map(td -> RateMath.convert(c.original(), td.rate())).orElse(null);
                    c = c.withToday(today);
                }
```

- [ ] **Step 7: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.supplierreply.*' --tests 'com.vladoose.nir.service.mail.*' --tests 'com.vladoose.nir.privaterequest.*' 2>&1 | tail -20`
Expected: PASS (прежние тесты экрана сверки — в `setUp` без нашего курса).

- [ ] **Step 8: Мутации**

1. `inheritRate` без нашего курса (ветка `rates.our…` убрана) → `ourRate_appliesAtOnce_originalStored` краснеет.
2. Наш курс раньше курса запроса → `rateOfThisRequest_winsOverOurs` краснеет.
3. `setPrice`: всегда снимать рубли без валюты (`if (cur != null || true)`) → `samePriceWithoutCurrency_keepsOriginal_changedClears` краснеет.
4. `foreignFromRow` не звать → `pickedFileRow_originalFromRow` краснеет (цена осталась 1).
5. `clear` без трёх строк → `revertedAuto_clearsOriginal` краснеет.
6. `rateLine` без «нашему» → `ourRate_withToday` краснеет.
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 9: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java && git commit -m "feat(rates): ответ поставщика в рублях — цены сразу по нашему курсу, исходная цена у позиции, ввод цены в рублях, курс сегодня

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 4: КП — закупка в чужой валюте, прибыль по курсу сегодня (сервер)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java` (`purchaseCurrency`, `purchaseOriginal`,
  `purchaseRate`), `src/main/java/com/vladoose/nir/dto/response/ClientOfferResponse.java` (`ourRates`, `todayRates`)
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferService.java` (`checkPurchase` до `checkItem`, перенос в
  `applyItem`, `copyItem`, `calculate` — с курсами сегодня)
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferMapper.java` (поля строки, курсы в ответе КП)
- Modify: `src/main/java/com/vladoose/nir/service/offer/ClientOfferCalculator.java` (`calculate(offer, today)`),
  `src/main/java/com/vladoose/nir/service/offer/OfferTotals.java` (`purchaseToday`, `profitToday`)
- Modify: `src/main/java/com/vladoose/nir/service/offer/PurchaseOffer.java` (`currency`, `original`, `rate`),
  `src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java` (`link` переносит рубли ответа)
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferPurchaseCurrencyTest.java`,
  `src/test/java/com/vladoose/nir/clientoffer/ClientOfferCalculatorTodayTest.java`

**Interfaces:**
- Consumes: `CurrencyRates.our / ours / today` (задача 2), `RateMath.convert` (задача 2), поля `ClientOfferItem.purchase*`
  (задача 1), `PriceRequestItem.response*` (задача 3).
- Produces: `ClientOfferCalculator.calculate(ClientOffer, Map<String, BigDecimal> today)` (прежний `calculate(ClientOffer)` —
  с пустой картой); `OfferTotals(…, purchaseToday, profitToday)` — пусты, если строк в чужой валюте нет или у их валюты нет
  курса сегодня; в ответе КП `ourRates: {RUB: 6}`, `todayRates: {RUB: {rate, date}}`; у строки — `purchaseCurrency`,
  `purchaseOriginal`, `purchaseRate`; `PurchaseOffer(…, currency, original, rate)`.

- [ ] **Step 1: Тесты — красные**

`ClientOfferCalculatorTodayTest` (расчёт — чистая функция, курсы сегодня — входом):

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.offer.ClientOfferCalculator;
import com.vladoose.nir.service.offer.OfferTotals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Закупка и прибыль по курсу сегодня (спека 2026-10-23 §4.5): цена клиенту — от нашего курса, итоги «сегодня» — рядом. */
class ClientOfferCalculatorTodayTest {

    private final ClientOfferCalculator calc = new ClientOfferCalculator();

    private static ClientOffer offer() {
        return ClientOffer.builder().market(Market.KZ).vatEnabled(true).defaultMarkupPct(new BigDecimal("80"))
                .rounding(OfferRounding.NONE).origin(ClientOfferOrigin.BUILDER).build();
    }

    private static ClientOfferItem rub(ClientOffer o, String original, String rate, int qty) {
        BigDecimal purchase = new BigDecimal(original).multiply(new BigDecimal(rate)).setScale(2);
        return ClientOfferItem.builder().offer(o).lineNo(1).kind(ClientOfferItemKind.ITEM).name("ДКИ-Н-12").unit("шт")
                .quantity(BigDecimal.valueOf(qty)).purchasePrice(purchase).purchaseCurrency("RUB")
                .purchaseOriginal(new BigDecimal(original)).purchaseRate(new BigDecimal(rate)).vatRate(new BigDecimal("16")).build();
    }

    private static ClientOfferItem tenge(ClientOffer o, String purchase) {
        return ClientOfferItem.builder().offer(o).lineNo(2).kind(ClientOfferItemKind.ITEM).name("Доставка").unit("шт")
                .quantity(BigDecimal.ONE).purchasePrice(new BigDecimal(purchase)).vatRate(new BigDecimal("16")).build();
    }

    @Test
    void today_onlyForeignLinesConvertedAtTodayRate() {
        ClientOffer o = offer();
        o.getItems().add(rub(o, "150000", "6", 1));      // закупка 900 000 по нашему курсу
        o.getItems().add(tenge(o, "50000"));
        OfferTotals t = calc.calculate(o, Map.of("RUB", new BigDecimal("5.48"))).totals();
        assertThat(t.purchase()).isEqualByComparingTo("950000.00");
        assertThat(t.purchaseToday()).isEqualByComparingTo("872000.00");   // 822 000 + 50 000
        // цена клиенту — от закупки по нашему курсу: прибыль сегодня = прибыль + разница закупок
        assertThat(t.profitToday().subtract(t.profit())).isEqualByComparingTo("78000.00");
    }

    @Test
    void noTodayRate_todayTotalsEmpty() {
        ClientOffer o = offer();
        o.getItems().add(rub(o, "150000", "6", 2));
        assertThat(calc.calculate(o, Map.of()).totals().purchaseToday()).isNull();
        assertThat(calc.calculate(o, Map.of()).totals().profitToday()).isNull();
    }

    @Test
    void noForeignLines_todayTotalsEmpty() {
        ClientOffer o = offer();
        o.getItems().add(tenge(o, "50000"));
        assertThat(calc.calculate(o, Map.of("RUB", new BigDecimal("5.48"))).totals().purchaseToday()).isNull();
    }

    @Test
    void todayPurchase_perUnitOneFraction_thenQuantity() {
        ClientOffer o = offer();
        o.getItems().add(rub(o, "0.25", "5.5", 3));       // 0,25 × 5,5 = 1,375 → 1,38 за ед.; × 3 = 4,14
        assertThat(calc.calculate(o, Map.of("RUB", new BigDecimal("5.5"))).totals().purchaseToday()).isEqualByComparingTo("4.14");
    }
}
```

`ClientOfferPurchaseCurrencyTest` — путь `PUT /api/client-offers/{id}` (решение плана 6; помощники — как в `ClientOfferApiTest`):

```java
package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.OfficialRate;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.repository.CurrencyRateRepository;
import com.vladoose.nir.repository.OfficialRateRepository;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class ClientOfferPurchaseCurrencyTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired CompanyProfileRepository profiles;
    @Autowired CurrencyRates rates;
    @Autowired CurrencyRateRepository ours;
    @Autowired OfficialRateRepository official;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ours.deleteAllInBatch();
        official.deleteAllInBatch();
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        kz.setDefaultMarkupPct(new BigDecimal("80.00"));
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        official.saveAndFlush(OfficialRate.builder().source(CurrencyRates.NBRK).currency("RUB").rateDate(LocalDate.now(Market.KZ.zone()))
                .rate(new BigDecimal("5.48")).fetchedAt(OffsetDateTime.now()).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static MockHttpServletRequestBuilder kz(MockHttpServletRequestBuilder b) { return b.header("X-Market", "KZ"); }

    private JsonNode json(MockHttpServletRequestBuilder b) throws Exception {
        MarketContext.clear();
        return om.readTree(mvc.perform(b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ObjectNode rubItem(String original, Double rate) {
        ObjectNode it = om.createObjectNode();
        it.put("key", "r1");
        it.put("kind", "ITEM");
        it.put("name", "Дефибриллятор ДКИ-Н-12");
        it.put("unit", "шт");
        it.put("quantity", 1);
        it.put("purchasePrice", 1);                        // сервер не читает: считает сам
        it.put("purchaseCurrency", "RUB");
        it.put("purchaseOriginal", new BigDecimal(original));
        if (rate == null) it.putNull("purchaseRate"); else it.put("purchaseRate", rate);
        it.put("vatRate", 16);
        it.put("registrationStatus", "UNCHECKED");
        return it;
    }

    private JsonNode put(JsonNode offer, ObjectNode item) throws Exception {
        ObjectNode body = offer.deepCopy();
        ArrayNode items = body.putArray("items");
        items.add(item);
        return json(kz(put("/api/client-offers/" + offer.get("id").asLong())).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    @Test
    void rubles_serverConverts_todayTotalsInResponse() throws Exception {
        JsonNode o = json(kz(post("/api/client-offers")));
        JsonNode saved = put(o, rubItem("150000", null));
        JsonNode line = saved.get("items").get(0);
        assertThat(line.get("purchasePrice").decimalValue()).isEqualByComparingTo("900000");
        assertThat(line.get("purchaseRate").decimalValue()).isEqualByComparingTo("6");
        assertThat(saved.get("ourRates").get("RUB").decimalValue()).isEqualByComparingTo("6");
        assertThat(saved.get("todayRates").get("RUB").get("rate").decimalValue()).isEqualByComparingTo("5.48");
        assertThat(saved.get("totals").get("purchaseToday").decimalValue()).isEqualByComparingTo("822000");
        assertThat(line.get("calc").get("price").decimalValue()).isEqualByComparingTo("1620000");   // 900 000 × 1,8
    }

    /** Review Focus 1: наш курс сменили, а старая вкладка шлёт прежний — принимается (курс строки), чужой — 400. */
    @Test
    void purchaseRate_onlyStoredOrCurrentOurs() throws Exception {
        JsonNode o = json(kz(post("/api/client-offers")));
        JsonNode saved = put(o, rubItem("150000", null));
        MarketContext.set(Market.KZ);
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6.5")))), "vlad");
        ObjectNode stale = rubItem("150000", 6.0);
        stale.put("id", saved.get("items").get(0).get("id").asLong());
        JsonNode again = put(saved, stale);
        assertThat(again.get("items").get(0).get("purchasePrice").decimalValue()).isEqualByComparingTo("900000");   // курс строки
        ObjectNode recalc = rubItem("150000", 6.5);                                                                // «Пересчитать»
        recalc.put("id", stale.get("id").asLong());
        assertThat(put(again, recalc).get("items").get(0).get("purchasePrice").decimalValue()).isEqualByComparingTo("975000");
        ObjectNode foreign = rubItem("150000", 7.0);
        foreign.put("id", stale.get("id").asLong());
        ObjectNode body = again.deepCopy();
        body.putArray("items").add(foreign);
        MarketContext.clear();
        mvc.perform(kz(put("/api/client-offers/" + o.get("id").asLong())).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("наш курс")));
    }

    @Test
    void noOurRate_400_marketCurrency_clears() throws Exception {
        JsonNode o = json(kz(post("/api/client-offers")));
        ObjectNode usd = rubItem("100", null);
        usd.put("purchaseCurrency", "USD");
        ObjectNode body = o.deepCopy();
        body.putArray("items").add(usd);
        mvc.perform(kz(put("/api/client-offers/" + o.get("id").asLong())).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Впишите наш курс для $")));
        JsonNode saved = put(o, rubItem("150000", null));
        ObjectNode tenge = (ObjectNode) saved.get("items").get(0).deepCopy();
        tenge.put("purchaseCurrency", "KZT");
        tenge.put("purchasePrice", 880000);
        JsonNode back = put(saved, tenge);
        assertThat(back.get("items").get(0).get("purchaseCurrency").isNull()).isTrue();
        assertThat(back.get("items").get(0).get("purchasePrice").decimalValue()).isEqualByComparingTo("880000");
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferCalculatorTodayTest' --tests 'com.vladoose.nir.clientoffer.ClientOfferPurchaseCurrencyTest' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `calculate(o, Map)`, `OfferTotals.purchaseToday()`.

- [ ] **Step 2: Расчёт — закупка и прибыль по курсу сегодня**

`OfferTotals`:

```java
/**
 * Итоги КП (§5.3) и маржа (§5.4, в документ не попадает). purchase — Σ закупок строк (каждая до 0,01); cost — то же
 * число: НДС поставщика не вычитается (решение оператора 2026-10-05), поле оставлено для совместимости API. vatTotal —
 * наш НДС с продажи. markupAvg — от закупки, по суммам с НДС: (Σ sum − Σ costTotal) / Σ costTotal × 100 по строкам
 * с закупкой больше нуля (одна наценка 20 % у всех строк — 20 %); null, если таких строк нет. purchaseToday /
 * profitToday — закупка и прибыль по курсу сегодня (спека 2026-10-23 §4.5): строки в чужой валюте — исходная цена × курс
 * сегодня, остальные — их закупка; null — строк в чужой валюте нет или у их валюты нет курса сегодня.
 */
public record OfferTotals(BigDecimal sum, List<VatLine> vat, BigDecimal vatTotal, BigDecimal purchase, BigDecimal cost,
                          BigDecimal revenueNet, BigDecimal profit, BigDecimal markupAvg,
                          int itemCount, int noPurchaseCount, int unconfirmedRegistrationCount,
                          BigDecimal purchaseToday, BigDecimal profitToday) {}
```

`ClientOfferCalculator`:

```java
    public OfferCalculation calculate(ClientOffer offer) {
        return calculate(offer, Map.of());
    }

    /** today — курс сегодня по валютам (1 единица = курс в валюте рынка); пусто — итогов «сегодня» нет. */
    public OfferCalculation calculate(ClientOffer offer, Map<String, BigDecimal> today) {
        List<ItemCalc> items = new ArrayList<>(offer.getItems().size());
        Map<BigDecimal, BigDecimal> vatByRate = new TreeMap<>();
        BigDecimal sum = zero(), vatTotal = zero(), purchase = zero(), revenueNet = zero(), profit = zero();
        BigDecimal markedSum = zero(), markedPurchase = zero();
        BigDecimal purchaseToday = zero(), profitToday = zero();
        boolean foreign = false, missingToday = false;
        String marketCode = offer.getMarket() == null ? null : offer.getMarket().currencyCode();
        int itemCount = 0, noPurchase = 0, unconfirmed = 0;

        for (ClientOfferItem item : offer.getItems()) {
            if (item.getKind() != ClientOfferItemKind.ITEM) {
                items.add(ItemCalc.NONE);
                continue;
            }
            itemCount++;
            OfferRegistrationStatus reg = item.getRegistrationStatus();
            if (reg == OfferRegistrationStatus.UNCHECKED || reg == OfferRegistrationStatus.SUGGESTED
                    || reg == OfferRegistrationStatus.NOT_FOUND) unconfirmed++;
            if (item.getPurchasePrice() == null) noPurchase++;

            ItemCalc c = calculateItem(offer, item);
            items.add(c);
            if (c.sum() != null) {
                sum = sum.add(c.sum());
                vatTotal = vatTotal.add(c.vatSum());
                revenueNet = revenueNet.add(c.sumNet());
                if (c.effectiveVatRate() != null) vatByRate.merge(c.effectiveVatRate(), c.vatSum(), BigDecimal::add);
            }
            if (c.costTotal() != null) {
                // закупка строки уже до 0,01 — итог «Закупка» = Σ закупок строк, и прибыль = выручка без НДС − закупка
                purchase = purchase.add(c.costTotal());
                profit = profit.add(c.profit());
                // средняя наценка — только где закупка больше нуля: у закупки 0 наценки нет («—»)
                if (item.getPurchasePrice().signum() > 0) {
                    markedSum = markedSum.add(c.sum());
                    markedPurchase = markedPurchase.add(c.costTotal());
                }
                // по курсу сегодня (спека 2026-10-23 §4.5): чужая валюта — исходная цена × курс сегодня, остальное — как есть
                String cur = item.getPurchaseCurrency();
                BigDecimal net = c.sumNet() == null ? zero() : c.sumNet();
                if (cur != null && !cur.equals(marketCode) && item.getPurchaseOriginal() != null) {
                    foreign = true;
                    BigDecimal rate = today.get(cur);
                    BigDecimal unit = rate == null ? null : RateMath.convert(item.getPurchaseOriginal(), rate);
                    if (unit == null) {
                        missingToday = true;
                    } else {
                        BigDecimal costToday = unit.multiply(qty(item)).setScale(2, RoundingMode.HALF_UP);
                        purchaseToday = purchaseToday.add(costToday);
                        profitToday = profitToday.add(net.subtract(costToday));
                    }
                } else {
                    purchaseToday = purchaseToday.add(c.costTotal());
                    profitToday = profitToday.add(c.profit());
                }
            }
        }

        List<VatLine> vat = new ArrayList<>();
        vatByRate.forEach((rate, amount) -> vat.add(new VatLine(rate, amount)));
        // от закупки, по суммам с НДС: одна наценка 20 % у всех строк — средняя 20 %, при любых ставках НДС
        BigDecimal markupAvg = markedPurchase.signum() > 0
                ? markedSum.subtract(markedPurchase).multiply(HUNDRED).divide(markedPurchase, 2, RoundingMode.HALF_UP)
                : null;
        boolean showToday = foreign && !missingToday;
        // cost (себестоимость) = purchase: НДС поставщика не вычитается; поле оставлено для совместимости API
        OfferTotals totals = new OfferTotals(sum, vat, vatTotal, purchase, purchase, revenueNet, profit, markupAvg,
                itemCount, noPurchase, unconfirmed, showToday ? purchaseToday : null, showToday ? profitToday : null);
        return new OfferCalculation(items, totals);
    }
```

(Тело — прежнее, со вставкой «по курсу сегодня» в ветку закупки и двумя последними полями итогов; `RateMath` — из
`service/rates`.)

- [ ] **Step 3: Строка КП — закупка в чужой валюте**

`ClientOfferItemDto` — после `purchaseVatRate`:

```java
    /** Закупка в чужой валюте (спека 2026-10-23 §4.5): исходная цена и курс; purchasePrice тогда считает сервер. */
    @Size(max = 5) private String purchaseCurrency;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal purchaseOriginal;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal purchaseRate;
```

`ClientOfferService` — поле `private final CurrencyRates rates;` (конструктор). В `checkItems`, в ветке ITEM — до
`checkItem(dto, it, profile, row)`:

```java
                purchases.add(checkPurchase(o, dto, it, row));
```

(`List<Purchase> purchases` рядом со списками `links / regs`; в `Checked` — пятое поле `List<Purchase> purchases`, у не-ITEM —
`Purchase.NONE`.)

```java
    /** Закупка строки после проверки: валюта не рынка — исходная цена и курс (решение плана 6); NONE — в валюте рынка. */
    private record Purchase(String currency, BigDecimal original, BigDecimal rate) {
        static final Purchase NONE = new Purchase(null, null, null);
    }

    /**
     * Валюта не рынка: исходная цена ≥ 0, курс — сохранённый курс строки (та же валюта) или текущий наш курс, пусто —
     * сохранённый, иначе наш; закупку в валюте рынка считает сервер (присланная не читается). Иначе — NONE.
     */
    private Purchase checkPurchase(ClientOffer o, ClientOfferItemDto dto, ClientOfferItem stored, String row) {
        String cur = trim(dto.getPurchaseCurrency());
        cur = cur == null ? null : cur.toUpperCase(Locale.ROOT);
        if (cur == null || cur.equals(o.getMarket().currencyCode())) return Purchase.NONE;
        String label = MoneyText.label(cur);
        BigDecimal original = scaled(dto.getPurchaseOriginal(), MONEY_SCALE);
        if (original == null || original.signum() < 0) {
            throw new BadRequestException("Цена закупки в " + label + " — число не меньше нуля — позиция " + row);
        }
        BigDecimal storedRate = stored != null && cur.equals(stored.getPurchaseCurrency()) ? stored.getPurchaseRate() : null;
        BigDecimal ours = rates.our(o.getMarket(), cur).orElse(null);
        BigDecimal rate = dto.getPurchaseRate();
        if (rate == null) {
            rate = storedRate != null ? storedRate : ours;
            if (rate == null) throw new BadRequestException("Впишите наш курс для " + label + " в «Система → Курсы валют» — позиция " + row);
        } else if (!(storedRate != null && storedRate.compareTo(rate) == 0) && !(ours != null && ours.compareTo(rate) == 0)) {
            throw new BadRequestException("Курс строки — только наш курс" + (ours == null ? "" : " (" + RateMath.text(ours) + ")")
                    + " — позиция " + row);
        }
        BigDecimal price = RateMath.convert(original, rate);
        if (price == null) throw new BadRequestException("Цена закупки слишком большая — позиция " + row);
        dto.setPurchasePrice(price);   // дальше проверки checkItem — как у обычной закупки
        return new Purchase(cur, original, rate);
    }
```

`applyItem` получает `Purchase p` (сигнатура `applyItem(it, dto, link, reg, catalogId, purchase)`; `syncItems` передаёт
`checked.purchases().get(i)`): у не-ITEM — три поля `null`; у ITEM — после `it.setPurchasePrice(dto.getPurchasePrice());`:

```java
        it.setPurchaseCurrency(purchase.currency());
        it.setPurchaseOriginal(purchase.original());
        it.setPurchaseRate(purchase.rate());
```

`copyItem` — `.purchaseCurrency(it.getPurchaseCurrency()).purchaseOriginal(it.getPurchaseOriginal()).purchaseRate(it.getPurchaseRate())`.

`calculate` сервиса:

```java
    /** Расчёт для ответа экрану: с курсами сегодня (итоги «по курсу сегодня»); документ считает без них. */
    public OfferCalculation calculate(ClientOffer o) {
        Map<String, BigDecimal> today = new HashMap<>();
        rates.today(o.getMarket()).forEach((c, t) -> today.put(c, t.rate()));
        return calculator.calculate(o, today);
    }
```

- [ ] **Step 4: Маппер и ответ КП**

`ClientOfferResponse` — поля:

```java
    /** Наш курс по валютам рынка и курс сегодня (спека 2026-10-23 §4.5): переключатель валюты закупки, плашка «Пересчитать». */
    private Map<String, BigDecimal> ourRates;
    private Map<String, TodayRate> todayRates;

    public record TodayRate(BigDecimal rate, LocalDate date) {}
```

`ClientOfferMapper` — поле `private final CurrencyRates rates;` (конструктор, Spring). В `toResponse`:

```java
        r.setOurRates(rates.ours(o.getMarket()));
        Map<String, ClientOfferResponse.TodayRate> today = new LinkedHashMap<>();
        rates.today(o.getMarket()).forEach((c, t) -> today.put(c, new ClientOfferResponse.TodayRate(t.rate(), t.date())));
        r.setTodayRates(today);
```

В `toItemDto` — `d.setPurchaseCurrency(it.getPurchaseCurrency()); d.setPurchaseOriginal(it.getPurchaseOriginal());
d.setPurchaseRate(it.getPurchaseRate());`.

- [ ] **Step 5: КП из заявки — рубли ответа переходят в строку**

`PurchaseOffer`:

```java
public record PurchaseOffer(long priceRequestItemId, long priceRequestId, long lotId, long distributorId,
                            String supplierName, BigDecimal price, String status, LocalDate respondedOn, boolean auto,
                            String currency, BigDecimal original, BigDecimal rate) {

    public PurchaseOffer(long priceRequestItemId, long priceRequestId, long lotId, long distributorId,
                         String supplierName, BigDecimal price, String status, LocalDate respondedOn) {
        this(priceRequestItemId, priceRequestId, lotId, distributorId, supplierName, price, status, respondedOn, false, null, null, null);
    }

    public static PurchaseOffer of(PriceRequestItem i) {
        PriceRequest pr = i.getPriceRequest();
        return new PurchaseOffer(i.getId(), pr.getId(), i.getTenderLot().getId(), pr.getDistributor().getId(),
                pr.getDistributor().getName(), i.getResponsePrice(), pr.getStatus(), pr.getResponseDate(), i.isResponseAuto(),
                i.getResponseCurrency(), i.getResponseOriginal(), i.getResponseRate());
    }
}
```

`ClientOfferRequestService.link`:

```java
    static void link(ClientOfferItem it, PurchaseOffer offer) {
        it.setPurchasePrice(offer.price().setScale(2, RoundingMode.HALF_UP));
        it.setSupplierName(cut(offer.supplierName(), 255));
        it.setPriceRequestItemId(offer.priceRequestItemId());
        it.setDistributorId(offer.distributorId());
        it.setPurchaseCurrency(offer.currency());
        it.setPurchaseOriginal(offer.original());
        it.setPurchaseRate(offer.rate());
        it.setPriceListItemId(null);
    }
```

Тест (в `ClientOfferPurchaseCurrencyTest`): частная заявка KZ на одну позицию, ответ с `responseCurrency = RUB`,
`responseOriginal = 150000`, `responseRate = 6`, `responsePrice = 900000` (через репозиторий, как в `ClientOfferFromRequestTest`)
→ `POST /api/client-offers/from-private-request/{id}` → у строки `purchaseCurrency = RUB`, `purchaseOriginal = 150000`,
`purchaseRate = 6`.

- [ ] **Step 6: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*' --tests 'com.vladoose.nir.readyoffer.*' 2>&1 | tail -20`
Expected: PASS (прежние тесты расчёта — `calculate(offer)` с пустой картой: итоги «сегодня» пусты, остальное не менялось).

- [ ] **Step 7: Мутации**

1. Принимать любой присланный курс (`else if` убрать) → `purchaseRate_onlyStoredOrCurrentOurs` краснеет.
2. `purchasePrice` из запроса, а не пересчётом → `rubles_serverConverts_todayTotalsInResponse` краснеет.
3. В расчёте «сегодня» — по нашему курсу (`rate = item.getPurchaseRate()`) → `today_onlyForeignLinesConvertedAtTodayRate` краснеет.
4. `missingToday` не учитывать → `noTodayRate_todayTotalsEmpty` краснеет.
5. `link` без трёх полей → тест КП из заявки краснеет.
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java && git commit -m "feat(client-offer): закупка в рублях по нашему курсу, закупка и прибыль по курсу сегодня в итогах КП

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 5: Разбор прайса — таблица Excel / Word, строки текста, шапка (даты, розница, условия), коды моделей

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/pricelist/ParsedPriceList.java`, `ParsedPriceRow.java`,
  `PriceListReader.java`, `PriceTextLines.java`, `PriceListHeader.java`, `PriceListCodes.java`
- Modify: `src/main/java/com/vladoose/nir/service/supplierreply/ReplyTableReader.java` (перегрузка `readAll(…, boolean
  expandVertical)` — объединённые по вертикали ячейки Excel во всех строках; `joined` — public),
  `src/main/java/com/vladoose/nir/service/supplierreply/SupplierReplyFiles.java` (`pdfLines` — public),
  `src/main/java/com/vladoose/nir/service/readyoffer/OfferHeader.java` (`date(String)` — public)
- Test: `src/test/java/com/vladoose/nir/pricelist/PriceListReaderTest.java`, `PriceListHeaderTest.java`,
  `PriceTextLinesTest.java`, `PriceListCodesTest.java`, `src/test/java/com/vladoose/nir/pricelist/PriceListFiles.java`
  (синтетические файлы)

**Interfaces:**
- Consumes: `ReplyTableReader.readAll / allLines`, `HeaderRoles`, `MoneyText.amount / currency / textCurrencies`,
  `OoxmlSlim.forParsing`, `HeaderDictionary.learned()`, `BrandModels.modelCodes`, `ProductText.letters / digits` (есть).
- Produces: `record ParsedPriceRow(int fileRow, String section, String name, String article, List<BigDecimal> prices,
  String regText)`; `record ParsedPriceList(ReplyParseStatus status, String note, String currency, List<PriceListColumn>
  columns, List<ParsedPriceRow> rows, Integer ourColumn, LocalDate listDate, LocalDate validTo, boolean retail, String terms,
  boolean truncated)` + `ParsedPriceList.failed(status, note)`; `PriceListReader.read(String fileName, ReplyFileKind kind,
  byte[] bytes) → ParsedPriceList` (бин) и `static read(…, Map<String, LineField> learned)`; `PriceListReader.fromTable
  (ReplyTable, List<String> text, boolean excludeData, String fileName)` (его зовёт PDF-путь задачи 6);
  `PriceListHeader.of(List<String> lines, String fileName) → PriceListHeader(listDate, validTo, retail, terms)`,
  `PriceListHeader.currency(List<String>) → String|null`, `PriceListHeader.defaultColumn(List<PriceListColumn>,
  List<ParsedPriceRow>) → Integer`; `PriceTextLines.read(List<String>) → Result(rows, text, currency)`;
  `PriceListCodes.codes(String) → List<String>`, `PriceListCodes.key(String) → String`, `PriceListCodes.keys(String) →
  Set<String>`.

- [ ] **Step 1: Синтетические файлы для тестов**

`PriceListFiles` — та же вёрстка, что у настоящих прайсов (§3 спеки), цифры и названия свои:

```java
package com.vladoose.nir.pricelist;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Синтетические прайсы для тестов (настоящие — только локально, PRICE_LIST_CORPUS). */
public final class PriceListFiles {

    private PriceListFiles() {}

    /**
     * Excel как у Аксиона: заголовок над таблицей, две цены (оптовая и дилерская), колонка РУ (объединённая на две строки),
     * разделы объединёнными строками, условия и срок под таблицей.
     */
    public static byte[] axionLikeXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet s = wb.createSheet("Прайс");
            row(s, 0, "Прайс-лист ООО «ZZ Завод»");
            row(s, 2, "№", "Наименование", "Оптовая цена, RUB, без НДС (FCA г. Ижевск)",
                    "Цена со скидкой при подписании дилерского контракта, RUB без НДС", "Разрешительные документы в Казахстане");
            row(s, 3, "Реанимация");
            s.addMergedRegion(new CellRangeAddress(3, 3, 0, 4));
            row(s, 4, "1", "Транспортный ИВЛ А-ИВЛ-Э-03", "330 400", "251 100", "РЕГИСТРАЦИОННОЕ УДОСТОВЕРЕНИЕ РК-МТ-5№021045");
            row(s, 5, "2", "Дефибриллятор ДКИ-Н-12. Базовые функции дефибриллятора", "205 000", "150 000",
                    "РЕГИСТРАЦИОННОЕ УДОСТОВЕРЕНИЕ РК МИ (МТ)-0№024573 (бессрочно)");
            row(s, 6, "3", "Дефибриллятор ДКИ-Н-12 (без сумки)", "195 000", "140 000");
            s.addMergedRegion(new CellRangeAddress(5, 6, 4, 4));
            row(s, 7, "Хирургия");
            s.addMergedRegion(new CellRangeAddress(7, 7, 0, 4));
            row(s, 8, "4", "Отсасыватель медицинский портативный ОМП-5/80-01", "66 800", "60 100", "Нет РУ в Казахстане");
            row(s, 10, "• Цены указаны без учёта стоимости доставки (FCA г. Ижевск);");
            row(s, 11, "• Предоплата 100%;");
            row(s, 12, "• Указанный прайс-лист актуален с 01 сентября 2026 года по 31 декабря 2026 года.");
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Word как у Орион-Си: без таблицы, строки «наименование ……… цена руб.», розничные цены, раздел с двоеточием. */
    public static byte[] orionLikeDocx() throws Exception {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : List.of("ООО «ZZ-Си»", "РЕКОМЕНДОВАННЫЕ РОЗНИЧНЫЕ ЦЕНЫ", "действительны с1 ноября 2025 г",
                    "СТЕРИЛИЗАТОРЫ ОЗОНОВЫЕ «ZZ» - МНОГОФУНКЦИОНАЛЬНЫЕ:",
                    "  1.  Стерилизатор озоновый «ZZ» СК – 16 л ……………………...…..……....      957 000 руб.",
                    "  2.  Стерилизатор озоновый «ZZ» СК – 36 л ……………….………….……...   1 097 000 руб.",
                    "    Стерилизатор озоновый «ZZ» без камеры (генератор озона)………………..   475 000 руб.",
                    "Гарантийный срок на все оборудование, выпускаемое ООО «ZZ-Си», составляет 2 года.")) {
                d.createParagraph().createRun().setText(line);
            }
            d.write(out);
            return out.toByteArray();
        }
    }

    private static void row(XSSFSheet s, int r, String... cells) {
        XSSFRow row = s.createRow(r);
        for (int c = 0; c < cells.length; c++) row.createCell(c).setCellValue(cells[c]);
    }
}
```

- [ ] **Step 2: Тесты — красные**

`PriceListCodesTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.service.pricelist.PriceListCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceListCodesTest {

    @Test
    void codesAndKeys() {
        assertThat(PriceListCodes.codes("Дефибриллятор ДКИ-Н-12. Наилучший вариант")).containsExactly("ДКИ-Н-12");
        assertThat(PriceListCodes.key("ДКИ-Н-12")).isEqualTo("дкин12");
        assertThat(PriceListCodes.codes("Стерилизатор озоновый «Орион» СК – 16 л")).contains("СК-16");   // склейка «СК – 16»
        assertThat(PriceListCodes.codes("Дозатор шприцевый Д01")).isEmpty();                            // одна буква — не код
        assertThat(PriceListCodes.codes("Отсасыватель ОМХ-5/80-01 (без стойки)")).containsExactly("ОМХ-5/80-01");
        // латинские двойники и разделители не важны: «OMX 5/80-01» латиницей = «ОМХ-5/80-01»
        assertThat(PriceListCodes.key("OMX 5/80-01")).isEqualTo(PriceListCodes.key("ОМХ-5/80-01"));
        assertThat(PriceListCodes.keys("Дефибриллятор ДКИ-Н-12 Аксион, сумка СК-16")).containsExactlyInAnyOrder("дкин12", "ск16");
    }
}
```

`PriceListHeaderTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.service.pricelist.ParsedPriceRow;
import com.vladoose.nir.service.pricelist.PriceListHeader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceListHeaderTest {

    @Test
    void datesFromPriceLines_notFromRegistrationText() {
        PriceListHeader h = PriceListHeader.of(List.of(
                "2023 Действителен до 03.05.2028 года",                              // срок РУ — не срок прайса
                "• Указанный прайс-лист актуален с 01 сентября 2026 года по 31 декабря 2026 года."), "прайс.pdf");
        assertThat(h.listDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(h.validTo()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void retail_glued_fileNameDate_terms() {
        PriceListHeader orion = PriceListHeader.of(List.of("РЕКОМЕНДОВАННЫЕ РОЗНИЧНЫЕ ЦЕНЫ", "действительны с1 ноября 2025 г",
                "Гарантийный срок на все оборудование составляет 2 года."), "Прайс стерилизаторы 01_11_2025.doc");
        assertThat(orion.retail()).isTrue();
        assertThat(orion.listDate()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(orion.terms()).contains("Гарантийный срок");
        PriceListHeader byName = PriceListHeader.of(List.of("Прайс-лист"), "[RU, RUB, KZ] Прайс-лист (01.09.2026).pdf");
        assertThat(byName.listDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(byName.retail()).isFalse();
        PriceListHeader terms = PriceListHeader.of(List.of("• Предоплата 100%;", "• Сертификат СТ-1 стоит 11000 рублей за партию;",
                "Больше информации на сайте"), null);
        assertThat(terms.terms()).isEqualTo("• Предоплата 100%;\n• Сертификат СТ-1 стоит 11000 рублей за партию;");
    }

    @Test
    void currencyFromText_mixedIsMixed() {
        assertThat(PriceListHeader.currency(List.of("Цены в рублях"))).isEqualTo("RUB");
        assertThat(PriceListHeader.currency(List.of("Цены в рублях", "Доставка 100 $"))).isEqualTo("MIXED");
        assertThat(PriceListHeader.currency(List.of("Прайс-лист"))).isNull();
    }

    @Test
    void defaultColumn_dealerThenDiscountThenWholesaleThenCheapest() {
        List<PriceListColumn> axion = List.of(new PriceListColumn(0, "Оптовая цена, RUB, без НДС"),
                new PriceListColumn(1, "Цена со скидкой при подписании дилерского контракта"));
        assertThat(PriceListHeader.defaultColumn(axion, List.of())).isEqualTo(1);
        List<PriceListColumn> plain = List.of(new PriceListColumn(0, "Цена А"), new PriceListColumn(1, "Цена Б"));
        List<ParsedPriceRow> rows = List.of(
                new ParsedPriceRow(1, null, "x", null, List.of(new BigDecimal("100"), new BigDecimal("90")), null),
                new ParsedPriceRow(2, null, "y", null, List.of(new BigDecimal("50"), new BigDecimal("40")), null));
        assertThat(PriceListHeader.defaultColumn(plain, rows)).isEqualTo(1);
        assertThat(PriceListHeader.defaultColumn(List.of(new PriceListColumn(0, "Цена")), rows)).isZero();
        assertThat(PriceListHeader.defaultColumn(List.of(), rows)).isNull();
    }
}
```

`PriceTextLinesTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.service.pricelist.PriceTextLines;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceTextLinesTest {

    @Test
    void dotLeaderLines_sections_text() {
        PriceTextLines.Result r = PriceTextLines.read(List.of(
                "ООО «ZZ-Си»", "115419, г. Москва, ул. Орджоникидзе, дом 11", "тел.: +7 (495) 222-22-74",
                "РЕКОМЕНДОВАННЫЕ РОЗНИЧНЫЕ ЦЕНЫ", "СТЕРИЛИЗАТОРЫ ОЗОНОВЫЕ «ZZ» - МНОГОФУНКЦИОНАЛЬНЫЕ:",
                "  1.  Стерилизатор озоновый «ZZ» СК – 16 л ……………………...…..……....      957 000 руб.",
                "    Стерилизатор озоновый «ZZ» без камеры (генератор озона)………………..   475 000 руб.",
                "Ресурс работы рассчитан на 1 год."));
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).name()).isEqualTo("Стерилизатор озоновый «ZZ» СК – 16 л");
        assertThat(r.rows().get(0).prices().get(0)).isEqualByComparingTo("957000");
        assertThat(r.rows().get(0).section()).isEqualTo("СТЕРИЛИЗАТОРЫ ОЗОНОВЫЕ «ZZ» - МНОГОФУНКЦИОНАЛЬНЫЕ");
        assertThat(r.rows().get(1).name()).isEqualTo("Стерилизатор озоновый «ZZ» без камеры (генератор озона)");
        assertThat(r.currency()).isEqualTo("RUB");
        assertThat(r.text()).contains("РЕКОМЕНДОВАННЫЕ РОЗНИЧНЫЕ ЦЕНЫ", "Ресурс работы рассчитан на 1 год.")
                .doesNotContain("  1.  Стерилизатор озоновый «ZZ» СК – 16 л ……………………...…..……....      957 000 руб.");
    }
}
```

`PriceListReaderTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.service.pricelist.ParsedPriceList;
import com.vladoose.nir.service.pricelist.ParsedPriceRow;
import com.vladoose.nir.service.pricelist.PriceListReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PriceListReaderTest {

    @Test
    void excelTable_twoPrices_sections_mergedReg_termsAndDates() throws Exception {
        ParsedPriceList p = PriceListReader.read("ZZ прайс.xlsx", ReplyFileKind.EXCEL, PriceListFiles.axionLikeXlsx(), Map.of());
        assertThat(p.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(p.columns()).extracting(PriceListColumn::index).containsExactly(0, 1);
        assertThat(p.ourColumn()).isEqualTo(1);                                       // дилерская
        assertThat(p.currency()).isEqualTo("RUB");
        assertThat(p.rows()).extracting(ParsedPriceRow::name).containsExactly("Транспортный ИВЛ А-ИВЛ-Э-03",
                "Дефибриллятор ДКИ-Н-12. Базовые функции дефибриллятора", "Дефибриллятор ДКИ-Н-12 (без сумки)",
                "Отсасыватель медицинский портативный ОМП-5/80-01");
        assertThat(p.rows()).extracting(ParsedPriceRow::section).containsExactly("Реанимация", "Реанимация", "Реанимация", "Хирургия");
        assertThat(p.rows().get(1).prices().get(0)).isEqualByComparingTo("205000");
        assertThat(p.rows().get(1).prices().get(1)).isEqualByComparingTo("150000");
        assertThat(p.rows().get(2).regText()).contains("0№024573");                  // объединённая по вертикали ячейка РУ
        assertThat(p.rows().get(3).regText()).isEqualTo("Нет РУ в Казахстане");
        assertThat(p.listDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.validTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(p.terms()).contains("Предоплата 100%").contains("FCA");
        assertThat(p.retail()).isFalse();
    }

    @Test
    void wordWithoutTable_textLines_retail() throws Exception {
        ParsedPriceList p = PriceListReader.read("Прайс ZZ 01_11_2025.docx", ReplyFileKind.WORD, PriceListFiles.orionLikeDocx(), Map.of());
        assertThat(p.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(p.rows()).hasSize(3);
        assertThat(p.rows().get(1).prices().get(0)).isEqualByComparingTo("1097000");
        assertThat(p.columns()).extracting(PriceListColumn::header).containsExactly("Цена");
        assertThat(p.ourColumn()).isZero();
        assertThat(p.retail()).isTrue();
        assertThat(p.listDate()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(p.currency()).isEqualTo("RUB");
    }

    @Test
    void garbage_failed_empty_noRows() {
        assertThat(PriceListReader.read("x.xlsx", ReplyFileKind.EXCEL, "не excel".getBytes(StandardCharsets.UTF_8), Map.of()).status())
                .isEqualTo(ReplyParseStatus.FAILED);
        assertThat(PriceListReader.read("x.png", ReplyFileKind.IMAGE, new byte[]{1, 2, 3}, Map.of()).status())
                .isEqualTo(ReplyParseStatus.NOT_PARSED);
        assertThat(PriceListReader.read("x.xlsx", ReplyFileKind.EXCEL, new byte[0], Map.of()).status())
                .isEqualTo(ReplyParseStatus.FAILED);
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.*' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `PriceListReader`, `PriceListHeader`, `PriceTextLines`, `PriceListCodes`.

- [ ] **Step 3: Доступ к общим помощникам**

`OfferHeader.date(String)` — `public static`. `SupplierReplyFiles.pdfLines(byte[])` — `public static`.
`ReplyTableReader.joined(List<String>)` — `public static` (сигнатура не меняется). В `ReplyTableReader`:

```java
    public static List<ReadTable> readAll(byte[] content, ReplyFileKind kind, Map<String, LineField> learned) throws IOException {
        return readAll(content, kind, learned, false);
    }

    /**
     * expandVertical — объединённые по вертикали ячейки Excel (одна колонка, несколько строк: «РУ» на два варианта модели)
     * получают текст во всех строках, а не только в шапке; прайсы (спека 2026-10-23 §5.2). Ответы поставщиков — как раньше.
     */
    public static List<ReadTable> readAll(byte[] content, ReplyFileKind kind, Map<String, LineField> learned, boolean expandVertical)
            throws IOException {
        List<Grid> grids = document(content, kind, expandVertical).grids();
        List<ReadTable> out = new ArrayList<>();
        for (int i = 0; i < grids.size(); i++) {
            Grid acc = grids.get(i);
            int j = i + 1;
            if (kind == ReplyFileKind.WORD) {
                for (; j < grids.size() && width(grids.get(j)) == width(grids.get(i))
                        && !startsWithHeader(grids.get(j), learned); j++) {
                    acc = glue(acc, grids.get(j));
                }
            }
            Optional<Parsed> p = parse(acc, learned);
            if (p.isEmpty()) continue;
            out.add(new ReadTable(p.get().table(), p.get().total()));
            i = j - 1;   // продолжения — строки этой таблицы
        }
        return out;
    }

    static Document document(byte[] content, ReplyFileKind kind) throws IOException {
        return document(content, kind, false);
    }

    static Document document(byte[] content, ReplyFileKind kind, boolean expandVertical) throws IOException {
        return switch (kind) {
            case EXCEL -> new Document(excel(content, expandVertical), List.of());
            case WORD -> ole2(content) ? doc(content) : docx(content);
            default -> new Document(List.of(), List.of());
        };
    }
```

`excel(byte[])` → `excel(byte[] content, boolean expandVertical)`; в цикле объединённых областей:

```java
                // объединённые ячейки шапки: текст — во все ячейки области («Цена, тг» над «за ед. | сумма»); у прайса — ещё
                // вертикальные в строках данных («РУ» на две комплектации)
                for (CellRangeAddress m : sheet.getMergedRegions()) {
                    boolean vertical = m.getFirstColumn() == m.getLastColumn() && m.getLastRow() > m.getFirstRow();
                    boolean header = m.getFirstRow() <= HEADER_SCAN + 1;
                    if (!(header || (expandVertical && vertical)) || m.getFirstRow() >= rows.size()) continue;
                    String v = get(rows, m.getFirstRow(), m.getFirstColumn());
                    for (int r = m.getFirstRow(); r <= Math.min(m.getLastRow(), rows.size() - 1); r++) {
                        for (int c = m.getFirstColumn(); c <= Math.min(m.getLastColumn(), MAX_COLS - 1); c++) set(rows, r, c, v);
                    }
                }
```

- [ ] **Step 4: Коды моделей и шапка**

`PriceListCodes`:

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.service.productwatch.BrandModels;
import com.vladoose.nir.service.productwatch.ProductText;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Коды моделей строк прайса и ключ товара (решение плана 8): коды — как у наших товаров ({@link BrandModels#modelCodes}),
 * плюс «СК – 16» с пробелами вокруг тире; ключ — нижний регистр, латинские двойники → кириллица, без разделителей
 * («ДКИ-Н-12» → «дкин12»). Одинаковое правило с обеих сторон — товар сайта и строка прайса сходятся по ключу.
 */
public final class PriceListCodes {

    private static final Pattern SPACED = Pattern.compile("(?<![\\p{L}\\p{N}])(\\p{L}{2,6})\\s+[–—-]\\s+(\\d{2,4})(?!\\p{N})");
    private static final String LATIN = "abcehkmoptxy";
    private static final String CYRILLIC = "авсенкмортху";

    private PriceListCodes() {}

    public static List<String> codes(String name) {
        List<String> out = new ArrayList<>(BrandModels.modelCodes(name));
        if (name != null) {
            Matcher m = SPACED.matcher(name);
            while (m.find()) {
                String c = m.group(1) + "-" + m.group(2);
                if (ProductText.digits(c) >= 2 && !out.contains(c)) out.add(c);
            }
        }
        return out;
    }

    public static String key(String code) {
        if (code == null) return null;
        StringBuilder b = new StringBuilder(code.length());
        for (char ch : code.toLowerCase(Locale.ROOT).replace('ё', 'е').toCharArray()) {
            if (!Character.isLetterOrDigit(ch)) continue;
            int i = LATIN.indexOf(ch);
            b.append(i >= 0 ? CYRILLIC.charAt(i) : ch);
        }
        return b.isEmpty() ? null : b.toString();
    }

    public static Set<String> keys(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : codes(text)) {
            String k = key(c);
            if (k != null) out.add(k);
        }
        return out;
    }
}
```

`PriceListHeader`:

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.service.readyoffer.OfferHeader;
import com.vladoose.nir.service.supplierreply.MoneyText;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Шапка прайса из текста вне таблицы (спека §5.2 п. 4): дата и «действует до» — только в строках про прайс или цены
 * (срок РУ «Действителен до 03.05.2028» — не срок прайса), иначе дата из имени файла; «розничный»; условия поставки.
 */
public record PriceListHeader(LocalDate listDate, LocalDate validTo, boolean retail, String terms) {

    static final int TERMS_MAX = 1000;
    private static final String MONTH = "(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)";
    private static final String DATE = "(?:\\d{1,2}\\.\\d{1,2}\\.\\d{4}|\\d{1,2}\\s+" + MONTH + "\\s+\\d{4})";
    private static final Pattern FROM = Pattern.compile("(?iu)(?<!\\p{L})(?:с|от)\\.?\\s*(" + DATE + ")");
    private static final Pattern TO = Pattern.compile("(?iu)(?<!\\p{L})(?:по|до)\\s*(" + DATE + ")");
    private static final Pattern PRICE_LINE = Pattern.compile("(?iu)прайс|(?<!\\p{L})цен(?:а|ы|у)?(?!\\p{L})|действительн|актуал");
    private static final Pattern FILE_DATE = Pattern.compile("(?<!\\d)(\\d{2})[._-](\\d{2})[._-](\\d{4})(?!\\d)");
    private static final Pattern RETAIL = Pattern.compile("(?iu)розничн|(?<!\\p{L})ррц(?!\\p{L})");
    private static final Pattern TERM = Pattern.compile("(?iu)^\\s*[•·*]|цены указаны|предоплат|отгрузк|доставк"
            + "|(?<!\\p{L})(?:fca|exw|dap|ddp|cpt|cip)(?!\\p{L})|ст-1|поверк|гаранти");

    public static PriceListHeader of(List<String> lines, String fileName) {
        LocalDate from = null, to = null;
        boolean retail = false;
        List<String> terms = new ArrayList<>();
        for (String raw : lines == null ? List.<String>of() : lines) {
            if (raw == null) continue;
            String line = raw.replace('\u00A0', ' ').strip();
            if (line.isEmpty()) continue;
            if (RETAIL.matcher(line).find()) retail = true;
            if (PRICE_LINE.matcher(line).find()) {
                if (from == null) {
                    Matcher m = FROM.matcher(line);
                    if (m.find()) from = OfferHeader.date(m.group(1));
                }
                if (to == null) {
                    Matcher m = TO.matcher(line);
                    if (m.find()) to = OfferHeader.date(m.group(1));
                }
            }
            if (TERM.matcher(line).find()) terms.add(line);
        }
        if (from == null && fileName != null) {
            Matcher m = FILE_DATE.matcher(fileName);
            if (m.find()) {
                try {
                    from = LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
                } catch (DateTimeException ignored) {
                    // «31_02_2026» — не дата
                }
            }
        }
        return new PriceListHeader(from, to, retail, joinTerms(terms));
    }

    private static String joinTerms(List<String> terms) {
        if (terms.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String t : terms) {
            if (b.length() + t.length() + 1 > TERMS_MAX) break;
            if (!b.isEmpty()) b.append('\n');
            b.append(t);
        }
        return b.isEmpty() ? null : b.toString();
    }

    /** Валюта по тексту: одна — она, разные (или суммы в тысячах) — MIXED, не названа — null. */
    public static String currency(List<String> lines) {
        Set<String> codes = new TreeSet<>();
        for (String l : lines == null ? List.<String>of() : lines) codes.addAll(MoneyText.textCurrencies(l));
        if (codes.isEmpty()) return null;
        return codes.size() == 1 ? codes.iterator().next() : MoneyText.MIXED;
    }

    /** Колонка «наша закупка» по умолчанию (спека §5.2 п. 5): «дилер» → «со скидкой» → «опт» → самая низкая цена в большинстве строк. */
    public static Integer defaultColumn(List<PriceListColumn> columns, List<ParsedPriceRow> rows) {
        if (columns == null || columns.isEmpty()) return null;
        if (columns.size() == 1) return columns.get(0).index();
        for (String key : List.of("дилер", "со скидк", "с учетом скидк", "опт")) {
            for (PriceListColumn c : columns) {
                String h = c.header() == null ? "" : c.header().toLowerCase(Locale.ROOT).replace('ё', 'е');
                if (h.contains(key)) return c.index();
            }
        }
        int[] wins = new int[columns.size()];
        for (ParsedPriceRow r : rows) {
            int best = -1;
            for (int i = 0; i < r.prices().size() && i < wins.length; i++) {
                BigDecimal p = r.prices().get(i);
                if (p != null && (best < 0 || p.compareTo(r.prices().get(best)) < 0)) best = i;
            }
            if (best >= 0) wins[best]++;
        }
        int arg = 0;
        for (int i = 1; i < wins.length; i++) if (wins[i] > wins[arg]) arg = i;
        return columns.get(arg).index();
    }
}
```

- [ ] **Step 5: Строки текста**

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.service.supplierreply.MoneyText;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Прайс строками текста (Word без таблицы, PDF без сетки; спека §5.2 п. 2): «1. Стерилизатор … СК – 16 л ……… 957 000
 * руб.» — наименование, заполнитель (точки, многоточие, три пробела или табуляция), одна цена в конце строки, валюта.
 * Раздел — строка без цены в верхнем регистре или с «:» в конце. Остальное — текст документа (шапка и условия).
 */
public final class PriceTextLines {

    public record Result(List<ParsedPriceRow> rows, List<String> text, String currency) {}

    static final int ROWS_MAX = 2000;
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d{1,4}[.)]\\s+");
    private static final Pattern LINE = Pattern.compile("(?iu)^(?<name>.*?\\p{L}.*?)\\s*(?:[.…·_]{2,}|\\s{3,}|\\t)\\s*"
            + "(?<price>\\d{1,3}(?:[\\s\\u00A0]\\d{3})+(?:[.,]\\d{1,2})?|\\d{4,}(?:[.,]\\d{1,2})?)\\s*"
            + "(?<cur>руб\\.?|рублей|рубля|₽|тенге|тг\\.?|₸|\\$|usd|€|eur|евро|юан\\p{L}*|cny)?\\.?\\s*$");
    private static final Pattern TAIL = Pattern.compile("[\\s.…·_]+$");

    private PriceTextLines() {}

    public static Result read(List<String> lines) {
        List<ParsedPriceRow> rows = new ArrayList<>();
        List<String> text = new ArrayList<>();
        Set<String> currencies = new TreeSet<>();
        String section = null;
        int n = 0;
        for (String raw : lines == null ? List.<String>of() : lines) {
            n++;
            if (raw == null) continue;
            String line = raw.replace('\u00A0', ' ').strip();
            if (line.isEmpty()) continue;
            Matcher m = LINE.matcher(NUMBERED.matcher(line).replaceFirst(""));
            if (m.find()) {
                BigDecimal price = MoneyText.amount(m.group("price"));
                String name = TAIL.matcher(m.group("name")).replaceAll("").replaceAll("\\s+", " ").strip();
                if (price != null && price.signum() > 0 && name.length() >= 3) {
                    rows.add(new ParsedPriceRow(n, section, cut(name, 1000), null, List.of(price), null));
                    String cur = m.group("cur") == null ? null : MoneyText.currency(m.group("cur"));
                    if (cur != null) currencies.add(cur);
                    if (rows.size() >= ROWS_MAX) break;
                    continue;
                }
            }
            if (section(line)) section = cut(line.replaceAll("[:：]\\s*$", "").strip(), 255);
            text.add(line);
        }
        String currency = currencies.isEmpty() ? null : currencies.size() == 1 ? currencies.iterator().next() : MoneyText.MIXED;
        return new Result(rows, text, currency);
    }

    /** Раздел: не длиннее 120, без чисел от трёх цифр, в верхнем регистре (≥ 80 % букв) или с «:» в конце. */
    static boolean section(String line) {
        if (line.length() > 120 || line.matches(".*\\d{3,}.*")) return false;
        if (line.endsWith(":")) return true;
        int letters = 0, upper = 0;
        for (char c : line.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) upper++;
            }
        }
        return letters >= 4 && upper * 5 >= letters * 4;
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 6: Разборщик**

`ParsedPriceRow` и `ParsedPriceList` — как в Interfaces (`failed(status, note)` — без строк и колонок). `PriceListReader`:

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Разбор прайса (спека 2026-10-23 §5.2): Excel — таблица; Word — таблица, иначе строки текста; PDF — сетка (задача 6),
 * иначе строки текста. Строки — до последней строки с ценой (ниже — условия и срок), разделы — строки без цены с одной
 * надписью; шапка — из текста вне строк. Сбой — FAILED с пометкой, загрузка не падает.
 */
@Component
public class PriceListReader {

    public static final int PARSE_MAX = 15 * 1024 * 1024;
    static final long PARTS_MAX = 15L * 1024 * 1024;
    static final int ROWS_MAX = 2000;
    public static final String NO_ROWS = "Строки не найдены — добавьте вручную или попросите у поставщика Excel";

    private final HeaderDictionary dictionary;

    public PriceListReader(HeaderDictionary dictionary) {
        this.dictionary = dictionary;
    }

    public ParsedPriceList read(String fileName, ReplyFileKind kind, byte[] bytes) {
        return read(fileName, kind, bytes, dictionary.learned());
    }

    public static ParsedPriceList read(String fileName, ReplyFileKind kind, byte[] bytes, Map<String, LineField> learned) {
        if (bytes == null || bytes.length == 0) return ParsedPriceList.failed(ReplyParseStatus.FAILED, "Файл пустой");
        if (bytes.length > PARSE_MAX) return ParsedPriceList.failed(ReplyParseStatus.NOT_PARSED, "Файл больше 15 МБ — строки впишите вручную");
        try {
            switch (kind) {
                case EXCEL, WORD -> {
                    byte[] parse = OoxmlSlim.forParsing(bytes, PARTS_MAX);
                    if (parse == null) return ParsedPriceList.failed(ReplyParseStatus.NOT_PARSED, "Файл слишком большой для разбора — строки впишите вручную");
                    List<String> all = ReplyTableReader.allLines(parse, kind);
                    Optional<ReplyTable> t = ReplyTableReader.readAll(parse, kind, learned, true).stream()
                            .map(ReplyTableReader.ReadTable::table).max(Comparator.comparingLong(PriceListReader::pricedRows));
                    if (t.isPresent() && pricedRows(t.get()) > 0) return fromTable(t.get(), all, true, fileName);
                    return fromText(PriceTextLines.read(all), fileName);
                }
                case PDF -> {
                    return fromText(PriceTextLines.read(SupplierReplyFiles.pdfLines(bytes)), fileName);   // сетка — задача 6
                }
                default -> {
                    return ParsedPriceList.failed(ReplyParseStatus.NOT_PARSED, "Прайс картинкой не разбирается — строки впишите вручную");
                }
            }
        } catch (Exception | Error e) {   // как SupplierReplyFiles: и OutOfMemoryError внутри JVM — пометка, не падение загрузки
            return ParsedPriceList.failed(ReplyParseStatus.FAILED,
                    kind == ReplyFileKind.PDF ? "PDF не открылся — строки впишите вручную" : "Файл не открылся: защищён паролем или повреждён");
        }
    }

    /** Таблица → строки прайса; excludeData — убрать из текста шапки строки данных (у Excel / Word текст — весь документ). */
    public static ParsedPriceList fromTable(ReplyTable t, List<String> text, boolean excludeData, String fileName) {
        List<Integer> priceCols = new ArrayList<>(), nameCols = new ArrayList<>();
        Integer article = null, brand = null, reg = null;
        for (ReplyTable.Column c : t.columns()) {
            switch (c.role()) {
                case PRICE -> priceCols.add(c.index());
                case NAME -> nameCols.add(c.index());
                case ARTICLE -> { if (article == null) article = c.index(); }
                case MANUFACT -> { if (brand == null) brand = c.index(); }
                case REG -> { if (reg == null) reg = c.index(); }
                default -> { }
            }
        }
        List<PriceListColumn> columns = new ArrayList<>();
        for (int i = 0; i < priceCols.size(); i++) columns.add(new PriceListColumn(i, t.columns().get(priceCols.get(i)).header()));
        int lastPriced = -1;
        for (int i = 0; i < t.rows().size(); i++) if (priced(t.rows().get(i).cells(), priceCols)) lastPriced = i;
        Set<String> data = new HashSet<>();
        data.add(norm(String.join(" ", t.columns().stream().map(ReplyTable.Column::header).toList())));
        List<ParsedPriceRow> rows = new ArrayList<>();
        String section = null;
        boolean truncated = false;
        for (int i = 0; i <= lastPriced; i++) {
            ReplyTable.Row r = t.rows().get(i);
            List<String> cells = r.cells();
            data.add(norm(ReplyTableReader.joined(cells)));
            List<BigDecimal> prices = new ArrayList<>();
            for (int c : priceCols) prices.add(price(cell(cells, c)));
            boolean any = prices.stream().anyMatch(Objects::nonNull);
            String name = join(cells, nameCols);
            String b = brand == null ? "" : cell(cells, brand);
            if (!b.isBlank() && !name.toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT))) name = (name + " " + b).strip();
            if (!any) {
                String s = sectionText(cells);
                if (s != null) {
                    section = PriceTextLines.cut(s, 255);
                    continue;
                }
            }
            if (name.isBlank()) continue;
            rows.add(new ParsedPriceRow(r.fileRow(), section, PriceTextLines.cut(name, 1000),
                    article == null ? null : blankToNull(PriceTextLines.cut(cell(cells, article), 100)), prices,
                    reg == null ? null : blankToNull(PriceTextLines.cut(cell(cells, reg), 500))));
            if (rows.size() >= ROWS_MAX) {
                truncated = true;
                break;
            }
        }
        List<String> rest = new ArrayList<>();
        for (String l : text) if (!excludeData || !data.contains(norm(l))) rest.add(l);
        PriceListHeader h = PriceListHeader.of(rest, fileName);
        String currency = t.currency() != null ? t.currency() : PriceListHeader.currency(rest);
        return new ParsedPriceList(ReplyParseStatus.PARSED, null, currency, columns, rows,
                PriceListHeader.defaultColumn(columns, rows), h.listDate(), h.validTo(), h.retail(), h.terms(), truncated);
    }

    static ParsedPriceList fromText(PriceTextLines.Result r, String fileName) {
        PriceListHeader h = PriceListHeader.of(r.text(), fileName);
        if (r.rows().isEmpty()) {
            return new ParsedPriceList(ReplyParseStatus.NO_TABLE, NO_ROWS, PriceListHeader.currency(r.text()), List.of(), List.of(),
                    null, h.listDate(), h.validTo(), h.retail(), h.terms(), false);
        }
        String currency = r.currency() != null ? r.currency() : PriceListHeader.currency(r.text());
        return new ParsedPriceList(ReplyParseStatus.PARSED, null, currency, List.of(new PriceListColumn(0, "Цена")), r.rows(), 0,
                h.listDate(), h.validTo(), h.retail(), h.terms(), r.rows().size() >= ROWS_MAX);
    }

    static long pricedRows(ReplyTable t) {
        List<Integer> priceCols = t.columns().stream().filter(c -> c.role() == LineField.PRICE).map(ReplyTable.Column::index).toList();
        return t.rows().stream().filter(r -> priced(r.cells(), priceCols)).count();
    }

    private static boolean priced(List<String> cells, List<Integer> priceCols) {
        for (int c : priceCols) if (price(cell(cells, c)) != null) return true;
        return false;
    }

    private static BigDecimal price(String cell) {
        BigDecimal v = MoneyText.amount(cell);
        return v == null || v.signum() <= 0 ? null : v;
    }

    /** Строка-раздел: одна надпись (одна непустая ячейка или все непустые одинаковы), не длиннее 120. */
    private static String sectionText(List<String> cells) {
        String only = null;
        for (String c : cells) {
            if (c == null || c.isBlank()) continue;
            String v = c.strip();
            if (only == null) only = v;
            else if (!only.equals(v)) return null;
        }
        return only == null || only.length() > 120 ? null : only;
    }

    private static String join(List<String> cells, List<Integer> cols) {
        StringBuilder b = new StringBuilder();
        for (int c : cols) {
            String v = cell(cells, c).strip();
            if (v.isEmpty() || b.toString().contains(v)) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(v);
        }
        return b.toString().replaceAll("\\s+", " ").strip();
    }

    private static String cell(List<String> cells, int c) {
        return c < cells.size() && cells.get(c) != null ? cells.get(c) : "";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace('\u00A0', ' ').replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
```

В `case PDF` пока только строки текста; задача 6 вставляет сетку первой.

- [ ] **Step 7: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.*' --tests 'com.vladoose.nir.service.supplierreply.*' --tests 'com.vladoose.nir.readyoffer.*' 2>&1 | tail -20`
Expected: PASS (гейт `SupplierReplyGateTest` — 21 из 22, перепутано 0: ответы поставщиков читают Excel без вертикального
развёртывания).

- [ ] **Step 8: Мутации**

1. `expandVertical` не передавать (всегда `false`) → `excelTable_twoPrices_sections_mergedReg_termsAndDates` краснеет (РУ строки 3 пусто).
2. Строки после последней цены — тоже строки (`i < t.rows().size()`) → тот же тест (условия ушли в строки, срок не найден).
3. `PRICE_LINE` не требовать → `datesFromPriceLines_notFromRegistrationText` краснеет (до 03.05.2028).
4. `defaultColumn` без «дилер» → `defaultColumn_dealerThenDiscountThenWholesaleThenCheapest` краснеет (взял бы «опт»).
5. `SPACED` убрать → `codesAndKeys` краснеет («СК-16» не найден).
6. `LATIN → CYRILLIC` убрать → тот же тест (ключ латиницей не совпал).
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 9: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/pricelist src/main/java/com/vladoose/nir/service/supplierreply/ReplyTableReader.java src/main/java/com/vladoose/nir/service/supplierreply/SupplierReplyFiles.java src/main/java/com/vladoose/nir/service/readyoffer/OfferHeader.java src/test/java/com/vladoose/nir/pricelist && git commit -m "feat(price-lists): разбор прайса — таблица Excel / Word, строки текста, разделы, РУ, срок и условия, коды моделей

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 6: PDF-таблица по сетке — `PdfTableReader`; инструмент для настоящих прайсов

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/pricelist/PdfTableReader.java`
- Modify: `src/main/java/com/vladoose/nir/service/pricelist/PriceListReader.java` (`case PDF` — сетка первой)
- Test: `src/test/java/com/vladoose/nir/pricelist/PdfTableReaderTest.java`, `src/test/java/com/vladoose/nir/pricelist/PriceListFiles.java`
  (+ `axionLikePdf()`), `src/test/java/com/vladoose/nir/pricelist/PriceListCorpusTool.java` (не тест: `PRICE_LIST_CORPUS=<папка>`)

**Interfaces:**
- Consumes: `PriceListReader.fromTable(ReplyTable, List<String>, boolean, String)` (задача 5), `HeaderRoles.role`,
  `MoneyText.currency`, PDFBox `PDFTextStripper` / `PDFGraphicsStreamEngine`.
- Produces: `PdfTableReader.read(byte[] pdf, Map<String, LineField> learned) → Optional<PdfTableReader.Result>`,
  `record Result(ReplyTable table, List<String> outside)` — таблица с ролями колонок по шапке первой страницы и текст вне
  сетки (заголовок, условия).

- [ ] **Step 1: Синтетический PDF — та же вёрстка, что у Аксиона**

В `PriceListFiles`:

```java
    /**
     * PDF как у Аксиона (спека §3): сетка линиями, шапка — ячейками в несколько строк, раздел — объединённой строкой
     * (внутренних вертикалей в его полосе нет), РУ — одна ячейка на две строки (горизонтали в колонке РУ нет), на второй
     * странице — своя сетка без шапки, под таблицей — условия и срок. Координаты — «сверху», как у PDF-текста.
     */
    public static byte[] axionLikePdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = new org.apache.pdfbox.pdmodel.PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             java.io.InputStream fontIn = PriceListFiles.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            org.apache.pdfbox.pdmodel.font.PDType0Font f = org.apache.pdfbox.pdmodel.font.PDType0Font.load(d, fontIn);
            org.apache.pdfbox.pdmodel.PDPage p1 = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            d.addPage(p1);
            float h = p1.getMediaBox().getHeight();
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p1)) {
                cs.setNonStrokingColor(0.92f);                         // фон шапки — толстая заливка с отступом 2 pt от сетки:
                cs.addRect(52, h - 138, 486, 36);                      // не линия, её края границами колонок не становятся
                cs.fill();
                cs.setNonStrokingColor(0f);
                text(cs, f, h, 200, 80, "Прайс-лист ООО «ZZ Завод»");
                float[] xs = {50, 70, 120, 300, 350, 400, 540};
                for (float y : new float[]{100, 140, 152, 175, 235}) hline(cs, h, 50, 540, y);
                hline(cs, h, 50, 400, 205);                            // в колонке РУ линии нет — ячейка на две строки
                vline(cs, h, 50, 100, 235);
                vline(cs, h, 540, 100, 235);
                for (int i = 1; i < xs.length - 1; i++) {               // в полосе раздела 140–152 внутренних линий нет
                    vline(cs, h, xs[i], 100, 140);
                    vline(cs, h, xs[i], 152, 235);
                }
                text(cs, f, h, 55, 122, "№");
                text(cs, f, h, 80, 122, "Фото");
                text(cs, f, h, 180, 122, "Наименование");
                text(cs, f, h, 303, 116, "Оптовая цена,");
                text(cs, f, h, 303, 124, "RUB");
                text(cs, f, h, 353, 116, "Цена дилера,");
                text(cs, f, h, 353, 124, "RUB");
                text(cs, f, h, 405, 122, "Разрешительные документы в Казахстане");
                text(cs, f, h, 190, 148, "Реанимация");
                text(cs, f, h, 57, 166, "1");
                text(cs, f, h, 125, 166, "Транспортный ИВЛ А-ИВЛ-Э-03");
                text(cs, f, h, 310, 166, "330 400");
                text(cs, f, h, 360, 166, "251 100");
                text(cs, f, h, 405, 166, "РУ РК-МТ-5№021045");
                text(cs, f, h, 57, 190, "2");
                text(cs, f, h, 125, 186, "Дефибриллятор ДКИ-Н-12.");
                text(cs, f, h, 125, 194, "Базовые функции");
                text(cs, f, h, 310, 190, "205 000");
                text(cs, f, h, 360, 190, "150 000");
                text(cs, f, h, 405, 202, "РУ РК МИ (МТ)-");
                text(cs, f, h, 405, 210, "0№024573 (бессрочно)");
                text(cs, f, h, 57, 222, "3");
                text(cs, f, h, 125, 222, "Дефибриллятор ДКИ-Н-12 (без сумки)");
                text(cs, f, h, 310, 222, "195 000");
                text(cs, f, h, 360, 222, "140 000");
            }
            org.apache.pdfbox.pdmodel.PDPage p2 = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            d.addPage(p2);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p2)) {
                float[] xs = {30, 45, 100, 290, 345, 395, 560};
                hline(cs, h, 30, 560, 60);
                hline(cs, h, 30, 560, 85);
                for (float x : xs) vline(cs, h, x, 60, 85);
                text(cs, f, h, 35, 74, "4");
                text(cs, f, h, 105, 74, "Отсасыватель портативный ОМП-5/80-01");
                text(cs, f, h, 300, 74, "66 800");
                text(cs, f, h, 355, 74, "60 100");
                text(cs, f, h, 400, 74, "Нет РУ в Казахстане");
                text(cs, f, h, 105, 110, "• Предоплата 100%;");
                text(cs, f, h, 105, 120, "• Указанный прайс-лист актуален с 01 сентября 2026 года по 31 декабря 2026 года.");
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    private static void text(org.apache.pdfbox.pdmodel.PDPageContentStream cs, org.apache.pdfbox.pdmodel.font.PDType0Font f,
                             float pageHeight, float x, float yTop, String s) throws java.io.IOException {
        cs.beginText();
        cs.setFont(f, 6);
        cs.newLineAtOffset(x, pageHeight - yTop);
        cs.showText(s);
        cs.endText();
    }

    private static void hline(org.apache.pdfbox.pdmodel.PDPageContentStream cs, float pageHeight, float x1, float x2, float yTop)
            throws java.io.IOException {
        cs.moveTo(x1, pageHeight - yTop);
        cs.lineTo(x2, pageHeight - yTop);
        cs.stroke();
    }

    private static void vline(org.apache.pdfbox.pdmodel.PDPageContentStream cs, float pageHeight, float x, float y1Top, float y2Top)
            throws java.io.IOException {
        cs.moveTo(x, pageHeight - y1Top);
        cs.lineTo(x, pageHeight - y2Top);
        cs.stroke();
    }
```

- [ ] **Step 2: Тест — красный**

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.entity.ReplyParseStatus;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.pricelist.ParsedPriceList;
import com.vladoose.nir.service.pricelist.ParsedPriceRow;
import com.vladoose.nir.service.pricelist.PdfTableReader;
import com.vladoose.nir.service.pricelist.PriceListReader;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTableReaderTest {

    @Test
    void grid_headerRoles_mergedCells_secondPageOwnGrid_outsideText() throws Exception {
        PdfTableReader.Result r = PdfTableReader.read(PriceListFiles.axionLikePdf(), Map.of()).orElseThrow();
        ReplyTable t = r.table();
        assertThat(t.columns()).extracting(ReplyTable.Column::role).containsExactly(LineField.NUMBER, LineField.IGNORE, LineField.NAME,
                LineField.PRICE, LineField.PRICE, LineField.REG);
        assertThat(t.columns().get(3).header()).isEqualTo("Оптовая цена, RUB");
        assertThat(t.rows()).hasSize(5);                                            // раздел + 3 строки + строка второй страницы
        assertThat(t.rows().get(0).cells().get(2)).isEqualTo("Реанимация");
        assertThat(t.rows().get(2).cells().get(2)).isEqualTo("Дефибриллятор ДКИ-Н-12. Базовые функции");
        assertThat(t.rows().get(2).cells().get(4)).isEqualTo("150 000");
        // объединённая на две строки ячейка РУ — у обеих строк; перенос после дефиса — без пробела
        assertThat(t.rows().get(2).cells().get(5)).isEqualTo("РУ РК МИ (МТ)-0№024573 (бессрочно)");
        assertThat(t.rows().get(3).cells().get(5)).isEqualTo("РУ РК МИ (МТ)-0№024573 (бессрочно)");
        assertThat(t.rows().get(4).cells().get(2)).isEqualTo("Отсасыватель портативный ОМП-5/80-01");   // своя сетка страницы 2
        assertThat(t.currency()).isEqualTo("RUB");
        assertThat(r.outside()).contains("Прайс-лист ООО «ZZ Завод»", "• Предоплата 100%;");
    }

    @Test
    void reader_pdfGrid_toPriceList() throws Exception {
        ParsedPriceList p = PriceListReader.read("ZZ прайс (01.09.2026).pdf", ReplyFileKind.PDF, PriceListFiles.axionLikePdf(), Map.of());
        assertThat(p.status()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(p.rows()).extracting(ParsedPriceRow::section).containsExactly("Реанимация", "Реанимация", "Реанимация", "Реанимация");
        assertThat(p.ourColumn()).isEqualTo(1);
        assertThat(p.rows().get(3).prices().get(1)).isEqualByComparingTo("60100");
        assertThat(p.listDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.validTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(p.terms()).contains("Предоплата 100%");
    }

    /** Review Focus 5: PDF без сетки — строки текста; без текста (скан) — «строки не найдены», без падения. */
    @Test
    void noGrid_textLines_scan_noRows() throws Exception {
        ParsedPriceList text = PriceListReader.read("прайс.pdf", ReplyFileKind.PDF,
                ReplyFixtures.textPdf("Стерилизатор озоновый СК-16 …………… 957 000 руб.", "Предоплата 100%"), Map.of());
        assertThat(text.rows()).hasSize(1);
        assertThat(text.rows().get(0).prices().get(0)).isEqualByComparingTo("957000");
        ParsedPriceList scan = PriceListReader.read("скан.pdf", ReplyFileKind.PDF, ReplyFixtures.pdfBytes(1), Map.of());
        assertThat(scan.status()).isEqualTo(ReplyParseStatus.NO_TABLE);
        assertThat(scan.note()).isEqualTo(PriceListReader.NO_ROWS);
        assertThat(PdfTableReader.read(ReplyFixtures.textPdf("просто текст"), Map.of())).isEmpty();
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.PdfTableReaderTest' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `PdfTableReader`.

- [ ] **Step 3: `PdfTableReader`**

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.LineField;
import com.vladoose.nir.entity.ReplyCurrencySource;
import com.vladoose.nir.entity.ReplyTable;
import com.vladoose.nir.service.supplierreply.HeaderRoles;
import com.vladoose.nir.service.supplierreply.MoneyText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.*;

/**
 * PDF-таблица по сетке (спека 2026-10-23 §5.2 п. 3, решение плана 7). Прайс, выгруженный из Calc / Excel / Word, рисует
 * рамки ячеек: вертикальные линии — колонки, горизонтальные — строки; текст ячейки — символы внутри её прямоугольника.
 * Объединённая ячейка (раздел во всю ширину, РУ на несколько строк) — там, где линии нет; её текст получают все строки,
 * которые она накрывает. Колонки — по каждой странице, роли — по шапке первой страницы с шапкой, по порядку колонок.
 * Страница с другим числом колонок — не продолжение таблицы. Текст вне сетки — для шапки прайса и условий.
 */
public final class PdfTableReader {

    public record Result(ReplyTable table, List<String> outside) {}

    /** Символ: x слева, y — базовая линия от верха страницы (TextPosition.getYDirAdj), ширина, высота, ширина пробела шрифта. */
    record Glyph(String ch, float x, float y, float w, float h, float space) {
        float cx() { return x + w / 2; }
        float cy() { return y - h / 2; }
    }

    /** Отрезок сетки в координатах «сверху»: вертикальный — x = at, y от from до to; горизонтальный — y = at, x от from до to. */
    record Seg(boolean vertical, float at, float from, float to) {}

    static final int MAX_PAGES = 30;
    static final float JOIN = 1.5f;
    static final float MIN_SEG = 4f;
    static final float THIN = 2f;

    private PdfTableReader() {}

    public static Optional<Result> read(byte[] pdf, Map<String, LineField> learned) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            List<String> headers = null;
            List<LineField> roles = null;
            List<ReplyTable.Row> rows = new ArrayList<>();
            List<String> outside = new ArrayList<>();
            int rowNo = 0;
            for (int p = 0; p < pages; p++) {
                PDPage page = doc.getPage(p);
                List<Glyph> glyphs = glyphs(doc, p + 1);
                Grid g = Grid.of(segments(page));
                if (g == null) {
                    outside.addAll(lines(glyphs));
                    continue;
                }
                outside.addAll(lines(g.outside(glyphs)));
                List<List<String>> cells = g.cells(glyphs);
                int start;
                if (headers == null) {
                    int h = header(cells, learned);
                    if (h < 0) continue;
                    headers = cells.get(h);
                    roles = headers.stream().map(x -> HeaderRoles.role(x, learned)).toList();
                    start = h + 1;
                } else if (cells.isEmpty() || cells.get(0).size() != headers.size()) {
                    continue;
                } else {
                    start = header(cells, learned) == 0 ? 1 : 0;              // шапка повторена на странице
                }
                for (int r = start; r < cells.size(); r++) {
                    List<String> row = cells.get(r);
                    if (row.stream().allMatch(String::isBlank)) continue;
                    rows.add(new ReplyTable.Row(++rowNo, row));
                }
            }
            if (headers == null) return Optional.empty();
            List<ReplyTable.Column> cols = new ArrayList<>();
            String currency = null;
            for (int c = 0; c < headers.size(); c++) {
                cols.add(new ReplyTable.Column(c, headers.get(c), roles.get(c)));
                if (currency == null && roles.get(c) == LineField.PRICE) currency = MoneyText.currency(headers.get(c));
            }
            ReplyTable.CurrencyFrom from = new ReplyTable.CurrencyFrom(
                    currency == null ? ReplyCurrencySource.NONE : ReplyCurrencySource.TABLE, List.of(), null, null);
            int score = (int) roles.stream().filter(x -> x != LineField.IGNORE).count();
            return Optional.of(new Result(new ReplyTable("PDF", 1, score, currency, cols, rows, from, null), outside));
        }
    }

    /** Шапка — среди первых 5 строк сетки: строка с наименованием и ценой (как у таблиц Excel), иначе −1. */
    static int header(List<List<String>> cells, Map<String, LineField> learned) {
        for (int r = 0; r < Math.min(5, cells.size()); r++) {
            Set<LineField> rs = EnumSet.noneOf(LineField.class);
            for (String c : cells.get(r)) rs.add(HeaderRoles.role(c, learned));
            if (rs.contains(LineField.NAME) && rs.contains(LineField.PRICE)) return r;
        }
        return -1;
    }

    // ---- текст ----------------------------------------------------------------------------------------------------

    static List<Glyph> glyphs(PDDocument doc, int pageNo) throws IOException {
        List<Glyph> out = new ArrayList<>();
        PDFTextStripper s = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                for (TextPosition t : positions) {
                    String u = t.getUnicode();
                    if (u == null || u.isBlank()) continue;
                    out.add(new Glyph(u, t.getXDirAdj(), t.getYDirAdj(), t.getWidthDirAdj(), Math.max(t.getHeightDir(), 1f),
                            t.getWidthOfSpace()));
                }
            }
        };
        s.setSortByPosition(true);
        s.setStartPage(pageNo);
        s.setEndPage(pageNo);
        s.getText(doc);
        return out;
    }

    /** Символы → строки текста сверху вниз (одна строка — символы на одной высоте, ±0,6 высоты). */
    static List<String> lines(List<Glyph> glyphs) {
        List<String> out = new ArrayList<>();
        for (List<Glyph> line : group(glyphs)) {
            String t = join(line);
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    /** Текст ячейки: строки ячейки через пробел; строка, кончившаяся дефисом, склеивается со следующей без пробела. */
    static String cellText(List<Glyph> glyphs) {
        StringBuilder b = new StringBuilder();
        for (List<Glyph> line : group(glyphs)) {
            String t = join(line);
            if (t.isEmpty()) continue;
            if (!b.isEmpty() && b.charAt(b.length() - 1) != '-') b.append(' ');
            b.append(t);
        }
        return b.toString().strip();
    }

    private static List<List<Glyph>> group(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x));
        List<List<Glyph>> lines = new ArrayList<>();
        for (Glyph g : sorted) {
            List<Glyph> last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            if (last != null && Math.abs(last.get(0).y() - g.y()) <= 0.6f * Math.max(g.h(), last.get(0).h())) last.add(g);
            else lines.add(new ArrayList<>(List.of(g)));
        }
        for (List<Glyph> l : lines) l.sort(Comparator.comparingDouble(Glyph::x));
        return lines;
    }

    private static String join(List<Glyph> line) {
        StringBuilder b = new StringBuilder();
        Glyph prev = null;
        for (Glyph g : line) {
            if (prev != null) {
                float gap = g.x() - (prev.x() + prev.w());
                float space = prev.space() > 0 ? prev.space() : prev.h() * 0.5f;
                if (gap > space * 0.5f) b.append(' ');
            }
            b.append(g.ch());
            prev = g;
        }
        return b.toString().replaceAll("\\s+", " ").strip();
    }

    // ---- сетка ----------------------------------------------------------------------------------------------------

    /** Линии страницы: обведённые отрезки и стороны прямоугольников, а залитые — только тонкие (тоньше 2 pt) — рамки. */
    static List<Seg> segments(PDPage page) throws IOException {
        PDRectangle box = page.getMediaBox();
        float top = box.getLowerLeftY() + box.getHeight();
        List<Seg> out = new ArrayList<>();
        PDFGraphicsStreamEngine e = new PDFGraphicsStreamEngine(page) {
            private final List<float[]> path = new ArrayList<>();
            private Point2D cur;
            private Point2D start;

            private void edge(Point2D p, Point2D q) {
                path.add(new float[]{(float) p.getX(), (float) p.getY(), (float) q.getX(), (float) q.getY()});
            }

            @Override public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {
                edge(a, b); edge(b, c); edge(c, d); edge(d, a);
            }
            @Override public void moveTo(float x, float y) { cur = new Point2D.Float(x, y); start = cur; }
            @Override public void lineTo(float x, float y) {
                Point2D n = new Point2D.Float(x, y);
                if (cur != null) edge(cur, n);
                cur = n;
            }
            @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { cur = new Point2D.Float(x3, y3); }
            @Override public Point2D getCurrentPoint() { return cur; }
            @Override public void closePath() { if (cur != null && start != null) edge(cur, start); cur = start; }
            @Override public void endPath() { path.clear(); }
            @Override public void strokePath() { commit(false); }
            @Override public void fillPath(int windingRule) { commit(true); }
            @Override public void fillAndStrokePath(int windingRule) { commit(false); }
            @Override public void drawImage(PDImage pdImage) { }
            @Override public void clip(int windingRule) { }
            @Override public void shadingFill(COSName shadingName) { }

            private void commit(boolean fill) {
                if (fill) {
                    // заливка — рамка, только если вся фигура тонкая (линия, нарисованная прямоугольником); фон ячеек — нет
                    float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                    for (float[] s : path) {
                        minX = Math.min(minX, Math.min(s[0], s[2])); maxX = Math.max(maxX, Math.max(s[0], s[2]));
                        minY = Math.min(minY, Math.min(s[1], s[3])); maxY = Math.max(maxY, Math.max(s[1], s[3]));
                    }
                    if (!path.isEmpty()) {
                        if (maxX - minX < THIN && maxY - minY >= MIN_SEG) out.add(new Seg(true, (minX + maxX) / 2, top - maxY, top - minY));
                        else if (maxY - minY < THIN && maxX - minX >= MIN_SEG) out.add(new Seg(false, top - (minY + maxY) / 2, minX, maxX));
                    }
                } else {
                    for (float[] s : path) {
                        if (Math.abs(s[0] - s[2]) < 0.5f && Math.abs(s[1] - s[3]) >= MIN_SEG) {
                            out.add(new Seg(true, s[0], top - Math.max(s[1], s[3]), top - Math.min(s[1], s[3])));
                        } else if (Math.abs(s[1] - s[3]) < 0.5f && Math.abs(s[0] - s[2]) >= MIN_SEG) {
                            out.add(new Seg(false, top - s[1], Math.min(s[0], s[2]), Math.max(s[0], s[2])));
                        }
                    }
                }
                path.clear();
            }
        };
        e.processPage(page);
        return out;
    }

    /** Сетка страницы: границы колонок xs, границы строк ys (все), у каждой колонки — свои границы (объединённые ячейки). */
    record Grid(float[] xs, float[] ys, List<float[]> columnYs) {

        static Grid of(List<Seg> segs) {
            float[] xs = cluster(segs.stream().filter(Seg::vertical).map(Seg::at).toList());
            if (xs.length < 3) return null;
            List<Seg> h = segs.stream().filter(s -> !s.vertical()).toList();
            List<Float> ys = new ArrayList<>();
            List<float[]> columnYs = new ArrayList<>();
            for (int c = 0; c + 1 < xs.length; c++) {
                List<Float> own = new ArrayList<>();
                for (Seg s : h) if (s.from() <= xs[c] + JOIN && s.to() >= xs[c + 1] - JOIN) own.add(s.at());
                ys.addAll(own);
                columnYs.add(cluster(own));
            }
            float[] all = cluster(ys);
            return all.length < 2 ? null : new Grid(xs, all, columnYs);
        }

        List<Glyph> outside(List<Glyph> glyphs) {
            float topY = ys[0], bottomY = ys[ys.length - 1], left = xs[0], right = xs[xs.length - 1];
            return glyphs.stream().filter(g -> g.cy() < topY || g.cy() > bottomY || g.cx() < left || g.cx() > right).toList();
        }

        /** Ячейки строк сетки: у колонки — её собственные границы; текст объединённой ячейки — каждой строке под ней. */
        List<List<String>> cells(List<Glyph> glyphs) {
            int nr = ys.length - 1, nc = xs.length - 1;
            List<List<String>> out = new ArrayList<>();
            for (int r = 0; r < nr; r++) out.add(new ArrayList<>(Collections.nCopies(nc, "")));
            for (int c = 0; c < nc; c++) {
                float[] cb = columnYs.get(c).length < 2 ? ys : columnYs.get(c);
                float x1 = xs[c], x2 = xs[c + 1];
                for (int k = 0; k + 1 < cb.length; k++) {
                    float y1 = cb[k], y2 = cb[k + 1];
                    List<Glyph> in = glyphs.stream().filter(g -> g.cx() > x1 && g.cx() < x2 && g.cy() > y1 && g.cy() < y2).toList();
                    if (in.isEmpty()) continue;
                    String text = cellText(in);
                    for (int r = 0; r < nr; r++) {
                        float mid = (ys[r] + ys[r + 1]) / 2;
                        if (mid > y1 && mid < y2) out.get(r).set(c, text);
                    }
                }
            }
            return out;
        }
    }

    /** Координаты линий — по возрастанию, ближе JOIN — одна (среднее). */
    static float[] cluster(List<Float> values) {
        List<Float> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        List<Float> out = new ArrayList<>();
        float sum = 0;
        int n = 0;
        for (Float v : sorted) {
            if (n > 0 && v - sum / n > JOIN) {
                out.add(sum / n);
                sum = 0;
                n = 0;
            }
            sum += v;
            n++;
        }
        if (n > 0) out.add(sum / n);
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }
}
```

`PriceListReader` — `case PDF`:

```java
                case PDF -> {
                    Optional<PdfTableReader.Result> g = PdfTableReader.read(bytes, learned);
                    if (g.isPresent() && pricedRows(g.get().table()) > 0) {
                        return fromTable(g.get().table(), g.get().outside(), false, fileName);
                    }
                    return fromText(PriceTextLines.read(SupplierReplyFiles.pdfLines(bytes)), fileName);
                }
```

- [ ] **Step 4: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.*' 2>&1 | tail -20`
Expected: PASS. Если `textPdf` с «……» не даёт строку (шрифт рисует «…» иначе), строку прайса в тесте записать точками
«..........» — проверяется путь «без сетки → строки текста», а не вид многоточия.

- [ ] **Step 5: Инструмент для настоящих прайсов (не тест)**

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.entity.PriceListColumn;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.service.pricelist.ParsedPriceList;
import com.vladoose.nir.service.pricelist.ParsedPriceRow;
import com.vladoose.nir.service.pricelist.PriceListReader;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Разбор настоящих прайсов (спека §12): репозиторий публичный — файлы не кладутся, прогон локально:
 * PRICE_LIST_CORPUS=~/Downloads/прайсы ./gradlew test --tests '*PriceListCorpusTool*' -i. Печатает шапку и строки — сверять глазами.
 */
@EnabledIfEnvironmentVariable(named = "PRICE_LIST_CORPUS", matches = ".+")
class PriceListCorpusTool {

    @Test
    void print() throws Exception {
        try (Stream<Path> files = Files.list(Paths.get(System.getenv("PRICE_LIST_CORPUS")))) {
            for (Path f : files.sorted().toList()) {
                byte[] b = Files.readAllBytes(f);
                String name = f.getFileName().toString();
                ReplyFileKind kind = SupplierReplyFiles.kind(name, null, b);
                ParsedPriceList p = PriceListReader.read(name, kind, b, Map.of());
                System.out.println("=== " + name + " · " + kind + " · " + p.status() + (p.note() == null ? "" : " — " + p.note()));
                System.out.println("валюта " + p.currency() + " · колонки " + p.columns().stream().map(PriceListColumn::header).toList()
                        + " · наша " + p.ourColumn() + " · с " + p.listDate() + " по " + p.validTo() + " · розница " + p.retail());
                System.out.println("условия: " + p.terms());
                for (ParsedPriceRow r : p.rows()) {
                    System.out.println("  [" + r.section() + "] " + r.name() + " · " + r.prices() + " · РУ " + r.regText());
                }
            }
        }
    }
}
```

Проверка на настоящих файлах — в задаче 15 (`PRICE_LIST_CORPUS` — папка с копиями прайсов Аксиона и Орион-Си).

- [ ] **Step 6: Мутации**

1. `cells`: брать общие границы `ys` вместо своих у колонки → `grid_headerRoles_mergedCells_secondPageOwnGrid_outsideText` краснеет (РУ только у одной строки).
2. Колонки по первой странице для всех (`xs` первой страницы — и на второй) → строка второй страницы теряет текст → тест сетки
   краснеет.
3. `commit(true)` — брать и толстые заливки → тест сетки краснеет (края фона шапки дают лишние колонки).
4. `cellText`: всегда пробел между строками → тот же тест (`(МТ)- 0№…`).
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/pricelist src/test/java/com/vladoose/nir/pricelist && git commit -m "feat(price-lists): PDF-таблица по сетке — колонки и строки по линиям, объединённые ячейки, шапка первой страницы

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 7: Прайсы — загрузка, черновик, правка, «Сохранить прайс», связь с товарами, РУ, «было …», путь API

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/pricelist/PriceListService.java`, `PriceListWriter.java`,
  `PriceListLinker.java`, `PriceListRegs.java`, `PriceListViewBuilder.java`, `PriceListOrder.java`, `PriceListKeys.java`
- Create: `src/main/java/com/vladoose/nir/controller/PriceListController.java`,
  `src/main/java/com/vladoose/nir/dto/response/PriceListView.java`, `PriceListListItem.java`,
  `src/main/java/com/vladoose/nir/dto/request/PriceListPatch.java`
- Modify: `src/main/java/com/vladoose/nir/repository/PriceListItemRepository.java` (строки других действующих прайсов поставщика)
- Test: `src/test/java/com/vladoose/nir/pricelist/PriceListServiceTest.java`, `PriceListLinkerTest.java`,
  `PriceListViewTest.java`, `PriceListApiTest.java`

**Interfaces:**
- Consumes: `PriceListReader.read` (задачи 5–6), `PriceListCodes.codes / key` (задача 5), `CurrencyRates` и `RateMath`
  (задача 2), `SupplierReplyFiles.kind / sha256 / fileName`, `ReadyOfferController.contentType`, `MedRegistryRepository.
  findByNormalizedRegNumber`, `RegNumbers.normalize`, `SiteProductRepository.findByMarketAndRemovedAtIsNull`,
  `WestmedLeadMapper.productUrl`.
- Produces: `PriceListService.upload(Long distributorId, String fileName, String mime, byte[] bytes, String user) → Long`
  (id черновика или прежнего прайса того же файла), `patch(Long id, PriceListPatch, String user)`, `patchRow(Long id, Long rowId,
  PriceListPatch)`, `addRow(Long id)`, `deleteRow(Long id, Long rowId)`, `activate(Long id, String user)`, `delete(Long id)`,
  `guard(Long id) → PriceList` (404 чужого рынка), `static BigDecimal ourPrice(List<BigDecimal> prices, Integer column,
  BigDecimal discountPct)`; `PriceListLinker.link(PriceListItem, Market)` (модель, ключ, связь); `PriceListRegs.number(String)
  → String|null`, `PriceListRegs.none(String) → boolean`, `PriceListRegs.status(boolean noReg, String number, MedRegistry rec,
  LocalDate today) → "NONE" | null | "NOT_FOUND" | "ACTIVE" | "EXPIRED"`; `PriceListOrder.NEWER` (Comparator прайсов: дата прайса, иначе дата
  загрузки, затем id — новые первыми); `PriceListKeys.of(PriceListItem) → List<String>` («p…», «e…», «k…»),
  `PriceListKeys.of(Long siteProductId, Long equipmentId, String modelKey)`, `PriceListKeys.same(List, List)`;
  `PriceListViewBuilder.view(Long id) → PriceListView`, `list(Long supplier) → List<PriceListListItem>`.

- [ ] **Step 1: Тесты — красные**

`PriceListServiceTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PriceListPatch;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceListRepository;
import com.vladoose.nir.service.pricelist.PriceListService;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PriceListServiceTest {

    @Autowired PriceListService service;
    @Autowired PriceListRepository lists;
    @Autowired DistributorRepository distributors;
    Distributor axion;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        axion = PriceListFixtures.supplier(distributors, Market.KZ, "Аксион");
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void upload_draftRows_ourColumnPrice() throws Exception {
        Long id = service.upload(axion.getId(), "ZZ прайс.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        PriceList p = lists.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(PriceListStatus.DRAFT);
        assertThat(p.getItems()).hasSize(4);
        assertThat(p.getOurColumn()).isEqualTo(1);
        assertThat(p.getItems().get(1).getOurPrice()).isEqualByComparingTo("150000");
        assertThat(p.getItems().get(1).getModelKey()).isEqualTo("дкин12");
        assertThat(p.getItems().get(1).getRegNumber()).isEqualTo("РК МИ (МТ)-0№024573");
        assertThat(p.getItems().get(3).isNoReg()).isTrue();
        assertThat(p.getCreatedBy()).isEqualTo("vlad");
        assertThat(p.getMarket()).isEqualTo(Market.KZ);
    }

    /** Review Focus 4: тот же файл того же поставщика — прежний прайс; поставщик другого рынка — 400. */
    @Test
    void sameFile_returnsExisting_otherMarketSupplier_400() throws Exception {
        Long a = service.upload(axion.getId(), "ZZ прайс.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        Long b = service.upload(axion.getId(), "копия.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        assertThat(b).isEqualTo(a);
        Distributor rf = PriceListFixtures.supplier(distributors, Market.RF, "РФ");
        assertThatThrownBy(() -> service.upload(rf.getId(), "x.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Поставщик не найден");
    }

    /** Review Focus 5: скан — черновик без строк с пометкой; файл сохранён. */
    @Test
    void scan_draftWithoutRows() throws Exception {
        Long id = service.upload(axion.getId(), "скан.pdf", "application/pdf", ReplyFixtures.pdfBytes(1), "vlad");
        PriceList p = lists.findById(id).orElseThrow();
        assertThat(p.getItems()).isEmpty();
        assertThat(p.getParseNote()).startsWith("Строки не найдены");
        assertThat(p.getData()).isNotEmpty();
    }

    @Test
    void patch_columnDiscountCurrencyDates_rows() throws Exception {
        Long id = service.upload(axion.getId(), "ZZ прайс.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        service.patch(id, new PriceListPatch("OUR_COLUMN", "0"), "vlad");
        assertThat(lists.findById(id).orElseThrow().getItems().get(1).getOurPrice()).isEqualByComparingTo("205000");
        service.patch(id, new PriceListPatch("DISCOUNT_PCT", "10"), "vlad");
        assertThat(lists.findById(id).orElseThrow().getItems().get(1).getOurPrice()).isEqualByComparingTo("184500");
        assertThatThrownBy(() -> service.patch(id, new PriceListPatch("CURRENCY", "GBP"), "vlad")).hasMessage("Валюта — KZT, RUB, USD, EUR или CNY");
        assertThatThrownBy(() -> service.patch(id, new PriceListPatch("DISCOUNT_PCT", "100"), "vlad")).hasMessage("Скидка — от 0 до 99,99 %");
        assertThatThrownBy(() -> service.patch(id, new PriceListPatch("VALID_TO", "2026-01-01"), "vlad"))
                .hasMessage("«Действует до» раньше даты прайса");
        Long rowId = lists.findById(id).orElseThrow().getItems().get(0).getId();
        service.patchRow(id, rowId, new PriceListPatch("PRICE:1", "250 000"));
        PriceListItem row = lists.findById(id).orElseThrow().getItems().get(0);
        assertThat(row.getPrices().get(1)).isEqualByComparingTo("250000");
        service.patchRow(id, rowId, new PriceListPatch("MODEL", "ВМ-03"));
        assertThat(lists.findById(id).orElseThrow().getItems().get(0).getModelKey()).isEqualTo("вм03");
        assertThatThrownBy(() -> service.patchRow(id, rowId, new PriceListPatch("NAME", " "))).hasMessage("Наименование не может быть пустым");
        service.activate(id, "vlad");
        assertThat(lists.findById(id).orElseThrow().getStatus()).isEqualTo(PriceListStatus.ACTIVE);
        service.delete(id);
        assertThat(lists.findById(id)).isEmpty();
    }
}
```

`PriceListLinkerTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.pricelist.PriceListLinker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PriceListLinkerTest {

    @Autowired PriceListLinker linker;
    @Autowired SiteProductRepository products;
    @Autowired MedEquipmentRepository equipment;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private SiteProduct product(String name) {
        return products.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name(name)
                .slug("zz-" + System.nanoTime()).syncedAt(OffsetDateTime.now()).build());
    }

    private static PriceListItem row(String name) {
        return PriceListItem.builder().name(name).build();
    }

    @Test
    void uniqueSiteProduct_linked_ambiguous_not_catalogFallback() {
        SiteProduct dki = product("ZZ Дефибриллятор ДКИ-Н-99 «Аксион»");
        PriceListItem a = row("Дефибриллятор ДКИ-Н-99. Базовые функции");
        linker.link(a, Market.KZ);
        assertThat(a.getModel()).isEqualTo("ДКИ-Н-99");
        assertThat(a.getModelKey()).isEqualTo("дкин99");
        assertThat(a.getSiteProductId()).isEqualTo(dki.getId());

        product("ZZ Отсасыватель ОМП-9/90-01 со стойкой");
        product("ZZ Отсасыватель ОМП-9/90-01 без стойки");
        PriceListItem b = row("Отсасыватель портативный ОМП-9/90-01");
        linker.link(b, Market.KZ);
        assertThat(b.getSiteProductId()).isNull();                      // два наших товара — связь ставит человек

        MedEquipment e = equipment.saveAndFlush(MedEquipment.builder().name("ZZ Тренажёр ДА-Н-Т-99").manufact("ZZ").market(Market.KZ).build());
        PriceListItem c = row("Тренажер ДА-Н-Т-99");
        linker.link(c, Market.KZ);
        assertThat(c.getSiteProductId()).isNull();
        assertThat(c.getMedEquipmentId()).isEqualTo(e.getId());
    }
}
```

`PriceListViewTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.response.PriceListView;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.pricelist.PriceListService;
import com.vladoose.nir.service.pricelist.PriceListViewBuilder;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PriceListViewTest {

    @Autowired PriceListService service;
    @Autowired PriceListViewBuilder views;
    @Autowired PriceListRepository lists;
    @Autowired DistributorRepository distributors;
    @Autowired MedRegistryRepository registry;
    @Autowired CurrencyRates rates;
    @Autowired CurrencyRateRepository ours;
    @Autowired OfficialRateRepository official;
    Distributor axion;

    @BeforeEach
    void setUp() {
        ours.deleteAllInBatch();
        official.deleteAllInBatch();
        MarketContext.set(Market.KZ);
        axion = PriceListFixtures.supplier(distributors, Market.KZ, "Аксион вид");
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        official.saveAndFlush(OfficialRate.builder().source(CurrencyRates.NBRK).currency("RUB").rateDate(LocalDate.now(Market.KZ.zone()))
                .rate(new BigDecimal("5.48")).fetchedAt(OffsetDateTime.now()).build());
        if (registry.findByRegNumber("РК МИ (МТ)-0№024573").isEmpty()) {
            registry.saveAndFlush(MedRegistry.builder().regNumber("РК МИ (МТ)-0№024573").name("Дефибриллятор ДКИ-Н-12").unlimited(true).build());
        }
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void conversions_reg_previousPrice() throws Exception {
        // прежний действующий прайс того же поставщика: ДКИ-Н-12 по 140 000
        PriceList old = PriceListFixtures.list(lists, axion, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 3, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-12", "140000", "дкин12"});
        PriceListFixtures.list(lists, axion, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 12, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-12", "160000", "дкин12"});          // новее загружаемого — не «было»
        Long id = service.upload(axion.getId(), "ZZ прайс.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        PriceListView v = views.view(id);
        assertThat(v.ourRate()).isEqualByComparingTo("6");
        assertThat(v.todayRate()).isEqualByComparingTo("5.48");
        PriceListView.Row dki = v.rows().get(1);
        assertThat(dki.converted()).isEqualByComparingTo("900000");
        assertThat(dki.today()).isEqualByComparingTo("822000");
        assertThat(dki.regStatus()).isEqualTo("ACTIVE");
        assertThat(dki.prevPrice()).isEqualByComparingTo("140000");
        assertThat(dki.prevDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(dki.prevPct()).isEqualByComparingTo("7.1");
        assertThat(v.rows().get(3).regStatus()).isEqualTo("NONE");             // «Нет РУ в Казахстане»
        assertThat(v.rows().get(0).regStatus()).isIn("NOT_FOUND", "ACTIVE", "EXPIRED");   // РК-МТ-5№021045 — как в реестре nirdb
        assertThat(old.getId()).isNotNull();
    }
}
```

`PriceListApiTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.service.pricelist.PriceListService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class PriceListApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired DistributorRepository distributors;
    @Autowired PriceListService service;
    MockMvc mvc;
    Distributor axion;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        axion = PriceListFixtures.supplier(distributors, Market.KZ, "Аксион API");
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void upload_view_file_otherMarket404() throws Exception {
        String body = mvc.perform(multipart("/api/price-lists").file(new MockMultipartFile("file", "ZZ прайс.xlsx",
                                "application/octet-stream", PriceListFiles.axionLikeXlsx()))
                        .param("distributorId", String.valueOf(axion.getId())).header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
        mvc.perform(get("/api/price-lists").header("X-Market", "KZ")).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/price-lists/" + id + "/file").header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/price-lists/" + id).header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(post("/api/price-lists/" + id + "/activate").header("X-Market", "KZ")).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(username = "op", roles = "OPERATOR")
    void operator_readsButCannotUpload() throws Exception {
        MarketContext.set(Market.KZ);
        Long id = service.upload(axion.getId(), "ZZ прайс.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        MarketContext.clear();
        mvc.perform(get("/api/price-lists/" + id).header("X-Market", "KZ")).andExpect(status().isOk());
        mvc.perform(multipart("/api/price-lists").file(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", new byte[]{1}))
                .param("distributorId", String.valueOf(axion.getId())).header("X-Market", "KZ")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/price-lists/" + id).header("X-Market", "KZ").contentType("application/json")
                .content("{\"field\":\"TERMS\",\"value\":\"x\"}")).andExpect(status().isForbidden());
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.PriceList*Test' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `PriceListService`, `PriceListLinker`, `PriceListViewBuilder`, `PriceListPatch`.

- [ ] **Step 2: Номер РУ из текста и порядок прайсов**

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.MedRegistry;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** РУ из текста прайса (спека §5.3): «РЕГИСТРАЦИОННОЕ УДОСТОВЕРЕНИЕ РК-МТ-5No017589 (бессрочно)» → «РК-МТ-5№017589»; «Нет РУ…». */
public final class PriceListRegs {

    private static final Pattern NUMBER = Pattern.compile("(?iu)РК[\\s-]*(?:МИ\\s*\\(МТ\\)|МТ)[\\s-]*(\\d?)\\s*(?:№|No\\.?|N)\\s*(\\d{4,7})");
    private static final Pattern NONE = Pattern.compile("(?iu)нет\\s+(?:ру|рег|сертифик)|не\\s+является\\s+медицинск|сертификаты\\s+не\\s+имеются|нет\\s+сертификатов");

    private PriceListRegs() {}

    /** Номер в записи реестра: «РК МИ (МТ)-0№024573», «РК-МТ-5№021045»; не нашёлся — null. */
    public static String number(String text) {
        if (text == null) return null;
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return null;
        boolean mi = m.group().toUpperCase().contains("МИ");
        return (mi ? "РК МИ (МТ)-" : "РК-МТ-") + m.group(1) + "№" + m.group(2);
    }

    /** «Нет РУ в Казахстане», «не является медицинским оборудованием», «Сертификаты не имеются». */
    public static boolean none(String text) {
        return text != null && NONE.matcher(text).find();
    }

    /**
     * Статус РУ строки для экрана и подсказок: NONE — «нет РУ» по тексту; номера нет — null; по реестру — ACTIVE (бессрочно,
     * без срока или срок не раньше today) / EXPIRED / NOT_FOUND.
     */
    public static String status(boolean noReg, String number, MedRegistry rec, LocalDate today) {
        if (noReg) return "NONE";
        if (number == null) return null;
        if (rec == null) return "NOT_FOUND";
        return Boolean.TRUE.equals(rec.getUnlimited()) || rec.getExpirationDate() == null || !rec.getExpirationDate().isBefore(today)
                ? "ACTIVE" : "EXPIRED";
    }
}
```

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.PriceList;

import java.time.LocalDate;
import java.util.Comparator;

/** Какой прайс новее (спека §5.5): дата прайса, а нет её — дата загрузки; при равенстве — больший id. */
public final class PriceListOrder {

    private PriceListOrder() {}

    public static LocalDate date(PriceList p) {
        return p.getListDate() != null ? p.getListDate() : p.getCreatedAt() == null ? LocalDate.MIN : p.getCreatedAt().toLocalDate();
    }

    /** Новые первыми. */
    public static final Comparator<PriceList> NEWER = Comparator.comparing(PriceListOrder::date)
            .thenComparing(PriceList::getId, Comparator.nullsFirst(Comparator.naturalOrder())).reversed();
}
```

Ключи товара строки (спека §5.4) — нужны «было …» (здесь), подсказкам и истории (задача 8). Строки — один товар, если у
них есть общий ключ: связь с нашим товаром ставится не всегда (у старого прайса её могло не быть), а код модели у строк
одного товара общий.

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.PriceListItem;

import java.util.ArrayList;
import java.util.List;

/** Ключи товара строки прайса: «p<наш товар>», «e<позиция каталога>», «k<код модели>» — в этом порядке. */
public final class PriceListKeys {

    private PriceListKeys() {}

    public static List<String> of(PriceListItem r) {
        return of(r.getSiteProductId(), r.getMedEquipmentId(), r.getModelKey());
    }

    public static List<String> of(Long siteProductId, Long equipmentId, String modelKey) {
        List<String> out = new ArrayList<>(3);
        if (siteProductId != null) out.add("p" + siteProductId);
        if (equipmentId != null) out.add("e" + equipmentId);
        if (modelKey != null && !modelKey.isBlank()) out.add("k" + modelKey);
        return out;
    }

    /** Есть ли общий ключ. */
    public static boolean same(List<String> a, List<String> b) {
        for (String k : a) if (b.contains(k)) return true;
        return false;
    }
}
```

- [ ] **Step 3: Связь строки с товаром**

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.PriceListItem;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Модель, ключ и связь строки прайса (спека §5.4): код модели — из наименования и артикула; связь — только если код ровно у
 * одного нашего товара (KZ, не снят), иначе ровно у одной позиции каталога рынка; кандидатов несколько — без связи.
 * Связь, поставленная человеком, не перезаписывается.
 */
@Component
public class PriceListLinker {

    private final SiteProductRepository products;
    private final MedEquipmentRepository equipment;

    public PriceListLinker(SiteProductRepository products, MedEquipmentRepository equipment) {
        this.products = products;
        this.equipment = equipment;
    }

    /** Карты «ключ → id» нашего товара и позиции каталога для серии строк (одна загрузка на прайс). */
    public record Index(Map<String, Set<Long>> products, Map<String, Set<Long>> equipment) {}

    public Index index(Market market) {
        Map<String, Set<Long>> p = new HashMap<>(), e = new HashMap<>();
        if (market == Market.KZ) {
            for (SiteProduct s : products.findByMarketAndRemovedAtIsNull(Market.KZ)) {
                for (String code : PriceListCodes.codes(s.getName())) p.computeIfAbsent(PriceListCodes.key(code), k -> new HashSet<>()).add(s.getId());
            }
        }
        for (MedEquipment m : equipment.findAll()) {               // фильтр рынка — аспектом (сессия привязана)
            if (m.getMarket() != market) continue;
            for (String code : PriceListCodes.codes(m.getName())) e.computeIfAbsent(PriceListCodes.key(code), k -> new HashSet<>()).add(m.getId());
        }
        return new Index(p, e);
    }

    public void link(PriceListItem row, Market market) {
        link(row, index(market));
    }

    public void link(PriceListItem row, Index ix) {
        List<String> codes = new ArrayList<>(PriceListCodes.codes(row.getName()));
        if (row.getArticle() != null) codes.addAll(PriceListCodes.codes(row.getArticle()));
        if (row.getModel() == null && !codes.isEmpty()) row.setModel(PriceTextLines.cut(codes.get(0), 100));
        String key = row.getModel() != null ? PriceListCodes.key(row.getModel()) : null;
        row.setModelKey(key == null ? null : PriceTextLines.cut(key, 100));
        if (key == null || row.getSiteProductId() != null || row.getMedEquipmentId() != null) return;
        Set<Long> p = ix.products().getOrDefault(key, Set.of());
        if (p.size() == 1) {
            row.setSiteProductId(p.iterator().next());
            return;
        }
        if (!p.isEmpty()) return;
        Set<Long> e = ix.equipment().getOrDefault(key, Set.of());
        if (e.size() == 1) row.setMedEquipmentId(e.iterator().next());
    }
}
```

(`PriceTextLines.cut` — обрезка из задачи 5, тот же пакет `service/pricelist`.)

- [ ] **Step 4: Сервис и запись**

`dto/request/PriceListPatch.java`:

```java
package com.vladoose.nir.dto.request;

/** Правка прайса или строки по полю (спека §5.3, как у частной заявки): field — имя поля, value — текст (пусто — снять). */
public record PriceListPatch(String field, String value) {}
```

`PriceListWriter`:

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.PriceListRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;

/** Черновик прайса из разобранного файла — отдельный бин: разбор — вне транзакции (CLAUDE.md §6). */
@Component
public class PriceListWriter {

    private final PriceListRepository lists;
    private final PriceListLinker linker;

    public PriceListWriter(PriceListRepository lists, PriceListLinker linker) {
        this.lists = lists;
        this.linker = linker;
    }

    @Transactional
    public Long create(Distributor d, String fileName, String mime, byte[] bytes, String sha, ReplyFileKind kind,
                       ParsedPriceList parsed, Long inboundEmailId, String user) {
        PriceList p = PriceList.builder().market(d.getMarket()).distributor(d).status(PriceListStatus.DRAFT)
                .listDate(parsed.listDate()).validTo(parsed.validTo())
                .currency(parsed.currency() == null ? d.getMarket().currencyCode() : parsed.currency()).retail(parsed.retail())
                .columns(new ArrayList<>(parsed.columns())).ourColumn(parsed.ourColumn()).discountPct(BigDecimal.ZERO)
                .terms(parsed.terms()).fileName(fileName).mimeType(mime).sizeBytes((long) bytes.length).sha256(sha).data(bytes)
                .kind(kind).parseStatus(parsed.status())
                .parseNote(parsed.truncated() ? "Показаны первые 2 000 строк" : PriceTextLines.cut(parsed.note(), 300))
                .inboundEmailId(inboundEmailId).createdBy(user).build();
        PriceListLinker.Index ix = linker.index(d.getMarket());
        int n = 0;
        for (ParsedPriceRow r : parsed.rows()) {
            PriceListItem it = PriceListItem.builder().priceList(p).lineNo(++n).section(r.section()).name(r.name())
                    .article(r.article()).prices(new ArrayList<>(r.prices())).regText(r.regText())
                    .regNumber(PriceListRegs.number(r.regText())).noReg(PriceListRegs.none(r.regText())).fileRow(r.fileRow())
                    .build();
            it.setOurPrice(PriceListService.ourPrice(it.getPrices(), p.getOurColumn(), p.getDiscountPct()));
            linker.link(it, ix);
            p.getItems().add(it);
        }
        return lists.saveAndFlush(p).getId();
    }
}
```

`PriceListService`:

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PriceListPatch;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.supplierreply.MoneyText;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Прайсы поставщиков (спека 2026-10-23 §5): загрузка — разбор вне транзакции и черновик; правка шапки и строк по полю;
 * «Сохранить прайс»; удаление. Гард — прайс своего рынка (findById фильтр рынка не режет, §14), поставщик своего рынка.
 */
@Service
public class PriceListService {

    static final Set<ReplyFileKind> KINDS = EnumSet.of(ReplyFileKind.EXCEL, ReplyFileKind.WORD, ReplyFileKind.PDF, ReplyFileKind.IMAGE);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_DISCOUNT = new BigDecimal("99.99");

    private final PriceListRepository lists;
    private final DistributorRepository distributors;
    private final SiteProductRepository products;
    private final MedEquipmentRepository equipment;
    private final PriceListReader reader;
    private final PriceListWriter writer;
    private final PriceListLinker linker;

    public PriceListService(PriceListRepository lists, DistributorRepository distributors, SiteProductRepository products,
                            MedEquipmentRepository equipment, PriceListReader reader, PriceListWriter writer, PriceListLinker linker) {
        this.lists = lists;
        this.distributors = distributors;
        this.products = products;
        this.equipment = equipment;
        this.reader = reader;
        this.writer = writer;
        this.linker = linker;
    }

    /** Закупка строки: цена нашей колонки × (100 − скидка) / 100 одной дробью до 0,01; цены нет — null. */
    public static BigDecimal ourPrice(List<BigDecimal> prices, Integer column, BigDecimal discountPct) {
        if (column == null || prices == null || column < 0 || column >= prices.size() || prices.get(column) == null) return null;
        BigDecimal d = discountPct == null ? BigDecimal.ZERO : discountPct;
        return prices.get(column).multiply(HUNDRED.subtract(d)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public PriceList guard(Long id) {
        PriceList p = lists.findById(id).orElseThrow(() -> new NotFoundException("Прайс не найден: " + id));
        if (p.getMarket() != MarketContext.get()) throw new NotFoundException("Прайс не найден: " + id);
        return p;
    }

    private Distributor supplier(Long id) {
        if (id == null) throw new BadRequestException("Выберите поставщика");
        Distributor d = distributors.findById(id).orElse(null);
        if (d == null || d.getMarket() != MarketContext.get()) throw new BadRequestException("Поставщик не найден");
        return d;
    }

    /** Загрузка (не транзакция: разбор PDFBox / POI — вне неё): тот же файл поставщика — прежний прайс (спека §5.1). */
    public Long upload(Long distributorId, String fileName, String mime, byte[] bytes, String user) {
        return create(supplier(distributorId), fileName, mime, bytes, null, user);
    }

    Long create(Distributor d, String fileName, String mime, byte[] bytes, Long inboundEmailId, String user) {
        if (bytes == null || bytes.length == 0) throw new BadRequestException("Файл пустой");
        if (bytes.length > PriceListReader.PARSE_MAX) throw new BadRequestException("Файл больше 15 МБ — уменьшите его и загрузите снова");
        String name = SupplierReplyFiles.fileName(fileName);
        ReplyFileKind kind = SupplierReplyFiles.kind(name, mime, bytes);
        if (!KINDS.contains(kind)) throw new BadRequestException("Прайс — файл Excel, Word, PDF или картинка");
        String sha = SupplierReplyFiles.sha256(bytes);
        Optional<PriceList> same = lists.findByDistributorIdAndSha256(d.getId(), sha);
        if (same.isPresent()) return same.get().getId();
        ParsedPriceList parsed = reader.read(name, kind, bytes);
        return writer.create(d, name, mime, bytes, sha, kind, parsed, inboundEmailId, user);
    }

    @Transactional
    public void patch(Long id, PriceListPatch in, String user) {
        PriceList p = guard(id);
        String v = in == null || in.value() == null ? null : in.value().strip();
        if (v != null && v.isEmpty()) v = null;
        switch (in == null || in.field() == null ? "" : in.field()) {
            case "DISTRIBUTOR" -> p.setDistributor(supplier(v == null ? null : parseLong(v)));
            case "LIST_DATE" -> {
                LocalDate d = date(v);
                if (d != null && p.getValidTo() != null && p.getValidTo().isBefore(d)) throw new BadRequestException("«Действует до» раньше даты прайса");
                p.setListDate(d);
            }
            case "VALID_TO" -> {
                LocalDate d = date(v);
                if (d != null && p.getListDate() != null && d.isBefore(p.getListDate())) throw new BadRequestException("«Действует до» раньше даты прайса");
                p.setValidTo(d);
            }
            case "CURRENCY" -> {
                String c = v == null ? null : v.toUpperCase(Locale.ROOT);
                if (c == null || !MoneyText.CHOOSABLE.contains(c)) throw new BadRequestException("Валюта — KZT, RUB, USD, EUR или CNY");
                p.setCurrency(c);
            }
            case "OUR_COLUMN" -> {
                int c = v == null ? -1 : (int) parseLong(v);
                if (c < 0 || c >= p.getColumns().size()) throw new BadRequestException("Нет такой колонки цены");
                p.setOurColumn(c);
                recompute(p);
            }
            case "DISCOUNT_PCT" -> {
                BigDecimal d = v == null ? BigDecimal.ZERO : MoneyText.amount(v);
                if (d == null || d.signum() < 0 || d.compareTo(MAX_DISCOUNT) > 0 || d.stripTrailingZeros().scale() > 2) {
                    throw new BadRequestException("Скидка — от 0 до 99,99 %");
                }
                p.setDiscountPct(d.setScale(2, RoundingMode.HALF_UP));
                recompute(p);
            }
            case "TERMS" -> {
                if (v != null && v.length() > 1000) throw new BadRequestException("Условия — не длиннее 1000 символов");
                p.setTerms(v);
            }
            default -> throw new BadRequestException("Неизвестное поле: " + (in == null ? null : in.field()));
        }
        p.setUpdatedAt(OffsetDateTime.now());
    }

    @Transactional
    public void patchRow(Long id, Long rowId, PriceListPatch in) {
        PriceList p = guard(id);
        PriceListItem r = row(p, rowId);
        String v = in == null || in.value() == null ? null : in.value().strip();
        if (v != null && v.isEmpty()) v = null;
        String field = in == null || in.field() == null ? "" : in.field();
        if (field.startsWith("PRICE:")) {
            int c = (int) parseLong(field.substring(6));
            if (c < 0 || c >= p.getColumns().size()) throw new BadRequestException("Нет такой колонки цены");
            BigDecimal price = v == null ? null : MoneyText.amount(v);
            if (v != null && (price == null || price.signum() < 0)) throw new BadRequestException("Цена — число не меньше нуля");
            List<BigDecimal> prices = new ArrayList<>(r.getPrices());
            while (prices.size() < p.getColumns().size()) prices.add(null);
            prices.set(c, price == null ? null : price.setScale(2, RoundingMode.HALF_UP));
            r.setPrices(prices);
            r.setOurPrice(ourPrice(prices, p.getOurColumn(), p.getDiscountPct()));
        } else {
            switch (field) {
                case "SECTION" -> r.setSection(v == null ? null : PriceTextLines.cut(v, 255));
                case "NAME" -> {
                    if (v == null) throw new BadRequestException("Наименование не может быть пустым");
                    r.setName(PriceTextLines.cut(v, 1000));
                    linker.link(r, p.getMarket());
                }
                case "MODEL" -> {
                    r.setModel(v == null ? null : PriceTextLines.cut(v, 100));
                    linker.link(r, p.getMarket());
                }
                case "ARTICLE" -> r.setArticle(v == null ? null : PriceTextLines.cut(v, 100));
                case "REG_TEXT" -> {
                    r.setRegText(v == null ? null : PriceTextLines.cut(v, 500));
                    r.setRegNumber(PriceListRegs.number(v));
                    r.setNoReg(PriceListRegs.none(v));
                }
                case "SITE_PRODUCT" -> {
                    Long pid = v == null ? null : parseLong(v);
                    if (pid != null) {
                        SiteProduct s = products.findById(pid).orElse(null);
                        if (s == null || s.getMarket() != p.getMarket() || s.getRemovedAt() != null) throw new BadRequestException("Товар не найден");
                    }
                    r.setSiteProductId(pid);
                    if (pid != null) r.setMedEquipmentId(null);
                }
                case "EQUIPMENT" -> {
                    Long eid = v == null ? null : parseLong(v);
                    if (eid != null) {
                        MedEquipment e = equipment.findById(eid).orElse(null);
                        if (e == null || e.getMarket() != p.getMarket()) throw new BadRequestException("Позиция каталога не найдена");
                    }
                    r.setMedEquipmentId(eid);
                    if (eid != null) r.setSiteProductId(null);
                }
                default -> throw new BadRequestException("Неизвестное поле: " + field);
            }
        }
        p.setUpdatedAt(OffsetDateTime.now());
    }

    @Transactional
    public Long addRow(Long id) {
        PriceList p = guard(id);
        int n = p.getItems().stream().mapToInt(PriceListItem::getLineNo).max().orElse(0);
        PriceListItem it = PriceListItem.builder().priceList(p).lineNo(n + 1).name("Новая строка")
                .prices(new ArrayList<>(Collections.nCopies(Math.max(p.getColumns().size(), 1), (BigDecimal) null))).build();
        if (p.getColumns().isEmpty()) {
            p.getColumns().add(new PriceListColumn(0, "Цена"));
            p.setOurColumn(0);
        }
        p.getItems().add(it);
        lists.saveAndFlush(p);
        return it.getId();
    }

    @Transactional
    public void deleteRow(Long id, Long rowId) {
        PriceList p = guard(id);
        PriceListItem r = row(p, rowId);
        p.getItems().remove(r);
        p.setUpdatedAt(OffsetDateTime.now());
    }

    @Transactional
    public void activate(Long id, String user) {
        PriceList p = guard(id);
        p.setStatus(PriceListStatus.ACTIVE);
        p.setActivatedBy(user);
        p.setActivatedAt(OffsetDateTime.now());
    }

    @Transactional
    public void delete(Long id) {
        lists.delete(guard(id));
    }

    private void recompute(PriceList p) {
        for (PriceListItem r : p.getItems()) r.setOurPrice(ourPrice(r.getPrices(), p.getOurColumn(), p.getDiscountPct()));
    }

    private static PriceListItem row(PriceList p, Long rowId) {
        return p.getItems().stream().filter(r -> r.getId().equals(rowId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Строка прайса не найдена: " + rowId));
    }

    private static LocalDate date(String v) {
        if (v == null) return null;
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Дата — ГГГГ-ММ-ДД");
        }
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Ожидалось число: " + v);
        }
    }
}
```

- [ ] **Step 5: Вид экрана проверки и список**

`dto/response/PriceListView.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.PriceListColumn;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Экран проверки прайса (спека §5.3): шапка, колонки цен, курсы (наш и сегодня — для «→ тг»), строки с закупкой, РУ
 * (ACTIVE / EXPIRED / NOT_FOUND — по реестру, NONE — «нет РУ» по тексту, null — номера нет), связью и «было …».
 */
public record PriceListView(Long id, String status, Long distributorId, String distributorName, String fileName, String kind,
                            String parseStatus, String parseNote, LocalDate listDate, LocalDate validTo, boolean expired,
                            String currency, boolean retail, List<PriceListColumn> columns, Integer ourColumn, BigDecimal discountPct,
                            String terms, String marketCurrency, BigDecimal ourRate, BigDecimal todayRate, LocalDate todayDate,
                            String createdBy, OffsetDateTime createdAt, String activatedBy, OffsetDateTime activatedAt, List<Row> rows) {

    public record Row(Long id, int lineNo, String section, String name, String model, String article, List<BigDecimal> prices,
                      BigDecimal ourPrice, BigDecimal converted, BigDecimal today, String regText, String regNumber,
                      String regStatus, LocalDate regExpires, boolean noReg, Long siteProductId, String siteProductName,
                      String siteProductUrl, Long medEquipmentId, String medEquipmentName, BigDecimal prevPrice,
                      LocalDate prevDate, BigDecimal prevPct, Integer fileRow) {}
}
```

`dto/response/PriceListListItem.java`:

```java
package com.vladoose.nir.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Строка списка «Прайсы поставщиков» (без байтов файла). */
public record PriceListListItem(Long id, Long distributorId, String distributorName, String status, LocalDate listDate,
                                LocalDate validTo, boolean expired, String currency, String fileName, String kind,
                                String parseStatus, String createdBy, OffsetDateTime createdAt, long itemCount) {}
```

`PriceListItemRepository` — строки других действующих прайсов поставщика (для «было …»):

```java
    /** Строки действующих прайсов поставщика, кроме этого прайса (фильтр рынка — через прайс, аспектом). */
    @Query("SELECT i FROM PriceListItem i JOIN FETCH i.priceList p WHERE p.distributor.id = :distributorId "
            + "AND p.id <> :exceptId AND p.status = com.vladoose.nir.entity.PriceListStatus.ACTIVE")
    List<PriceListItem> findOtherActiveOfDistributor(@Param("distributorId") Long distributorId, @Param("exceptId") Long exceptId);
```

`PriceListViewBuilder`:

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.dto.response.PriceListListItem;
import com.vladoose.nir.dto.response.PriceListView;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.service.rates.CurrencyRates;
import com.vladoose.nir.service.rates.RateMath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class PriceListViewBuilder {

    private final PriceListService service;
    private final PriceListRepository lists;
    private final PriceListItemRepository items;
    private final SiteProductRepository products;
    private final MedEquipmentRepository equipment;
    private final MedRegistryRepository registry;
    private final CurrencyRates rates;
    private final String siteUrl;

    public PriceListViewBuilder(PriceListService service, PriceListRepository lists, PriceListItemRepository items,
                                SiteProductRepository products, MedEquipmentRepository equipment, MedRegistryRepository registry,
                                CurrencyRates rates, @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.service = service;
        this.lists = lists;
        this.items = items;
        this.products = products;
        this.equipment = equipment;
        this.registry = registry;
        this.rates = rates;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public PriceListView view(Long id) {
        PriceList p = service.guard(id);
        Market m = p.getMarket();
        LocalDate today = LocalDate.now(m.zone());
        String cur = p.getCurrency();
        boolean foreign = cur != null && !cur.equals(m.currencyCode()) && !"MIXED".equals(cur);
        BigDecimal our = foreign ? rates.our(m, cur).orElse(null) : null;
        CurrencyRates.Today td = foreign ? rates.today(m, cur).orElse(null) : null;
        Map<String, PriceListItem> previous = previous(p);
        Map<Long, SiteProduct> sp = new HashMap<>();
        products.findAllById(p.getItems().stream().map(PriceListItem::getSiteProductId).filter(Objects::nonNull).toList())
                .forEach(s -> sp.put(s.getId(), s));
        Map<Long, MedEquipment> eq = new HashMap<>();
        equipment.findAllById(p.getItems().stream().map(PriceListItem::getMedEquipmentId).filter(Objects::nonNull).toList())
                .forEach(e -> eq.put(e.getId(), e));
        List<PriceListView.Row> rows = new ArrayList<>();
        for (PriceListItem r : p.getItems()) {
            MedRegistry rec = r.getRegNumber() == null ? null : registry.findByNormalizedRegNumber(RegNumbers.normalize(r.getRegNumber())).orElse(null);
            String regStatus = PriceListRegs.status(r.isNoReg(), r.getRegNumber(), rec, today);
            PriceListItem prev = PriceListKeys.of(r).stream().map(previous::get).filter(Objects::nonNull).findFirst().orElse(null);
            BigDecimal prevPrice = prev == null ? null : prev.getOurPrice();
            BigDecimal pct = prevPrice == null || prevPrice.signum() == 0 || r.getOurPrice() == null ? null
                    : r.getOurPrice().subtract(prevPrice).multiply(BigDecimal.valueOf(100)).divide(prevPrice, 1, RoundingMode.HALF_UP);
            SiteProduct s = r.getSiteProductId() == null ? null : sp.get(r.getSiteProductId());
            MedEquipment e = r.getMedEquipmentId() == null ? null : eq.get(r.getMedEquipmentId());
            rows.add(new PriceListView.Row(r.getId(), r.getLineNo(), r.getSection(), r.getName(), r.getModel(), r.getArticle(),
                    r.getPrices(), r.getOurPrice(), foreign ? RateMath.convert(r.getOurPrice(), our) : null,
                    td == null ? null : RateMath.convert(r.getOurPrice(), td.rate()), r.getRegText(), r.getRegNumber(), regStatus,
                    rec == null ? null : rec.getExpirationDate(), r.isNoReg(), r.getSiteProductId(), s == null ? null : s.getName(),
                    s == null || s.getSlug() == null ? null : WestmedLeadMapper.productUrl(siteUrl, s.getSlug()), r.getMedEquipmentId(),
                    e == null ? null : e.getName(), prevPrice, prev == null ? null : PriceListOrder.date(prev.getPriceList()), pct, r.getFileRow()));
        }
        return new PriceListView(p.getId(), p.getStatus().name(), p.getDistributor().getId(), p.getDistributor().getName(),
                p.getFileName(), p.getKind().name(), p.getParseStatus().name(), p.getParseNote(), p.getListDate(), p.getValidTo(),
                p.getValidTo() != null && p.getValidTo().isBefore(today), cur, p.isRetail(), p.getColumns(), p.getOurColumn(),
                p.getDiscountPct(), p.getTerms(), m.currencyCode(), our, td == null ? null : td.rate(), td == null ? null : td.date(),
                p.getCreatedBy(), p.getCreatedAt(), p.getActivatedBy(), p.getActivatedAt(), rows);
    }

    /** «Было …»: по каждому ключу — строка самого нового из более старых действующих прайсов того же поставщика. */
    private Map<String, PriceListItem> previous(PriceList p) {
        Map<String, PriceListItem> out = new HashMap<>();
        List<PriceListItem> others = new ArrayList<>(items.findOtherActiveOfDistributor(p.getDistributor().getId(), p.getId()));
        others.sort(Comparator.comparing(PriceListItem::getPriceList, PriceListOrder.NEWER));
        for (PriceListItem o : others) {
            if (o.getOurPrice() == null || PriceListOrder.NEWER.compare(o.getPriceList(), p) < 0) continue;   // новее — не «было»
            for (String k : PriceListKeys.of(o)) out.putIfAbsent(k, o);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<PriceListListItem> list(Long supplier) {
        LocalDate today = LocalDate.now(com.vladoose.nir.context.MarketContext.get().zone());
        List<PriceListListItem> out = new ArrayList<>();
        for (Object[] r : lists.findRows()) {
            Long distributorId = (Long) r[1];
            if (supplier != null && !supplier.equals(distributorId)) continue;
            LocalDate validTo = (LocalDate) r[5];
            out.add(new PriceListListItem((Long) r[0], distributorId, (String) r[2], ((PriceListStatus) r[3]).name(), (LocalDate) r[4],
                    validTo, validTo != null && validTo.isBefore(today), (String) r[6], (String) r[7], ((ReplyFileKind) r[8]).name(),
                    ((ReplyParseStatus) r[9]).name(), (String) r[10], (OffsetDateTime) r[11], ((Number) r[12]).longValue()));
        }
        return out;
    }
}
```

- [ ] **Step 6: Путь API**

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.PriceListPatch;
import com.vladoose.nir.dto.response.PriceListListItem;
import com.vladoose.nir.dto.response.PriceListView;
import com.vladoose.nir.entity.PriceList;
import com.vladoose.nir.entity.ReplyFileKind;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.pricelist.PriceListService;
import com.vladoose.nir.service.pricelist.PriceListViewBuilder;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** «Прайсы поставщиков» (спека §5, §9): читать — все, загружать и править — администратор. */
@RestController
@RequestMapping("/api/price-lists")
public class PriceListController {

    private final PriceListService service;
    private final PriceListViewBuilder views;

    public PriceListController(PriceListService service, PriceListViewBuilder views) {
        this.service = service;
        this.views = views;
    }

    @GetMapping
    public List<PriceListListItem> list(@RequestParam(required = false) Long supplier) {
        return views.list(supplier);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView upload(@RequestParam("file") MultipartFile file, @RequestParam("distributorId") Long distributorId) throws IOException {
        if (file == null || file.isEmpty()) throw new BadRequestException("Файл пустой");
        return views.view(service.upload(distributorId, file.getOriginalFilename(), file.getContentType(), file.getBytes(), CurrentUser.name()));
    }

    @GetMapping("/{id}")
    public PriceListView view(@PathVariable Long id) {
        return views.view(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView patch(@PathVariable Long id, @RequestBody PriceListPatch in) {
        service.patch(id, in, CurrentUser.name());
        return views.view(id);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView activate(@PathVariable Long id) {
        service.activate(id, CurrentUser.name());
        return views.view(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/rows")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView addRow(@PathVariable Long id) {
        service.addRow(id);
        return views.view(id);
    }

    @PatchMapping("/{id}/rows/{rowId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView patchRow(@PathVariable Long id, @PathVariable Long rowId, @RequestBody PriceListPatch in) {
        service.patchRow(id, rowId, in);
        return views.view(id);
    }

    @DeleteMapping("/{id}/rows/{rowId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView deleteRow(@PathVariable Long id, @PathVariable Long rowId) {
        service.deleteRow(id, rowId);
        return views.view(id);
    }

    /** Файл прайса: attachment с именем RFC 5987, nosniff, без кеша; тип — только PDF и картинки (как у готового КП). */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        PriceList p = service.guard(id);
        ReplyFileKind kind = SupplierReplyFiles.kind(p.getFileName(), p.getMimeType(), p.getData());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReadyOfferController.contentType(kind, p.getFileName())))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(p.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(p.getData());
    }
}
```

Если `ReadyOfferController.contentType` — package-private, сделать `public static`.

- [ ] **Step 7: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.*' 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 8: Мутации**

1. `guard` без сверки рынка → `upload_view_file_otherMarket404` краснеет.
2. Без поиска того же файла (`findByDistributorIdAndSha256`) → `sameFile_returnsExisting_otherMarketSupplier_400` краснеет (уникальность → 500 или новый id).
3. `ourPrice` без скидки → `patch_columnDiscountCurrencyDates_rows` краснеет.
4. Связь при двух товарах ставится первому → `uniqueSiteProduct_linked_ambiguous_not_catalogFallback` краснеет.
5. «Было» берёт и прайсы новее этого (условие `NEWER.compare(…) < 0` убрано) → `conversions_reg_previousPrice` краснеет
   (160 000 вместо 140 000).
6. `@PreAuthorize` снять с `upload` → `operator_readsButCannotUpload` краснеет.
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 9: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java && git commit -m "feat(price-lists): загрузка прайса, черновик, правка по полю, «Сохранить прайс», связь с товарами, РУ, «было …», путь /api/price-lists

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 8: Подсказки «у кого и почём», поиск по прайсам, история цен, кандидаты связи (сервер)

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/pricelist/PriceHints.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/PriceHintRow.java`, `PriceHint.java`, `PriceHistoryEntry.java`,
  `PriceLinkCandidates.java`, `src/main/java/com/vladoose/nir/dto/request/PriceHintsRequest.java`
- Modify: `src/main/java/com/vladoose/nir/repository/PriceListItemRepository.java` (`findActiveByKeys`, `findActiveByWord`,
  `findLabels`), `src/main/java/com/vladoose/nir/repository/PriceRequestItemRepository.java` (`findPricedForHistory`),
  `src/main/java/com/vladoose/nir/service/lotmodel/LotModelSearch.java` (`ours(String)` — public),
  `src/main/java/com/vladoose/nir/controller/PriceListController.java` (4 пути)
- Test: `src/test/java/com/vladoose/nir/pricelist/PriceHintsTest.java`, `PriceHintsApiTest.java`

**Interfaces:**
- Consumes: `PriceListKeys`, `PriceListOrder`, `PriceListRegs.status` (задача 7), `PriceListCodes.keys` (задача 5),
  `CurrencyRates.ours / today` и `RateMath.convert` (задача 2), `LotModelSearch.ours`, `MedEquipmentService.search`,
  `BrandMatch.firstCarried`.
- Produces: `record PriceHintRow(Long rowId, Long priceListId, Long distributorId, String supplierName, boolean manufacturer,
  String section, String name, String model, String article, LocalDate listDate, LocalDate validTo, boolean expired,
  String currency, BigDecimal price, BigDecimal ourRate, BigDecimal converted, BigDecimal today, LocalDate todayDate,
  String terms, String regText, String regNumber, String regStatus, LocalDate regExpires)` — `price` — закупка строки в
  валюте прайса, `converted` — в валюте рынка по нашему курсу (валюта рынка — та же цена; нет нашего курса — null),
  `today` — по курсу сегодня (только KZ и чужая валюта); `regNumber` — номер записи реестра, если нашлась (для «РУ ✓» в КП);
  `record PriceHint(String text, List<PriceHintRow> rows)`; `record PriceHistoryEntry(String kind /* LIST | REPLY */,
  LocalDate date, String supplierName, String currency, BigDecimal price, BigDecimal pct, Long priceListId, Long priceRequestId)`;
  `PriceHints.Target(String text, Long siteProductId, Long equipmentId, String preferBrand)` + `Target.text(String)`;
  `PriceHints.hints(Market, List<Target>) → List<PriceHint>` (один запрос строк на все цели), `hint(Market, Target) →
  PriceHint`, `search(Market, String q, int limit) → List<PriceHintRow>`, `history(Long rowId) → List<PriceHistoryEntry>`,
  `linkCandidates(String q) → PriceLinkCandidates`; `PriceListItemRepository.findLabels(Collection<Long>)` → [itemId,
  priceListId, supplierName, listDate, createdAt].

- [ ] **Step 1: Тесты — красные**

`PriceHintsTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PriceHint;
import com.vladoose.nir.dto.response.PriceHintRow;
import com.vladoose.nir.dto.response.PriceHistoryEntry;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.pricelist.PriceHints;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PriceHintsTest {

    @Autowired PriceHints hints;
    @Autowired PriceListRepository lists;
    @Autowired DistributorRepository distributors;
    @Autowired CurrencyRates rates;
    @Autowired CurrencyRateRepository ours;
    @Autowired OfficialRateRepository official;
    @Autowired SiteProductRepository products;
    @Autowired PrivateRequestService requests;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;

    Distributor a, b;

    @BeforeEach
    void setUp() {
        ours.deleteAllInBatch();
        official.deleteAllInBatch();
        MarketContext.set(Market.KZ);
        a = PriceListFixtures.supplier(distributors, Market.KZ, "Аксион подсказки");
        b = PriceListFixtures.supplier(distributors, Market.KZ, "Дилер подсказки");
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        official.saveAndFlush(OfficialRate.builder().source(CurrencyRates.NBRK).currency("RUB").rateDate(LocalDate.now(Market.KZ.zone()))
                .rate(new BigDecimal("5.48")).fetchedAt(OffsetDateTime.now()).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Review Focus 3: новый прайс поставщика — все его комплектации; старый — только в истории; у другого — свои строки. */
    @Test
    void latestListWins_variantsKept_historyHasOld() {
        PriceListFixtures.list(lists, a, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 3, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-99 базовый", "140000", "дкин99"});
        PriceList fresh = PriceListFixtures.list(lists, a, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 9, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-99 базовый", "150000", "дкин99"},
                new Object[]{"Дефибриллятор ДКИ-Н-99 без сумки", "140000", "дкин99"});
        PriceListFixtures.list(lists, b, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 8, 1),
                new Object[]{"ДКИ-Н-99", "160000", "дкин99"});

        PriceHint h = hints.hint(Market.KZ, PriceHints.Target.text("Дефибриллятор ДКИ-Н-99 «Аксион»"));
        assertThat(h.rows()).extracting(PriceHintRow::price).extracting(BigDecimal::intValue).containsExactly(140000, 150000, 160000);
        PriceHintRow best = h.rows().get(0);
        assertThat(best.priceListId()).isEqualTo(fresh.getId());
        assertThat(best.converted()).isEqualByComparingTo("840000");
        assertThat(best.today()).isEqualByComparingTo("767200");
        assertThat(best.listDate()).isEqualTo(LocalDate.of(2026, 9, 1));

        Long basic = fresh.getItems().get(0).getId();
        List<PriceHistoryEntry> hist = hints.history(basic);
        assertThat(hist).extracting(PriceHistoryEntry::date)
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 3, 1));
        assertThat(hist.get(0).price()).isEqualByComparingTo("150000");            // своя комплектация, не «без сумки»
        assertThat(hist.get(0).pct()).isEqualByComparingTo("7.1");                // к прайсу 01.03 того же поставщика
        assertThat(hist.get(1).pct()).isNull();                                   // у второго поставщика записи раньше нет
    }

    @Test
    void draftMixedAndNoOurRate() {
        PriceListFixtures.list(lists, a, PriceListStatus.DRAFT, "RUB", null, new Object[]{"ДКИ-Н-98", "1", "дкин98"});
        PriceListFixtures.list(lists, b, PriceListStatus.ACTIVE, "MIXED", null, new Object[]{"ДКИ-Н-98", "2", "дкин98"});
        Distributor c = PriceListFixtures.supplier(distributors, Market.KZ, "Доллар");
        PriceListFixtures.list(lists, c, PriceListStatus.ACTIVE, "USD", null, new Object[]{"ДКИ-Н-98", "300", "дкин98"});
        Distributor k = PriceListFixtures.supplier(distributors, Market.KZ, "Тенге");
        PriceListFixtures.list(lists, k, PriceListStatus.ACTIVE, "KZT", null, new Object[]{"ДКИ-Н-98", "990000", "дкин98"});
        List<PriceHintRow> rows = hints.hint(Market.KZ, PriceHints.Target.text("ДКИ-Н-98")).rows();
        assertThat(rows).extracting(PriceHintRow::supplierName).containsExactly(k.getName(), c.getName());   // без курса — в конце
        assertThat(rows.get(0).converted()).isEqualByComparingTo("990000");
        assertThat(rows.get(0).ourRate()).isNull();
        assertThat(rows.get(1).converted()).isNull();
        assertThat(hints.hint(Market.RF, PriceHints.Target.text("ДКИ-Н-98")).rows()).isEmpty();             // рынок
    }

    @Test
    void productTarget_byLinkOrCode_makerFirst() {
        SiteProduct p = products.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("ZZ Облучатель ОБН-97 «Аксион»").brand("ZZБренд").slug("zz-" + System.nanoTime()).syncedAt(OffsetDateTime.now()).build());
        PriceList dealer = PriceListFixtures.list(lists, b, PriceListStatus.ACTIVE, "RUB", null, new Object[]{"Облучатель настенный", "10000", null});
        dealer.getItems().get(0).setSiteProductId(p.getId());                      // только связь, кода нет
        lists.saveAndFlush(dealer);
        a.setManufacturer(true);
        a.getBrands().add("ZZБренд");
        distributors.saveAndFlush(a);
        PriceListFixtures.list(lists, a, PriceListStatus.ACTIVE, "RUB", null, new Object[]{"ОБН-97", "12000", "обн97"});   // только код
        PriceHint h = hints.hint(Market.KZ, new PriceHints.Target(null, p.getId(), null, "ZZБренд"));
        assertThat(h.rows()).extracting(PriceHintRow::supplierName).containsExactly(a.getName(), b.getName());   // завод бренда первым
        assertThat(hints.hint(Market.KZ, new PriceHints.Target(null, p.getId(), null, null)).rows().get(0).supplierName())
                .isEqualTo(b.getName());                                                                         // без бренда — дешевле
    }

    @Test
    void search_wordsSupplierAndSuperseded() {
        PriceListFixtures.list(lists, a, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 3, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-96 в сумке", "1", "дкин96"});
        PriceListFixtures.list(lists, a, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 9, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-96 в сумке", "2", "дкин96"});
        assertThat(hints.search(Market.KZ, "дки-н-96 сумк", 50)).extracting(PriceHintRow::price)
                .extracting(BigDecimal::intValue).containsExactly(2);                                 // старый прайс вытеснен
        assertThat(hints.search(Market.KZ, "подсказки ДКИ-Н-96", 50)).hasSize(1);                     // слово — в названии поставщика
        assertThat(hints.search(Market.KZ, "д", 50)).isEmpty();                                       // слово короче 2 символов
        assertThat(hints.search(Market.KZ, "ДКИ-Н-96 нет", 50)).isEmpty();                            // каждое слово запроса
    }

    @Test
    void history_includesSupplierReplies() {
        PriceList list = PriceListFixtures.list(lists, a, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 9, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-95", "150000", "дкин95"});
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Дефибриллятор ДКИ-Н-95", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().iterator().next();
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(b).status("RESPONDED")
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).responseDate(LocalDate.of(2026, 10, 15)).build());
        priceItems.saveAndFlush(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(new BigDecimal("900000")).responseCurrency("RUB").responseOriginal(new BigDecimal("150000"))
                .responseRate(new BigDecimal("6")).build());
        List<PriceHistoryEntry> hist = hints.history(list.getItems().get(0).getId());
        assertThat(hist).extracting(PriceHistoryEntry::kind).containsExactly("REPLY", "LIST");
        assertThat(hist.get(0).price()).isEqualByComparingTo("150000");            // исходная цена, в рублях
        assertThat(hist.get(0).currency()).isEqualTo("RUB");
        assertThat(hist.get(0).priceRequestId()).isEqualTo(pr.getId());
    }
}
```

`PriceHintsApiTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceList;
import com.vladoose.nir.entity.PriceListStatus;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceListRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
@WithMockUser(username = "op", roles = "OPERATOR")
class PriceHintsApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired DistributorRepository distributors;
    @Autowired PriceListRepository lists;
    MockMvc mvc;
    PriceList list;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "API подсказки");
        list = PriceListFixtures.list(lists, d, PriceListStatus.ACTIVE, "KZT", LocalDate.of(2026, 9, 1),
                new Object[]{"Отсасыватель ОМП-9/94", "60100", "омп994"});
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void operatorReads_searchHintsHistory_otherMarket404() throws Exception {
        mvc.perform(get("/api/price-lists/search").param("q", "омп-9/94").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].price").value(60100.0));
        mvc.perform(post("/api/price-lists/hints").header("X-Market", "KZ").contentType("application/json")
                        .content("{\"texts\":[\"Отсасыватель портативный ОМП-9/94\",\"без кода\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rows[0].supplierName").exists())
                .andExpect(jsonPath("$[1].rows.length()").value(0));
        Long rowId = list.getItems().get(0).getId();
        mvc.perform(get("/api/price-lists/rows/" + rowId + "/history").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].kind").value("LIST"));
        mvc.perform(get("/api/price-lists/rows/" + rowId + "/history").header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(post("/api/price-lists/hints").header("X-Market", "KZ").contentType("application/json")
                        .content("{\"texts\":[" + "\"x\",".repeat(200) + "\"x\"]}"))
                .andExpect(status().isBadRequest());
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.PriceHints*' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `PriceHints`, `PriceHint`, `PriceHintRow`.

- [ ] **Step 2: Запросы**

`PriceListItemRepository`:

```java
    /**
     * Строки действующих прайсов рынка по ключам товара (подсказки, история): связь с нашим товаром, с позицией каталога
     * или код модели. «Валюта не ясна» и строки без закупки — мимо. Пустые списки — заглушка (-1 / "").
     */
    @Query("SELECT i FROM PriceListItem i JOIN FETCH i.priceList p JOIN FETCH p.distributor d "
            + "WHERE p.market = :market AND p.status = com.vladoose.nir.entity.PriceListStatus.ACTIVE "
            + "AND (p.currency IS NULL OR p.currency <> 'MIXED') AND i.ourPrice IS NOT NULL "
            + "AND (i.siteProductId IN :products OR i.medEquipmentId IN :equipment OR i.modelKey IN :keys)")
    List<PriceListItem> findActiveByKeys(@Param("market") Market market, @Param("products") Collection<Long> products,
                                         @Param("equipment") Collection<Long> equipment, @Param("keys") Collection<String> keys);

    /** Поиск по действующим прайсам рынка: слово — в наименовании, модели, артикуле или названии поставщика (LIKE с \). */
    @Query("SELECT i FROM PriceListItem i JOIN FETCH i.priceList p JOIN FETCH p.distributor d "
            + "WHERE p.market = :market AND p.status = com.vladoose.nir.entity.PriceListStatus.ACTIVE "
            + "AND (p.currency IS NULL OR p.currency <> 'MIXED') "
            + "AND (LOWER(i.name) LIKE :pattern ESCAPE '\\' OR LOWER(i.model) LIKE :pattern ESCAPE '\\' "
            + "OR LOWER(i.article) LIKE :pattern ESCAPE '\\' OR LOWER(d.name) LIKE :pattern ESCAPE '\\') ORDER BY i.id DESC")
    List<PriceListItem> findActiveByWord(@Param("market") Market market, @Param("pattern") String pattern, Pageable page);

    /** Подпись строки прайса у строки КП: [itemId, priceListId, поставщик, дата прайса, загружен]. */
    @Query("SELECT i.id, p.id, d.name, p.listDate, p.createdAt FROM PriceListItem i JOIN i.priceList p JOIN p.distributor d "
            + "WHERE i.id IN :ids")
    List<Object[]> findLabels(@Param("ids") Collection<Long> ids);
```

`PriceRequestItemRepository`:

```java
    /**
     * Ответы с ценой для истории цен (спека 2026-10-23 §7), рынок явно: [id строки, id запроса, поставщик, исходная цена,
     * валюта (пусто — валюта рынка), дата ответа, наименование позиции, бренд, позиция каталога, наш товар].
     */
    @Query(value = """
            SELECT i.id, pr.id, d.name, COALESCE(i.response_original, i.response_price), i.response_currency,
                   COALESCE(pr.response_date, CAST(pr.sent_at AS date), CAST(pr.created_at AS date)),
                   l.equip_name, l.manufact, e.id, e.site_product_id
            FROM price_request_item i
            JOIN price_request pr ON pr.id = i.price_request_id
            JOIN distributor d ON d.id = pr.distributor_id
            JOIN tender_lot l ON l.id = i.tender_lot_id
            LEFT JOIN med_equipment e ON e.id = COALESCE(i.med_equipment_id, l.proposed_equipment_id)
            WHERE pr.market = :market AND i.response_price IS NOT NULL
            ORDER BY i.id DESC LIMIT 2000
            """, nativeQuery = true)
    List<Object[]> findPricedForHistory(@Param("market") String market);
```

`LotModelSearch.ours(String q)` — `private` → `public` (поиск наших товаров без лота; javadoc прежний).

- [ ] **Step 3: Ответы**

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Строка прайса в подсказке, поиске и «Поставщик ▾» (спека 2026-10-23 §6, решение плана 15). */
public record PriceHintRow(Long rowId, Long priceListId, Long distributorId, String supplierName, boolean manufacturer,
                           String section, String name, String model, String article, LocalDate listDate, LocalDate validTo,
                           boolean expired, String currency, BigDecimal price, BigDecimal ourRate, BigDecimal converted,
                           BigDecimal today, LocalDate todayDate, String terms, String regText, String regNumber,
                           String regStatus, LocalDate regExpires) {}
```

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/** Подсказка «📒 по прайсу» (спека §6.1): строки текущих прайсов поставщиков — лучшая первой; пусто — подсказки нет. */
public record PriceHint(String text, List<PriceHintRow> rows) {}
```

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Запись истории цен (спека §7): прайс или ответ на запрос цен; цена — в исходной валюте; pct — к прошлой записи. */
public record PriceHistoryEntry(String kind, LocalDate date, String supplierName, String currency, BigDecimal price,
                                BigDecimal pct, Long priceListId, Long priceRequestId) {}
```

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/** Кандидаты связи строки прайса (спека §5.3): наши товары (KZ) и позиции каталога рынка. */
public record PriceLinkCandidates(List<LotModelSearchResponse.Our> products, List<EquipmentSearchItemResponse> equipment) {}
```

```java
package com.vladoose.nir.dto.request;

import java.util.List;

/** Подсказки по текстам позиций (до 200). */
public record PriceHintsRequest(List<String> texts) {}
```

- [ ] **Step 4: Сервис подсказок**

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.MedEquipmentService;
import com.vladoose.nir.service.lotmodel.LotModelSearch;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.service.rates.CurrencyRates;
import com.vladoose.nir.service.rates.RateMath;
import com.vladoose.nir.util.BrandMatch;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Подсказки «у кого и почём» (спека 2026-10-23 §6–7): строки действующих прайсов по ключу товара — у каждого поставщика его
 * самый новый прайс с этим товаром (все комплектации); лучшая — самая дешёвая закупка в валюте рынка по нашему курсу.
 * Только из базы, без сети.
 */
@Service
public class PriceHints {

    public static final int ROWS_MAX = 10;
    public static final int TEXTS_MAX = 200;
    public static final int SEARCH_MAX = 50;
    static final int HISTORY_MAX = 10;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final List<Long> NO_IDS = List.of(-1L);
    private static final List<String> NO_KEYS = List.of("");

    /** Что ищем: текст позиции (коды моделей), наш товар, позиция каталога; preferBrand — сначала завод этого бренда. */
    public record Target(String text, Long siteProductId, Long equipmentId, String preferBrand) {
        public static Target text(String text) {
            return new Target(text, null, null, null);
        }
    }

    private record Rates(Map<String, BigDecimal> our, Map<String, CurrencyRates.Today> today, LocalDate date) {}

    private record Entry(String kind, LocalDate date, String supplier, String currency, BigDecimal price, Long priceListId,
                         Long priceRequestId) {}

    private final PriceListItemRepository items;
    private final PriceRequestItemRepository replies;
    private final SiteProductRepository products;
    private final MedEquipmentRepository equipment;
    private final MedRegistryRepository registry;
    private final CurrencyRates rates;
    private final LotModelSearch modelSearch;
    private final MedEquipmentService equipmentService;

    public PriceHints(PriceListItemRepository items, PriceRequestItemRepository replies, SiteProductRepository products,
                      MedEquipmentRepository equipment, MedRegistryRepository registry, CurrencyRates rates,
                      LotModelSearch modelSearch, MedEquipmentService equipmentService) {
        this.items = items;
        this.replies = replies;
        this.products = products;
        this.equipment = equipment;
        this.registry = registry;
        this.rates = rates;
        this.modelSearch = modelSearch;
        this.equipmentService = equipmentService;
    }

    @Transactional(readOnly = true)
    public PriceHint hint(Market m, Target t) {
        return hints(m, List.of(t)).get(0);
    }

    /** Подсказки для набора целей — одним запросом строк. */
    @Transactional(readOnly = true)
    public List<PriceHint> hints(Market m, List<Target> targets) {
        if (targets.isEmpty()) return List.of();
        Map<Long, SiteProduct> sp = new HashMap<>();
        products.findAllById(targets.stream().map(Target::siteProductId).filter(Objects::nonNull).toList()).forEach(p -> sp.put(p.getId(), p));
        Map<Long, MedEquipment> eq = new HashMap<>();
        equipment.findAllById(targets.stream().map(Target::equipmentId).filter(Objects::nonNull).toList()).forEach(e -> eq.put(e.getId(), e));
        List<List<String>> keys = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>();
        for (Target t : targets) {
            List<String> k = keys(t, sp, eq);
            keys.add(k);
            all.addAll(k);
        }
        List<PriceListItem> rows = all.isEmpty() ? List.of() : find(m, all);
        Rates r = rates(m);
        Map<String, MedRegistry> regs = regs(rows);
        List<PriceHint> out = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            List<String> k = keys.get(i);
            List<PriceListItem> mine = k.isEmpty() ? List.of()
                    : rows.stream().filter(x -> PriceListKeys.same(PriceListKeys.of(x), k)).toList();
            out.add(new PriceHint(targets.get(i).text(), current(mine, targets.get(i).preferBrand(), m, r, regs)));
        }
        return out;
    }

    /** Поиск по действующим прайсам (спека §6.2 п. 1): каждое слово от 2 символов; старые прайсы, вытесненные новыми, — мимо. */
    @Transactional(readOnly = true)
    public List<PriceHintRow> search(Market m, String q, int limit) {
        List<String> words = words(q);
        if (words.isEmpty()) return List.of();
        int max = Math.max(1, Math.min(limit, SEARCH_MAX));
        List<PriceListItem> hit = items.findActiveByWord(m, like(words.get(0)), PageRequest.of(0, 500)).stream()
                .filter(x -> words.stream().allMatch(hay(x)::contains)).toList();
        Set<String> keys = new LinkedHashSet<>();
        hit.forEach(x -> keys.addAll(PriceListKeys.of(x)));
        List<PriceListItem> same = keys.isEmpty() ? List.of() : find(m, keys);
        hit = hit.stream().filter(x -> same.stream().noneMatch(y -> newerOfSameSupplier(y, x))).toList();
        Rates r = rates(m);
        Map<String, MedRegistry> regs = regs(hit);
        return hit.stream()
                .sorted(Comparator.comparing((PriceListItem x) -> x.getPriceList().getDistributor().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PriceListItem::getPriceList, PriceListOrder.NEWER).thenComparingInt(PriceListItem::getLineNo))
                .limit(max).map(x -> row(x, m, r, regs)).toList();
    }

    /** История цен товара строки (спека §7): строки прайсов и ответы на запросы цен, новые сверху, до 10. */
    @Transactional(readOnly = true)
    public List<PriceHistoryEntry> history(Long rowId) {
        Market m = MarketContext.get();
        PriceListItem r = items.findById(rowId).orElse(null);
        if (r == null || r.getPriceList().getMarket() != m) throw new NotFoundException("Строка прайса не найдена: " + rowId);
        List<String> keys = PriceListKeys.of(r);
        if (keys.isEmpty()) return List.of();
        List<Entry> all = new ArrayList<>();
        Map<Long, PriceListItem> perList = new LinkedHashMap<>();
        for (PriceListItem x : find(m, new LinkedHashSet<>(keys))) {
            if (!PriceListKeys.same(PriceListKeys.of(x), keys)) continue;
            // в прайсе несколько комплектаций с этим ключом — та же, что у строки, иначе первая
            perList.merge(x.getPriceList().getId(), x, (a, b) -> sameName(b, r) && !sameName(a, r) ? b
                    : !sameName(a, r) && !sameName(b, r) && b.getLineNo() < a.getLineNo() ? b : a);
        }
        for (PriceListItem x : perList.values()) {
            PriceList p = x.getPriceList();
            all.add(new Entry("LIST", PriceListOrder.date(p), p.getDistributor().getName(), currency(p, m), x.getOurPrice(), p.getId(), null));
        }
        for (Object[] a : replies.findPricedForHistory(m.name())) {
            List<String> k = new ArrayList<>(PriceListKeys.of(longOrNull(a[9]), longOrNull(a[8]), null));
            PriceListCodes.keys(a[6] + " " + (a[7] == null ? "" : a[7])).forEach(c -> k.add("k" + c));
            if (!PriceListKeys.same(k, keys)) continue;
            all.add(new Entry("REPLY", date(a[5]), (String) a[2], a[4] == null ? m.currencyCode() : (String) a[4],
                    (BigDecimal) a[3], null, ((Number) a[1]).longValue()));
        }
        all.sort(Comparator.comparing(Entry::date, Comparator.nullsLast(Comparator.reverseOrder())));
        List<PriceHistoryEntry> out = new ArrayList<>();
        for (int i = 0; i < all.size() && out.size() < HISTORY_MAX; i++) {
            Entry e = all.get(i);
            BigDecimal pct = null;
            for (int j = i + 1; j < all.size(); j++) {
                Entry o = all.get(j);
                if (!o.supplier().equals(e.supplier()) || !o.currency().equals(e.currency())) continue;
                if (o.price().signum() != 0) pct = e.price().subtract(o.price()).multiply(HUNDRED).divide(o.price(), 1, RoundingMode.HALF_UP);
                break;
            }
            out.add(new PriceHistoryEntry(e.kind(), e.date(), e.supplier(), e.currency(), e.price(), pct, e.priceListId(), e.priceRequestId()));
        }
        return out;
    }

    /** Кандидаты связи строки прайса (спека §5.3): наши товары (только KZ) и позиции каталога рынка. */
    @Transactional(readOnly = true)
    public PriceLinkCandidates linkCandidates(String q) {
        String query = q == null ? "" : q.strip();
        if (query.length() < 2) return new PriceLinkCandidates(List.of(), List.of());
        if (query.length() > 200) query = query.substring(0, 200);
        List<LotModelSearchResponse.Our> ours = MarketContext.get() == Market.KZ ? modelSearch.ours(query) : List.of();
        List<EquipmentSearchItemResponse> eq = equipmentService.search(query, 10).stream()
                .map(e -> new EquipmentSearchItemResponse(e.getId(), e.getName(), e.getManufact(),
                        e.getRegistration() == null ? null : PrivateRequestViewBuilder.candidate(e.getRegistration())))
                .toList();
        return new PriceLinkCandidates(ours, eq);
    }

    // ---- внутреннее ----

    /** Ключи цели: наш товар и его коды, позиция каталога, её наш товар и коды, коды текста. */
    private static List<String> keys(Target t, Map<Long, SiteProduct> sp, Map<Long, MedEquipment> eq) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (t.siteProductId() != null) {
            out.add("p" + t.siteProductId());
            SiteProduct p = sp.get(t.siteProductId());
            if (p != null) PriceListCodes.keys(p.getName()).forEach(k -> out.add("k" + k));
        }
        if (t.equipmentId() != null) {
            out.add("e" + t.equipmentId());
            MedEquipment e = eq.get(t.equipmentId());
            if (e != null) {
                if (e.getSiteProduct() != null) out.add("p" + e.getSiteProduct().getId());
                PriceListCodes.keys(e.getName()).forEach(k -> out.add("k" + k));
            }
        }
        PriceListCodes.keys(t.text()).forEach(k -> out.add("k" + k));
        return new ArrayList<>(out);
    }

    private List<PriceListItem> find(Market m, Collection<String> keys) {
        List<Long> p = new ArrayList<>(), e = new ArrayList<>();
        List<String> k = new ArrayList<>();
        for (String s : keys) {
            switch (s.charAt(0)) {
                case 'p' -> p.add(Long.valueOf(s.substring(1)));
                case 'e' -> e.add(Long.valueOf(s.substring(1)));
                default -> k.add(s.substring(1));
            }
        }
        return items.findActiveByKeys(m, p.isEmpty() ? NO_IDS : p, e.isEmpty() ? NO_IDS : e, k.isEmpty() ? NO_KEYS : k);
    }

    /** У каждого поставщика — строки его самого нового прайса среди найденных; порядок — §6.1. */
    private List<PriceHintRow> current(List<PriceListItem> rows, String preferBrand, Market m, Rates r, Map<String, MedRegistry> regs) {
        Map<Long, PriceList> newest = new HashMap<>();
        for (PriceListItem x : rows) {
            newest.merge(x.getPriceList().getDistributor().getId(), x.getPriceList(),
                    (a, b) -> PriceListOrder.NEWER.compare(a, b) <= 0 ? a : b);
        }
        Comparator<PriceListItem> order = Comparator
                .comparing((PriceListItem x) -> converted(x, m, r), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(x -> x.getPriceList().getDistributor().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(PriceListItem::getLineNo);
        if (preferBrand != null && !preferBrand.isBlank()) {
            order = Comparator.comparing((PriceListItem x) -> !maker(x.getPriceList().getDistributor(), preferBrand)).thenComparing(order);
        }
        return rows.stream()
                .filter(x -> newest.get(x.getPriceList().getDistributor().getId()).getId().equals(x.getPriceList().getId()))
                .sorted(order).limit(ROWS_MAX).map(x -> row(x, m, r, regs)).toList();
    }

    private static boolean maker(Distributor d, String brand) {
        return d.isManufacturer() && BrandMatch.firstCarried(d.getBrands(), brand) != null;
    }

    private static boolean newerOfSameSupplier(PriceListItem y, PriceListItem x) {
        return y.getPriceList().getDistributor().getId().equals(x.getPriceList().getDistributor().getId())
                && !y.getPriceList().getId().equals(x.getPriceList().getId())
                && PriceListOrder.NEWER.compare(y.getPriceList(), x.getPriceList()) < 0
                && PriceListKeys.same(PriceListKeys.of(y), PriceListKeys.of(x));
    }

    private PriceHintRow row(PriceListItem x, Market m, Rates r, Map<String, MedRegistry> regs) {
        PriceList p = x.getPriceList();
        String cur = currency(p, m);
        boolean foreign = !cur.equals(m.currencyCode());
        BigDecimal our = foreign ? r.our().get(cur) : null;
        CurrencyRates.Today td = foreign ? r.today().get(cur) : null;
        MedRegistry rec = x.getRegNumber() == null ? null : regs.get(RegNumbers.normalize(x.getRegNumber()));
        return new PriceHintRow(x.getId(), p.getId(), p.getDistributor().getId(), p.getDistributor().getName(),
                p.getDistributor().isManufacturer(), x.getSection(), x.getName(), x.getModel(), x.getArticle(),
                PriceListOrder.date(p), p.getValidTo(), p.getValidTo() != null && p.getValidTo().isBefore(r.date()), cur,
                x.getOurPrice(), our, converted(x, m, r), td == null ? null : RateMath.convert(x.getOurPrice(), td.rate()),
                td == null ? null : td.date(), p.getTerms(), x.getRegText(), rec != null ? rec.getRegNumber() : x.getRegNumber(),
                PriceListRegs.status(x.isNoReg(), x.getRegNumber(), rec, r.date()), rec == null ? null : rec.getExpirationDate());
    }

    private static BigDecimal converted(PriceListItem x, Market m, Rates r) {
        String cur = currency(x.getPriceList(), m);
        if (cur.equals(m.currencyCode())) return x.getOurPrice();
        BigDecimal our = r.our().get(cur);
        return our == null ? null : RateMath.convert(x.getOurPrice(), our);
    }

    static String currency(PriceList p, Market m) {
        return p.getCurrency() == null ? m.currencyCode() : p.getCurrency();
    }

    private Rates rates(Market m) {
        return new Rates(rates.ours(m), rates.today(m), LocalDate.now(m.zone()));
    }

    private Map<String, MedRegistry> regs(Collection<PriceListItem> rows) {
        Map<String, MedRegistry> out = new HashMap<>();
        for (PriceListItem x : rows) {
            String n = x.getRegNumber() == null ? null : RegNumbers.normalize(x.getRegNumber());
            if (n == null || out.containsKey(n)) continue;
            out.put(n, registry.findByNormalizedRegNumber(n).orElse(null));
        }
        return out;
    }

    static List<String> words(String q) {
        if (q == null) return List.of();
        String t = q.toLowerCase(Locale.ROOT).replace('ё', 'е').strip();
        if (t.length() > 200) t = t.substring(0, 200);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String w : t.split("\\s+")) if (w.length() >= 2) out.add(w);
        return out.stream().sorted(Comparator.comparingInt(String::length).reversed()).limit(5).toList();
    }

    private static String hay(PriceListItem x) {
        return String.join(" ", nz(x.getName()), nz(x.getModel()), nz(x.getArticle()), nz(x.getPriceList().getDistributor().getName()))
                .toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String like(String word) {
        return "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static boolean sameName(PriceListItem a, PriceListItem r) {
        return a.getName() != null && a.getName().equalsIgnoreCase(r.getName());
    }

    private static Long longOrNull(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static LocalDate date(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
```

- [ ] **Step 5: Пути**

В `PriceListController` — поле `private final PriceHints hints;` (конструктор) и:

```java
    @GetMapping("/search")
    public List<PriceHintRow> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "50") int limit) {
        return hints.search(MarketContext.get(), q, limit);
    }

    @PostMapping("/hints")
    public List<PriceHint> hints(@RequestBody PriceHintsRequest in) {
        List<String> texts = in == null || in.texts() == null ? List.of() : in.texts();
        if (texts.size() > PriceHints.TEXTS_MAX) throw new BadRequestException("Не больше 200 позиций за раз");
        return hints.hints(MarketContext.get(), texts.stream().map(PriceHints.Target::text).toList());
    }

    @GetMapping("/rows/{rowId}/history")
    public List<PriceHistoryEntry> history(@PathVariable Long rowId) {
        return hints.history(rowId);
    }

    @GetMapping("/link-candidates")
    public PriceLinkCandidates linkCandidates(@RequestParam(required = false) String q) {
        return hints.linkCandidates(q);
    }
```

(`/search`, `/hints`, `/link-candidates` — литеральные пути: Spring предпочитает их шаблону `/{id}`.) Если подъём контекста
упадёт на круговой зависимости (`LotModelSearch` → … → `PriceHints`), у поля `modelSearch` — `@Lazy` в конструкторе.

- [ ] **Step 6: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.*' --tests 'com.vladoose.nir.lotmodel.*' 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 7: Мутации**

1. В `current` без отбора «самый новый прайс поставщика» → `latestListWins_variantsKept_historyHasOld` краснеет (строка 01.03).
2. В истории без выбора своей комплектации (`perList.merge` → берётся первая по строке) → тот же тест краснеет (140 000).
3. Строки без нашего курса — не в конце (`nullsFirst`) → `draftMixedAndNoOurRate` краснеет.
4. Без `p.currency <> 'MIXED'` в `findActiveByKeys` → `draftMixedAndNoOurRate` краснеет.
5. Без предпочтения завода бренда → `productTarget_byLinkOrCode_makerFirst` краснеет.
6. Поиск без отсева вытесненных → `search_wordsSupplierAndSuperseded` краснеет.
7. Поиск без проверки остальных слов (фильтр `allMatch` убран) → `search_wordsSupplierAndSuperseded` краснеет.
8. История без ответов на запросы цен → `history_includesSupplierReplies` краснеет.
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java && git commit -m "feat(price-lists): подсказки «у кого и почём», поиск по прайсам, история цен, кандидаты связи

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 9: Подсказки на местах — заявка, лот тендера, панель «КП», «Товары», Telegram (сервер)

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/pricelist/LotPriceHints.java`, `PriceLines.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestLineView.java` (`priceList`),
  `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java` (подсказки позиций),
  `src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java` (`priceList`, `priceBudgetPct`),
  `src/main/java/com/vladoose/nir/controller/TenderController.java` (`getLots`),
  `src/main/java/com/vladoose/nir/service/LotSourcingService.java` (причина `PRICE_LIST`),
  `src/main/java/com/vladoose/nir/dto/response/ProductCatalogResponse.java` (`priceList` у товара),
  `src/main/java/com/vladoose/nir/service/productwatch/ProductCatalogQuery.java`,
  `src/main/java/com/vladoose/nir/service/productwatch/ProductWatchNotifier.java`,
  `src/main/java/com/vladoose/nir/service/tendernotify/NewTenderNotifier.java`
- Test: `src/test/java/com/vladoose/nir/pricelist/PriceHintPlacesTest.java`, `LotPriceHintsTest.java`

**Interfaces:**
- Consumes: `PriceHints.hints / Target`, `PriceHint`, `PriceHintRow` (задача 8); `TenderLotProductMatchRepository.findByTenderIdIn`;
  `ResultTexts.money`, `ExecutionTexts.money(BigDecimal, String)`.
- Produces: `LotPriceHints.forLots(Market, List<TenderLot>) → Map<Long, PriceHint>` (по id лота; лот без товара — нет
  ключа); `static Integer LotPriceHints.budgetPct(PriceHint, BigDecimal maxCost)`; `static PriceHints.Target
  LotPriceHints.target(TenderLot, List<TenderLotProductMatch>)`; `interface PriceLines { Map<Long, String> lines(Map<Long,
  List<Long>> lotIdsByTender); static Map<Long, String> merge(Map<Long, String> market, Map<Long, String> price); }` —
  `LotPriceHints implements PriceLines`; у `PrivateRequestLineView`, `TenderLotResponse`, `ProductCatalogResponse.Item` —
  поле `priceList` (`PriceHint`, пусто — подсказки нет); `TenderLotResponse.priceBudgetPct` — закупка лучшей строки в
  тенге × 100 / цена лота за единицу; причина подбора `PRICE_LIST` с подписью «прайс: 150 000 руб.».

- [ ] **Step 1: Тесты — красные**

`LotPriceHintsTest` (чистые правила — без базы):

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.dto.response.PriceHint;
import com.vladoose.nir.dto.response.PriceHintRow;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.pricelist.LotPriceHints;
import com.vladoose.nir.service.pricelist.PriceHints;
import com.vladoose.nir.service.pricelist.PriceLines;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LotPriceHintsTest {

    private static TenderLotProductMatch match(ProductMatchLevel level, Long product, FitVerdict fit, Long fitProduct, Integer met) {
        return TenderLotProductMatch.builder().level(level).productId(product).fit(fit).fitProductId(fitProduct).fitMet(met).build();
    }

    @Test
    void target_modelInWork_thenFit_thenNamed() {
        TenderLot lot = new TenderLot();
        MedEquipment e = MedEquipment.builder().id(5L).name("ДКИ-Н-12").build();
        lot.setProposedEquipment(e);
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.NAMED, 9L, null, null, null))).equipmentId()).isEqualTo(5L);
        lot.setProposedEquipment(null);
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.FITS, 1L, FitVerdict.FITS, 7L, 21))).siteProductId()).isEqualTo(7L);
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.TYPE, null, FitVerdict.PARTIAL, 7L, 3))).siteProductId()).isEqualTo(7L);
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.TYPE, null, FitVerdict.PARTIAL, 7L, 0)))).isNull();   // ◐ без ✓
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.TYPE, null, FitVerdict.NOT_FIT, 7L, 9)))).isNull();
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.COPIED, 8L, null, null, null))).siteProductId()).isEqualTo(8L);
        assertThat(LotPriceHints.target(lot, List.of())).isNull();
    }

    @Test
    void budgetPct_andMerge() {
        PriceHintRow row = new PriceHintRow(1L, 2L, 3L, "Аксион", true, null, "ДКИ-Н-12", null, null, null, null, false, "RUB",
                new BigDecimal("150000"), new BigDecimal("6"), new BigDecimal("900000"), null, null, null, null, null, null, null);
        PriceHint h = new PriceHint(null, List.of(row));
        assertThat(LotPriceHints.budgetPct(h, new BigDecimal("1080000"))).isEqualTo(83);
        assertThat(LotPriceHints.budgetPct(h, null)).isNull();
        assertThat(LotPriceHints.budgetPct(new PriceHint(null, List.of()), new BigDecimal("1"))).isNull();
        Map<Long, String> market = new LinkedHashMap<>(Map.of(1L, "рынок: медиана 1 ₸ (2 дог.)"));
        Map<Long, String> merged = PriceLines.merge(market, Map.of(1L, "закупка по прайсу ≈ 900 000 ₸", 2L, "закупка по прайсу ≈ 1 ₸"));
        assertThat(merged.get(1L)).isEqualTo("рынок: медиана 1 ₸ (2 дог.) · закупка по прайсу ≈ 900 000 ₸");
        assertThat(merged.get(2L)).isEqualTo("закупка по прайсу ≈ 1 ₸");
    }
}
```

`PriceHintPlacesTest`:

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.LotSourcingService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.pricelist.LotPriceHints;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class PriceHintPlacesTest {

    @Autowired WebApplicationContext wac;
    @Autowired PrivateRequestService requests;
    @Autowired PrivateRequestViewBuilder views;
    @Autowired LotSourcingService sourcing;
    @Autowired LotPriceHints lotHints;
    @Autowired PriceListRepository lists;
    @Autowired DistributorRepository distributors;
    @Autowired TenderRepository tenders;
    @Autowired MedEquipmentRepository equipment;
    @Autowired CurrencyRates rates;
    @Autowired CurrencyRateRepository ours;
    MockMvc mvc;
    Distributor a;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ours.deleteAllInBatch();
        MarketContext.set(Market.KZ);
        a = PriceListFixtures.supplier(distributors, Market.KZ, "Места");
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        PriceListFixtures.list(lists, a, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 9, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-94", "150000", "дкин94"});
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private TenderLot publicLot(String name, BigDecimal maxCost, Integer qty) {
        Tender t = Tender.builder().tenderNumber("ZZ-ПРАЙС-" + System.nanoTime()).status("ACTIVE").source(Source.PUBLIC_TENDER)
                .market(Market.KZ).currency("KZT").build();
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setLotNumber(1);
        l.setEquipName(name);
        l.setMaxCost(maxCost);
        l.setQuantity(qty);
        t.getLots().add(l);
        tenders.saveAndFlush(t);
        return t.getLots().get(0);
    }

    @Test
    void privateRequestLine_hasHint() {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Дефибриллятор ДКИ-Н-94", "Аксион", 2, null, null, null),
                input("Кушетка", null, 1, null, null, null), input("Дефибриллятор", "ДКИ-Н-94", 1, null, null, null)),
                LinesMode.APPEND);
        PrivateRequestViewResponse v = views.build(t);
        assertThat(v.getLines().get(0).getPriceList().rows().get(0).converted()).isEqualByComparingTo("900000");
        assertThat(v.getLines().get(1).getPriceList().rows()).isEmpty();
        assertThat(v.getLines().get(2).getPriceList().rows()).hasSize(1);                 // код модели — в поле бренда
    }

    @Test
    void tenderLot_modelInWork_budgetPct_andKpPanelChip() throws Exception {
        TenderLot lot = publicLot("Дефибриллятор", new BigDecimal("1080000"), 2);
        MedEquipment e = equipment.saveAndFlush(MedEquipment.builder().name("Дефибриллятор ДКИ-Н-94").manufact("Аксион").market(Market.KZ).build());
        lot.setProposedEquipment(e);
        tenders.saveAndFlush(lot.getTender());
        MarketContext.clear();
        mvc.perform(get("/api/tenders/" + lot.getTender().getId() + "/lots").header("X-Market", "KZ"))
                .andExpect(jsonPath("$[0].priceList.rows[0].price").value(150000.0))
                .andExpect(jsonPath("$[0].priceBudgetPct").value(83));
        MarketContext.set(Market.KZ);
        assertThat(sourcing.build(lot.getTender().getId(), List.of(lot.getId()), null).getDistributors().stream()
                .filter(x -> x.getDistributor().getId().equals(a.getId())).findFirst().orElseThrow().getReasons())
                .anySatisfy(r -> {
                    assertThat(r.getKind()).isEqualTo("PRICE_LIST");
                    assertThat(r.getLabel()).isEqualTo("прайс: 150\u00A0000\u00A0руб.");
                });
        Map<Long, String> lines = lotHints.lines(Map.of(lot.getTender().getId(), List.of(lot.getId())));
        assertThat(lines.get(lot.getTender().getId())).isEqualTo("закупка по прайсу ≈ 1,8\u00A0млн\u00A0₸");   // 900 000 × 2
    }

    @Test
    void lines_partialAndNoQuantity() {
        TenderLot withPrice = publicLot("Дефибриллятор", null, 1);
        withPrice.setProposedEquipment(equipment.saveAndFlush(MedEquipment.builder().name("ДКИ-Н-94").manufact("Аксион").market(Market.KZ).build()));
        TenderLot noPrice = publicLot("Отсасыватель", null, 1);
        noPrice.setProposedEquipment(equipment.saveAndFlush(MedEquipment.builder().name("ОМП-9/93").manufact("Аксион").market(Market.KZ).build()));
        Map<Long, String> out = lotHints.lines(Map.of(1L, List.of(withPrice.getId(), noPrice.getId())));
        assertThat(out.get(1L)).isEqualTo("закупка по прайсу ≈ 900\u00A0000\u00A0₸ (1 из 2 лотов)");
        TenderLot noQty = publicLot("Дефибриллятор", null, null);
        noQty.setProposedEquipment(equipment.saveAndFlush(MedEquipment.builder().name("ДКИ-Н-94 б/у").manufact("Аксион").market(Market.KZ).build()));
        assertThat(lotHints.lines(Map.of(2L, List.of(noQty.getId())))).doesNotContainKey(2L);   // без количества — не в сумму
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.LotPriceHintsTest' --tests 'com.vladoose.nir.pricelist.PriceHintPlacesTest' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `LotPriceHints`, `PriceLines`, `getPriceList`.

- [ ] **Step 2: `PriceLines` и `LotPriceHints`**

```java
package com.vladoose.nir.service.pricelist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Строка «закупка по прайсу ≈ …» в пачке бота и «🏥 Новый тендер» (спека 2026-10-23 §6.2 п. 4). */
public interface PriceLines {

    /** tenderId → строка по нашим лотам тендера; ни у одного лота нет цены — без строки. */
    Map<Long, String> lines(Map<Long, List<Long>> lotIdsByTender);

    /** Рядом со строкой «рынок»: «рынок: … · закупка по прайсу ≈ …»; рынка нет — только закупка. */
    static Map<Long, String> merge(Map<Long, String> market, Map<Long, String> price) {
        Map<Long, String> out = new LinkedHashMap<>(market);
        price.forEach((id, line) -> out.merge(id, line, (a, b) -> a + " · " + b));
        return out;
    }
}
```

```java
package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.dto.response.PriceHint;
import com.vladoose.nir.dto.response.PriceHintRow;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.tenderresults.ResultTexts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Подсказки лотов тендера (спека 2026-10-23 §6.2 п. 3–4): товар лота — модель в работе, иначе товар сверки («подходит»,
 * «подходит, РУ истекло», «◐ похоже» с ✓ > 0), иначе товар отметки NAMED / COPIED; лот без товара — без подсказки.
 */
@Service
public class LotPriceHints implements PriceLines {

    private final PriceHints hints;
    private final TenderLotRepository lots;
    private final TenderLotProductMatchRepository matches;

    public LotPriceHints(PriceHints hints, TenderLotRepository lots, TenderLotProductMatchRepository matches) {
        this.hints = hints;
        this.lots = lots;
        this.matches = matches;
    }

    public static PriceHints.Target target(TenderLot lot, List<TenderLotProductMatch> ms) {
        MedEquipment e = lot.getProposedEquipment();
        if (e != null) return new PriceHints.Target(null, null, e.getId(), null);
        Long fit = ms.stream().filter(m -> m.getFitProductId() != null && (m.getFit() == FitVerdict.FITS
                        || m.getFit() == FitVerdict.FITS_NO_RU || (m.getFit() == FitVerdict.PARTIAL && m.getFitMet() != null && m.getFitMet() > 0)))
                .map(TenderLotProductMatch::getFitProductId).findFirst().orElse(null);
        if (fit != null) return new PriceHints.Target(null, fit, null, null);
        Long named = ms.stream().filter(m -> m.getProductId() != null
                        && (m.getLevel() == ProductMatchLevel.NAMED || m.getLevel() == ProductMatchLevel.COPIED))
                .map(TenderLotProductMatch::getProductId).findFirst().orElse(null);
        return named == null ? null : new PriceHints.Target(null, named, null, null);
    }

    /** Закупка лучшей строки в валюте рынка × 100 / цена лота за единицу; нет чего-то — null. */
    public static Integer budgetPct(PriceHint h, BigDecimal maxCost) {
        if (h == null || h.rows().isEmpty() || maxCost == null || maxCost.signum() <= 0) return null;
        BigDecimal c = h.rows().get(0).converted();
        return c == null ? null : c.multiply(BigDecimal.valueOf(100)).divide(maxCost, 0, RoundingMode.HALF_UP).intValue();
    }

    @Transactional(readOnly = true)
    public Map<Long, PriceHint> forLots(Market m, List<TenderLot> lotList) {
        Map<Long, List<TenderLotProductMatch>> byLot = new HashMap<>();
        Set<Long> tenderIds = new HashSet<>();
        lotList.forEach(l -> tenderIds.add(l.getTender().getId()));
        if (!tenderIds.isEmpty()) {
            for (TenderLotProductMatch x : matches.findByTenderIdIn(tenderIds)) byLot.computeIfAbsent(x.getLotId(), k -> new ArrayList<>()).add(x);
        }
        List<Long> ids = new ArrayList<>();
        List<PriceHints.Target> targets = new ArrayList<>();
        for (TenderLot l : lotList) {
            PriceHints.Target t = target(l, byLot.getOrDefault(l.getId(), List.of()));
            if (t == null) continue;
            ids.add(l.getId());
            targets.add(t);
        }
        Map<Long, PriceHint> out = new HashMap<>();
        List<PriceHint> found = hints.hints(m, targets);
        for (int i = 0; i < ids.size(); i++) out.put(ids.get(i), found.get(i));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> lines(Map<Long, List<Long>> lotIdsByTender) {
        Set<Long> all = new HashSet<>();
        lotIdsByTender.values().forEach(all::addAll);
        if (all.isEmpty()) return Map.of();
        Map<Long, TenderLot> byId = new HashMap<>();
        lots.findAllById(all).forEach(l -> byId.put(l.getId(), l));
        Map<Long, PriceHint> found = forLots(Market.KZ, new ArrayList<>(byId.values()));
        Map<Long, String> out = new LinkedHashMap<>();
        lotIdsByTender.forEach((tenderId, ids) -> {
            int ours = 0, priced = 0;
            BigDecimal sum = BigDecimal.ZERO;
            for (Long id : ids) {
                if (!found.containsKey(id)) continue;   // лот без нашего товара — не «наш»
                ours++;
                TenderLot l = byId.get(id);
                PriceHint h = found.get(id);
                BigDecimal c = h.rows().isEmpty() ? null : h.rows().get(0).converted();
                if (c == null || l.getQuantity() == null || l.getQuantity() <= 0) continue;
                priced++;
                sum = sum.add(c.multiply(BigDecimal.valueOf(l.getQuantity())));
            }
            if (priced == 0) return;
            out.put(tenderId, "закупка по прайсу ≈ " + ResultTexts.money(sum)
                    + (priced < ours ? " (" + priced + " из " + ours + " " + (ours % 10 == 1 && ours % 100 != 11 ? "лота" : "лотов") + ")" : ""));
        });
        return out;
    }
}
```

- [ ] **Step 3: Частная заявка**

`PrivateRequestLineView` — поле:

```java
    /** «📒 по прайсу» (спека 2026-10-23 §6.2 п. 2): строки текущих прайсов поставщиков — лучшая первой; пусто — нет. */
    private PriceHint priceList;
```

`PrivateRequestViewBuilder` — сеттер (не конструктор: подсказки — необязательная часть вида, а сервис подсказок тянет
поиск моделей; без бина — тесты, собранные руками, — подсказок нет):

```java
    private PriceHints priceHints;

    @Autowired(required = false)
    public void setPriceHints(PriceHints priceHints) {
        this.priceHints = priceHints;
    }

    private Map<Long, PriceHint> hints(Market m, List<TenderLot> lots) {
        Map<Long, PriceHint> out = new HashMap<>();
        if (priceHints == null || lots.isEmpty()) return out;
        List<PriceHint> found = priceHints.hints(m, lots.stream()
                .map(l -> PriceHints.Target.text(String.join(" ", Objects.toString(l.getEquipName(), ""), Objects.toString(l.getManufact(), ""))))
                .toList());
        for (int i = 0; i < lots.size(); i++) out.put(lots.get(i).getId(), found.get(i));
        return out;
    }
```

В `build` — после `records`: `Map<Long, PriceHint> prices = hints(t.getMarket(), lots);` и в `line(...)` — четвёртый
аргумент `prices.get(l.getId())` (`v.setPriceList(hint)`); одиночный `line(TenderLot l)` (ответ на правку строки) —
`hints(l.getTender().getMarket(), List.of(l)).get(l.getId())`.

- [ ] **Step 4: Лот тендера и панель «КП»**

`TenderLotResponse` — поля:

```java
    /** «📒 закупка по прайсу» (спека 2026-10-23 §6.2 п. 3); пусто — у лота нет нашего товара или его нет в прайсах. */
    private PriceHint priceList;
    /** Закупка лучшей строки в валюте рынка — процент от цены лота за единицу. */
    private Integer priceBudgetPct;
```

`TenderController` — поле `private final LotPriceHints lotPriceHints;` (конструктор); в `getLots` перед `return lots;`:

```java
        Map<Long, PriceHint> prices = lotPriceHints.forLots(MarketContext.get(), entities);
        for (TenderLotResponse l : lots) {
            PriceHint h = prices.get(l.getId());
            if (h == null || h.rows().isEmpty()) continue;
            l.setPriceList(h);
            l.setPriceBudgetPct(LotPriceHints.budgetPct(h, l.getMaxCost()));
        }
```

`LotSourcingService` — сеттер `@Autowired(required = false) setLotPriceHints(LotPriceHints)`; в `build`, в одно-лотовом
режиме, до цикла по поставщикам:

```java
        Map<Long, PriceHintRow> priceRows = new HashMap<>();
        if (singleLot && lotPriceHints != null) {
            PriceHint h = lotPriceHints.forLots(MarketContext.get(), lots).get(lots.get(0).getId());
            if (h != null) for (PriceHintRow r : h.rows()) priceRows.putIfAbsent(r.distributorId(), r);
        }
```

и в цикле, после меток брендов:

```java
            PriceHintRow pr = priceRows.get(d.getId());
            if (pr != null) reasons.add(new LotSourcingResponse.Reason("PRICE_LIST", "прайс: " + ExecutionTexts.money(pr.price(), pr.currency())));
```

`relevant` — `brandHit || typeHit || pr != null`, `score += pr != null ? 0.4 : 0.0` (поставщик с товаром в прайсе — среди
релевантных; отметку по умолчанию не ставит — она по-прежнему по бренду).

- [ ] **Step 5: «Товары» и Telegram**

`ProductCatalogResponse.Item` — последним компонентом `PriceHint priceList`. `ProductCatalogQuery` — сеттер
`setPriceHints(PriceHints)` (`@Autowired(required = false)`); в `list()` до сборки строк:

```java
        List<SiteProduct> list = products.findByMarketAndRemovedAtIsNull(Market.KZ).stream()
                .sorted(Comparator.comparing((SiteProduct p) -> Objects.toString(p.getBrand(), "")).thenComparing(SiteProduct::getName))
                .toList();
        List<PriceHint> prices = priceHints == null ? null : priceHints.hints(Market.KZ,
                list.stream().map(p -> new PriceHints.Target(null, p.getId(), null, p.getBrand())).toList());
```

и у строки — `prices == null ? null : prices.get(i)` (цикл по индексу вместо `stream().map`).

`ProductWatchNotifier.publishDigest` и `NewTenderNotifier.afterImport` — сеттер `@Autowired(required = false)
setPriceLines(PriceLines)` и сразу после блока `marketLines`:

```java
        if (priceLines != null) {
            try {
                market = PriceLines.merge(market, priceLines.lines(lotIds));          // у NewTenderNotifier — p.lotIds()
            } catch (RuntimeException e) {
                log.warn("«Наш товар»: строки закупки по прайсу не собраны: {}", ErrorText.of(e));
            }
        }
```

(в `NewTenderNotifier` текст лога — «Telegram: строки закупки по прайсу к новым тендерам не собраны: {}»; переменная
`lines` берётся после слияния).

- [ ] **Step 6: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.*' --tests '*PrivateRequest*' --tests '*LotSourcing*' --tests '*ProductCatalog*' --tests '*ProductWatchNotifier*' --tests '*NewTenderNotifier*' 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 7: Мутации**

1. `target` без условия «✓ > 0» у PARTIAL → `target_modelInWork_thenFit_thenNamed` краснеет.
2. `lines` считает лот без количества → `lines_partialAndNoQuantity` краснеет.
3. Без «(N из M лотов)» → тот же тест краснеет.
4. `budgetPct` от `price` (рубли), а не `converted` → `tenderLot_modelInWork_budgetPct_andKpPanelChip` краснеет (14 %).
5. Подсказки позиции заявки без бренда в тексте (`getManufact` убран из `hints`) → `privateRequestLine_hasHint` краснеет
   (третья позиция — код модели в поле бренда).
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java && git commit -m "feat(price-lists): подсказки по прайсам у позиций заявки, лотов, в панели «КП», у товаров и в Telegram

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 10: КП клиенту — строка прайса в «Поставщик ▾», «Создать КП клиенту» из прайса, «Пришли новые цены» (сервер)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java` (`priceListItemId`, только для чтения
  `priceListId`, `priceListLabel`), `src/main/java/com/vladoose/nir/service/ClientOfferService.java` (`Link` — ещё строка
  прайса; `checkLink`; `applyItem`; `copyItem`), `src/main/java/com/vladoose/nir/service/ClientOfferMapper.java` (подписи
  строк прайса), `src/main/java/com/vladoose/nir/dto/response/SupplierOfferResponse.java` (поля строки прайса),
  `src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java` (`offers`, `createFromPrivateRequest`, `updates`,
  `applyNewPrices`, `linkPriceList`), `src/main/java/com/vladoose/nir/service/pricelist/PriceHints.java`
  (`latestOfSameSupplier`)
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferPriceListTest.java`

**Interfaces:**
- Consumes: `PriceHints.hint / hints / Target`, `PriceHintRow` (задача 8); `PriceListItemRepository.findLabels` (задача 8);
  поля закупки строки КП и `checkPurchase` (задача 4).
- Produces: у `ClientOfferItemDto` — `priceListItemId` (пишется PUT), `priceListId`, `priceListLabel` («Аксион от
  01.09.2026»); `SupplierOfferResponse(…, Long priceListItemId, Long priceListId, String currency, BigDecimal original,
  BigDecimal rate, LocalDate listDate, boolean expired)` + `fromPriceList(PriceHintRow, boolean selected)` +
  `withCheapest(boolean)`; `static void ClientOfferRequestService.linkPriceList(ClientOfferItem, PriceHintRow, Market)`;
  `PriceHints.latestOfSameSupplier(Market, Long rowId) → Optional<PriceHintRow>`.

- [ ] **Step 1: Тесты — красные**

`ClientOfferPriceListTest` (помощники запроса — как в `ClientOfferPurchaseCurrencyTest`, задача 4):

```java
package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.SupplierOfferResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.pricelist.PriceListFixtures;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.rates.CurrencyRates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class ClientOfferPriceListTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired CurrencyRates rates;
    @Autowired CurrencyRateRepository ours;
    @Autowired PriceListRepository lists;
    @Autowired DistributorRepository distributors;
    @Autowired PrivateRequestService requests;
    @Autowired ClientOfferRequestService requestOffers;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
    MockMvc mvc;
    Distributor a;
    PriceList old;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ours.deleteAllInBatch();
        MarketContext.set(Market.KZ);
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6")))), "vlad");
        a = PriceListFixtures.supplier(distributors, Market.KZ, "КП прайс");
        old = PriceListFixtures.list(lists, a, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 3, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-93", "140000", "дкин93"});
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static MockHttpServletRequestBuilder kz(MockHttpServletRequestBuilder b) { return b.header("X-Market", "KZ"); }

    private JsonNode json(MockHttpServletRequestBuilder b) throws Exception {
        MarketContext.clear();
        return om.readTree(mvc.perform(b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ObjectNode line(Long priceListItemId, Long priceRequestItemId) {
        ObjectNode it = om.createObjectNode();
        it.put("key", "p1");
        it.put("kind", "ITEM");
        it.put("name", "Дефибриллятор ДКИ-Н-93");
        it.put("unit", "шт");
        it.put("quantity", 1);
        it.put("purchaseCurrency", "RUB");
        it.put("purchaseOriginal", 140000);
        it.put("vatRate", 16);
        it.put("registrationStatus", "UNCHECKED");
        if (priceListItemId != null) it.put("priceListItemId", priceListItemId);
        if (priceRequestItemId != null) it.put("priceRequestItemId", priceRequestItemId);
        return it;
    }

    /** PUT КП с одной строкой. */
    private MockHttpServletRequestBuilder save(JsonNode offer, ObjectNode item) {
        ObjectNode body = offer.deepCopy();
        body.putArray("items").add(item);
        return kz(put("/api/client-offers/" + offer.get("id").asLong())).contentType(MediaType.APPLICATION_JSON).content(body.toString());
    }

    @Test
    void putPriceListLink_supplierFromList_labelInResponse() throws Exception {
        JsonNode o = json(kz(post("/api/client-offers")));
        Long rowId = old.getItems().get(0).getId();
        JsonNode saved = json(save(o, line(rowId, null)));
        JsonNode it = saved.get("items").get(0);
        assertThat(it.get("priceListItemId").asLong()).isEqualTo(rowId);
        assertThat(it.get("distributorId").asLong()).isEqualTo(a.getId());
        assertThat(it.get("priceListId").asLong()).isEqualTo(old.getId());
        assertThat(it.get("priceListLabel").asText()).isEqualTo(a.getName() + " от 01.03.2026");
        assertThat(it.get("purchasePrice").decimalValue()).isEqualByComparingTo("840000");
    }

    @Test
    void bothLinks400_draftRow400_unknownRowDropped() throws Exception {
        JsonNode o = json(kz(post("/api/client-offers")));
        Long rowId = old.getItems().get(0).getId();
        mvc.perform(save(o, line(rowId, 1L))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("либо ответ поставщика, либо строка прайса")));
        MarketContext.set(Market.KZ);
        PriceList draft = PriceListFixtures.list(lists, a, PriceListStatus.DRAFT, "RUB", null, new Object[]{"ДКИ-Н-93", "1", "дкин93"});
        MarketContext.clear();
        mvc.perform(save(o, line(draft.getItems().get(0).getId(), null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Строка прайса не найдена")));
        JsonNode saved = json(save(o, line(999_999_999L, null)));
        assertThat(saved.get("items").get(0).get("priceListItemId").isNull()).isTrue();
    }

    @Test
    void supplierMenu_hasPriceListRows_newerListPlashka() throws Exception {
        JsonNode o = json(kz(post("/api/client-offers")));
        JsonNode saved = json(save(o, line(old.getItems().get(0).getId(), null)));
        long itemId = saved.get("items").get(0).get("id").asLong();
        MarketContext.set(Market.KZ);
        List<SupplierOfferResponse> menu = requestOffers.offers(o.get("id").asLong(), itemId);
        assertThat(menu).hasSize(1);
        assertThat(menu.get(0).priceListItemId()).isEqualTo(old.getItems().get(0).getId());
        assertThat(menu.get(0).selected()).isTrue();
        assertThat(menu.get(0).original()).isEqualByComparingTo("140000");
        assertThat(menu.get(0).price()).isEqualByComparingTo("840000");
        PriceList fresh = PriceListFixtures.list(lists, a, PriceListStatus.ACTIVE, "RUB", LocalDate.of(2026, 9, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-93", "150000", "дкин93"});
        assertThat(requestOffers.newPrices(o.get("id").asLong()).lines()).singleElement()
                .satisfies(l -> assertThat(l.newPrice()).isEqualByComparingTo("900000"));
        ClientOffer applied = requestOffers.applyNewPrices(o.get("id").asLong());   // КП не из заявки — тоже можно
        ClientOfferItem line = applied.getItems().get(0);
        assertThat(line.getPriceListItemId()).isEqualTo(fresh.getItems().get(0).getId());
        assertThat(line.getPurchasePrice()).isEqualByComparingTo("900000");
        assertThat(line.getPurchaseOriginal()).isEqualByComparingTo("150000");
        assertThat(requestOffers.newPrices(o.get("id").asLong()).lines()).isEmpty();
    }

    /** Выбор в «Поставщик ▾» ответа в рублях со старым курсом: курс ответа допустим (иначе экран показал бы одно, а КП — другое). */
    @Test
    void pickedReplyInRubles_keepsItsRate() throws Exception {
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Отсасыватель ОМП-9/92", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().iterator().next();
        PriceRequestItem cheap = answer(t, lot, "Дешевле", "54000", "9000", "6");      // лучший — его возьмёт КП
        PriceRequestItem other = answer(t, lot, "Старый курс", "55000", "10000", "5.5");
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "vlad");
        assertThat(kp.getItems().get(0).getPriceRequestItemId()).isEqualTo(cheap.getId());
        long itemId = kp.getItems().get(0).getId();
        MarketContext.clear();
        JsonNode o = json(kz(get("/api/client-offers/" + kp.getId())));
        ObjectNode it = (ObjectNode) o.get("items").get(0).deepCopy();
        it.put("id", itemId);
        it.put("priceRequestItemId", other.getId());                                     // выбор в «Поставщик ▾»
        it.put("purchaseOriginal", 10000);
        it.put("purchaseRate", 5.5);                                                     // не наш (6) и не строки (6)
        assertThat(json(save(o, it)).get("items").get(0).get("purchasePrice").decimalValue()).isEqualByComparingTo("55000");
    }

    private PriceRequestItem answer(Tender t, TenderLot lot, String supplier, String price, String original, String rate) {
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, supplier);
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("RESPONDED")
                .letterStatus(LetterStatus.SENT).sentAt(java.time.OffsetDateTime.now()).build());
        PriceRequestItem i = priceItems.saveAndFlush(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(new BigDecimal(price)).responseCurrency("RUB").responseOriginal(new BigDecimal(original))
                .responseRate(new BigDecimal(rate)).build());
        pr.getItems().add(i);
        return i;
    }

    @Test
    void kpFromRequest_noReply_takesPriceListRow() {
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Дефибриллятор ДКИ-Н-93", null, 2, null, null, null)), LinesMode.APPEND);
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "vlad");
        ClientOfferItem it = kp.getItems().get(0);
        assertThat(it.getPriceListItemId()).isEqualTo(old.getItems().get(0).getId());
        assertThat(it.getPurchasePrice()).isEqualByComparingTo("840000");
        assertThat(it.getPurchaseCurrency()).isEqualTo("RUB");
        assertThat(it.getSupplierName()).isEqualTo(a.getName());
        assertThat(it.getPriceRequestItemId()).isNull();
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferPriceListTest' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `priceListItemId()` у `SupplierOfferResponse`, полей у DTO строки.

- [ ] **Step 2: Строка КП — ссылка на строку прайса**

`ClientOfferItemDto` — после `priceRequestItemId`:

```java
    /** Строка прайса, из которой закупка (спека 2026-10-23 §6.3; решение плана 17 — либо она, либо ответ поставщика). */
    private Long priceListItemId;
    /** Только для чтения: прайс строки и подпись «Аксион от 01.09.2026» (метка «📒 из прайса», «Подробнее»). */
    private Long priceListId;
    private String priceListLabel;
```

`ClientOfferService` — поле `private final PriceListItemRepository priceListItems;` (конструктор). `Link`:

```java
    /** Ссылка строки после проверки: keep — та, что сохранена; иначе — ответ или строка прайса и их поставщик. */
    private record Link(boolean keep, Long priceRequestItemId, Long priceListItemId, Long distributorId) {
        static final Link NONE = new Link(false, null, null, null);
        static final Link KEEP = new Link(true, null, null, null);
    }
```

`checkPurchase` (задача 4) — ещё один допустимый курс: курс ответа поставщика, на который строка теперь ссылается (та же
валюта). Иначе выбор в «Поставщик ▾» ответа, пересчитанного по прежнему нашему курсу, давал бы 400, а закупка в КП
расходилась бы с тем, что показано в списке; такую строку подсветит плашка «Пересчитать». После `BigDecimal ours = …`:

```java
        BigDecimal linked = dto.getPriceRequestItemId() == null ? null : priceRequestItems.findById(dto.getPriceRequestItemId())
                .filter(a -> cur.equals(a.getResponseCurrency())).map(PriceRequestItem::getResponseRate).orElse(null);
```

и условие отказа — `!(storedRate != null && storedRate.compareTo(rate) == 0) && !(ours != null && ours.compareTo(rate) == 0)
&& !(linked != null && linked.compareTo(rate) == 0)`.

`checkLink` — в начале:

```java
        Long listRow = dto.getPriceListItemId();
        if (listRow != null && dto.getPriceRequestItemId() != null) {
            throw new BadRequestException("У строки — либо ответ поставщика, либо строка прайса — позиция " + row);
        }
        if (listRow != null) return checkPriceListLink(o, listRow, stored, row);
```

прежний возврат — `new Link(false, id, null, pr.getDistributor().getId())`, и новый метод:

```java
    /**
     * Ссылка на строку прайса (спека §6.3): та же, что сохранена, — не перепроверяется; новая — строка действующего прайса
     * рынка КП, иначе 400; строки нет (прайс удалили) — ссылка молча снимается. Поставщик — поставщик прайса.
     */
    private Link checkPriceListLink(ClientOffer o, Long id, ClientOfferItem stored, String row) {
        if (stored != null && id.equals(stored.getPriceListItemId())) return Link.KEEP;
        PriceListItem r = priceListItems.findById(id).orElse(null);
        if (r == null) return Link.NONE;
        PriceList p = r.getPriceList();
        if (p.getMarket() != o.getMarket() || p.getStatus() != PriceListStatus.ACTIVE) {
            throw new BadRequestException("Строка прайса не найдена — позиция " + row);
        }
        return new Link(false, null, id, p.getDistributor().getId());
    }
```

`applyItem`: в ветке не-ITEM — `it.setPriceListItemId(null);`; в конце —

```java
        if (!link.keep()) {
            it.setPriceRequestItemId(link.priceRequestItemId());
            it.setPriceListItemId(link.priceListItemId());
            it.setDistributorId(link.distributorId());
        }
```

`copyItem` — `.priceListItemId(it.getPriceListItemId())`.

`ClientOfferMapper` — поле `private final PriceListItemRepository priceListItems;` (конструктор); в `toItemDto` —
`d.setPriceListItemId(it.getPriceListItemId());`; в `toResponse` перед циклом строк:

```java
        Set<Long> listRows = o.getItems().stream().map(ClientOfferItem::getPriceListItemId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Object[]> listLabels = new HashMap<>();
        if (!listRows.isEmpty()) for (Object[] x : priceListItems.findLabels(listRows)) listLabels.put(((Number) x[0]).longValue(), x);
```

и в цикле:

```java
            Object[] pl = it.getPriceListItemId() == null ? null : listLabels.get(it.getPriceListItemId());
            if (pl != null) {
                LocalDate date = pl[3] != null ? (LocalDate) pl[3] : ((OffsetDateTime) pl[4]).toLocalDate();
                d.setPriceListId(((Number) pl[1]).longValue());
                d.setPriceListLabel(pl[2] + " от " + date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
            }
```

- [ ] **Step 3: «Поставщик ▾» — строки прайсов**

`SupplierOfferResponse`:

```java
/**
 * Пункт «Поставщик ▾» (спека 2026-10-12 §8; 2026-10-23 §6.3): ответ поставщика с ценой или строка прайса (priceListItemId);
 * price — закупка в валюте рынка (у строки прайса — по нашему курсу; нет курса — пусто, выбрать нельзя); currency /
 * original / rate — исходная цена в чужой валюте.
 */
public record SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                    boolean accepted, boolean cheapest, boolean selected, Long priceRequestId, boolean auto,
                                    Long priceListItemId, Long priceListId, String currency, BigDecimal original,
                                    BigDecimal rate, LocalDate listDate, boolean expired) {

    public SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                 boolean accepted, boolean cheapest, boolean selected, Long priceRequestId, boolean auto) {
        this(priceRequestItemId, supplierName, price, respondedOn, accepted, cheapest, selected, priceRequestId, auto,
                null, null, null, null, null, null, false);
    }

    public SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                 boolean accepted, boolean cheapest, boolean selected) {
        this(priceRequestItemId, supplierName, price, respondedOn, accepted, cheapest, selected, null, false);
    }

    public static SupplierOfferResponse fromPriceList(PriceHintRow r, boolean selected) {
        boolean foreign = r.ourRate() != null;
        return new SupplierOfferResponse(null, r.supplierName(), r.converted(), r.listDate(), false, false, selected, null, false,
                r.rowId(), r.priceListId(), r.currency(), foreign ? r.price() : null, r.ourRate(), r.listDate(), r.expired());
    }

    public SupplierOfferResponse withCheapest(boolean c) {
        return new SupplierOfferResponse(priceRequestItemId, supplierName, price, respondedOn, accepted, c, selected,
                priceRequestId, auto, priceListItemId, priceListId, currency, original, rate, listDate, expired);
    }
}
```

`ClientOfferRequestService` — сеттер `@Autowired(required = false) setPriceHints(PriceHints)`; `offers`:

```java
    @Transactional(readOnly = true)
    public List<SupplierOfferResponse> offers(Long offerId, Long itemId) {
        ClientOffer o = offers.get(offerId);
        ClientOfferItem it = o.getItems().stream().filter(i -> itemId.equals(i.getId())).findFirst()
                .orElseThrow(() -> new NotFoundException("Строка КП не найдена: id=" + itemId));
        List<SupplierOfferResponse> out = new ArrayList<>();
        if (o.getTenderId() != null && it.getTenderLotId() != null) {
            for (PurchaseOffer c : offersByLot(o.getTenderId()).getOrDefault(it.getTenderLotId(), List.of())) {
                out.add(new SupplierOfferResponse(c.priceRequestItemId(), c.supplierName(), c.price(), c.respondedOn(),
                        "ACCEPTED".equals(c.status()), false, Objects.equals(c.priceRequestItemId(), it.getPriceRequestItemId()),
                        c.priceRequestId(), c.auto(), null, null, c.currency(), c.original(), c.rate(), null, false));
            }
        }
        if (priceHints != null && it.getKind() == ClientOfferItemKind.ITEM) {
            for (PriceHintRow r : priceHints.hint(o.getMarket(), target(it)).rows()) {
                out.add(SupplierOfferResponse.fromPriceList(r, Objects.equals(r.rowId(), it.getPriceListItemId())));
            }
        }
        BigDecimal min = out.stream().map(SupplierOfferResponse::price).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        return out.stream().map(x -> x.withCheapest(min != null && x.price() != null && x.price().compareTo(min) == 0)).toList();
    }

    /** Цель подсказки строки КП (спека §6.1): позиция каталога и текст — наименование, модель, производитель. */
    static PriceHints.Target target(ClientOfferItem it) {
        return new PriceHints.Target(String.join(" ", Objects.toString(it.getName(), ""), Objects.toString(it.getModel(), ""),
                Objects.toString(it.getManufacturer(), "")), null, it.getMedEquipmentId(), null);
    }

    /** Закупка из строки прайса: цена в валюте рынка по нашему курсу, исходная цена и курс, поставщик; ответ снимается. */
    static void linkPriceList(ClientOfferItem it, PriceHintRow r, Market m) {
        boolean foreign = !m.currencyCode().equals(r.currency());
        it.setPurchasePrice(r.converted().setScale(2, RoundingMode.HALF_UP));
        it.setPurchaseCurrency(foreign ? r.currency() : null);
        it.setPurchaseOriginal(foreign ? r.price() : null);
        it.setPurchaseRate(foreign ? r.ourRate() : null);
        it.setSupplierName(cut(r.supplierName(), 255));
        it.setDistributorId(r.distributorId());
        it.setPriceRequestItemId(null);
        it.setPriceListItemId(r.rowId());
    }
```

`createFromPrivateRequest` — до цикла:

```java
        List<TenderLot> sorted = t.getLots().stream().sorted(BY_LINE).toList();
        List<PriceHint> fromLists = priceHints == null ? List.of() : priceHints.hints(o.getMarket(), sorted.stream()
                .map(l -> PriceHints.Target.text(Objects.toString(l.getEquipName(), "") + " " + Objects.toString(l.getManufact(), "")))
                .toList());
```

в цикле (по индексу `i` вместо `for-each`) — после строки `PurchaseOffers.best(...)`:

```java
            Optional<PurchaseOffer> best = PurchaseOffers.best(byLot.getOrDefault(lot.getId(), List.of()));
            if (best.isPresent()) {
                link(it, best.get());
            } else if (!fromLists.isEmpty()) {                       // «📒 из прайса, не запрашивали»
                fromLists.get(i).rows().stream().filter(r -> r.converted() != null).findFirst()
                        .ifPresent(r -> linkPriceList(it, r, o.getMarket()));
            }
```

- [ ] **Step 4: «Пришли новые цены» — и строки прайсов**

`PriceHints`:

```java
    /** Строка того же товара в самом новом действующем прайсе того же поставщика (та же комплектация, иначе первая). */
    @Transactional(readOnly = true)
    public Optional<PriceHintRow> latestOfSameSupplier(Market m, Long rowId) {
        PriceListItem r = items.findById(rowId).orElse(null);
        if (r == null || r.getPriceList().getMarket() != m) return Optional.empty();
        List<String> keys = PriceListKeys.of(r);
        if (keys.isEmpty()) return Optional.empty();
        Long supplier = r.getPriceList().getDistributor().getId();
        List<PriceListItem> same = find(m, new LinkedHashSet<>(keys)).stream()
                .filter(x -> x.getPriceList().getDistributor().getId().equals(supplier) && PriceListKeys.same(PriceListKeys.of(x), keys))
                .toList();
        if (same.isEmpty()) return Optional.empty();
        PriceList newest = same.stream().map(PriceListItem::getPriceList).sorted(PriceListOrder.NEWER).findFirst().orElseThrow();
        PriceListItem pick = same.stream().filter(x -> x.getPriceList().getId().equals(newest.getId()))
                .min(Comparator.comparing((PriceListItem x) -> !sameName(x, r)).thenComparingInt(PriceListItem::getLineNo))
                .orElseThrow();
        return Optional.of(row(pick, m, rates(m), regs(List.of(pick))));
    }
```

`ClientOfferRequestService` — `Update` с полем строки прайса:

```java
    /** Что подставить строке: ответ (offer) или строка прайса (row) и цена в валюте рынка. */
    private record Update(ClientOfferItem item, PurchaseOffer offer, PriceHintRow row, BigDecimal price) {
        String supplierName() {
            return offer != null ? offer.supplierName() : row.supplierName();
        }
    }
```

`updates(ClientOffer o)` — первой строкой `if (o.getStatus() != ClientOfferStatus.DRAFT) return List.of();`; ответы
поставщиков — как раньше, только если `o.getTenderId() != null` (`new Update(it, best, null, scaled(best.price()))`);
затем строки прайсов — для любого черновика:

```java
        Set<ClientOfferItem> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        out.forEach(u -> taken.add(u.item()));
        List<ClientOfferItem> empty = new ArrayList<>();
        for (ClientOfferItem it : o.getItems()) {
            if (it.getKind() != ClientOfferItemKind.ITEM || taken.contains(it) || priceHints == null) continue;
            if (it.getPriceListItemId() != null && it.getPurchasePrice() != null) {
                // более новый прайс того же поставщика — другая цена (сравниваем исходные цены: курс меняет «Пересчитать»)
                priceHints.latestOfSameSupplier(o.getMarket(), it.getPriceListItemId())
                        .filter(r -> r.converted() != null && original(it).compareTo(r.price()) != 0)
                        .ifPresent(r -> out.add(new Update(it, null, r, scaled(r.converted()))));
            } else if (it.getPurchasePrice() == null) {
                empty.add(it);
            }
        }
        if (!empty.isEmpty()) {
            List<PriceHint> found = priceHints.hints(o.getMarket(), empty.stream().map(ClientOfferRequestService::target).toList());
            for (int i = 0; i < empty.size(); i++) {
                ClientOfferItem it = empty.get(i);
                found.get(i).rows().stream().filter(r -> r.converted() != null).findFirst()
                        .ifPresent(r -> out.add(new Update(it, null, r, scaled(r.converted()))));
            }
        }
```

(`original(it)` — `it.getPurchaseOriginal() != null ? it.getPurchaseOriginal() : it.getPurchasePrice()`. Строки без закупки,
которым уже нашёлся ответ поставщика, в `taken` — второй раз не попадают.) `newPrices` — `u.supplierName()`;
`applyNewPrices` — без отказа «КП не из заявки» и с `u.offer() != null ? link(...) : linkPriceList(u.item(), u.row(),
o.getMarket())`.

- [ ] **Step 5: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*' --tests 'com.vladoose.nir.readyoffer.*' 2>&1 | tail -20`
Expected: PASS (`ClientOfferNewPricesTest.onlyDraftFromRequest` — отправленное КП по-прежнему 400).

- [ ] **Step 6: Мутации**

1. Без отказа «обе ссылки» → `bothLinks400_draftRow400_unknownRowDropped` краснеет.
2. Строка черновика прайса принимается (`p.getStatus()` не проверять) → тот же тест краснеет.
3. Поставщик строки — из DTO, а не из прайса → `putPriceListLink_supplierFromList_labelInResponse` краснеет.
4. `updates` по строкам прайса только у КП из заявки → `supplierMenu_hasPriceListRows_newerListPlashka` краснеет.
5. `createFromPrivateRequest` без строки прайса → `kpFromRequest_noReply_takesPriceListRow` краснеет.
6. `checkPurchase` без курса ответа (`linked` убран) → `pickedReplyInRubles_keepsItsRate` краснеет (400).
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java && git commit -m "feat(client-offer): закупка из прайса — «Поставщик ▾», КП из заявки без ответа, «Пришли новые цены» по прайсам

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 11: «Входящие» — файлы писем «прочее», «Занести как прайс», строка в Telegram; число прайсов у поставщиков (сервер)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/mail/MailIngestWriter.java` (файлы писем «прочее», строка «📒»),
  `src/main/java/com/vladoose/nir/service/mail/MailNotificationComposer.java` (`other` — со строкой),
  `src/main/java/com/vladoose/nir/controller/InboundController.java` (`priceFiles`),
  `src/main/java/com/vladoose/nir/dto/response/InboundEmailResponse.java` (`priceFiles`),
  `src/main/java/com/vladoose/nir/service/pricelist/PriceListService.java` (`fromInbound`, `counts`),
  `src/main/java/com/vladoose/nir/controller/PriceListController.java` (`/from-inbound/{fileId}`, `/counts`)
- Test: `src/test/java/com/vladoose/nir/pricelist/PriceListInboundTest.java`

**Interfaces:**
- Consumes: `InboundEmailFile`, `InboundEmailFileRepository.findInfoByInboundIds` (задача 1); `PriceListService.create`
  (задача 7); `SupplierReplyFiles.kind / fileName / sha256`; `EmailAddresses.parse`; `TestMails` (тесты).
- Produces: `MailIngestWriter.priceFiles(ParsedMail) → List<InboundEmailFile>` (Excel, Word, PDF с байтами ≤ 15 МБ;
  `priceLike` — «прайс» / «прейскурант» / «price» в имени или теме), `MailIngestWriter.PRICE_LINE`; у
  `InboundEmailResponse` — `priceFiles: [{id, name, priceLike}]`; `PriceListService.fromInbound(Long fileId, Long
  distributorId, String user) → Long`, `counts() → Map<Long, Long>`; `POST /api/price-lists/from-inbound/{fileId}`
  `{distributorId?}` → вид экрана проверки; `GET /api/price-lists/counts` → `{distributorId: N}`.

- [ ] **Step 1: Тесты — красные**

```java
package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.mail.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class PriceListInboundTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired WebApplicationContext wac;
    @Autowired MailIngestWriter writer;
    @Autowired InboundEmailRepository inbound;
    @Autowired InboundEmailFileRepository inboundFiles;
    @Autowired DistributorRepository distributors;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void priceFiles_onlyOfficeAndPdf_priceLikeByNameOrSubject() throws Exception {
        byte[] docx = PriceListFiles.orionLikeDocx();
        ParsedMail m = TestMails.mail().subject("Обновление цен").attachments("Прайс ноябрь.docx", "фото.jpg", "КП.docx")
                .files(new MailFile("Прайс ноябрь.docx", DOCX, (long) docx.length, docx),
                        new MailFile("фото.jpg", "image/jpeg", 3L, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
                        new MailFile("КП.docx", DOCX, (long) docx.length, docx),
                        new MailFile("большой.pdf", "application/pdf", null, null))   // больше предела — без байтов
                .build();
        List<InboundEmailFile> files = MailIngestWriter.priceFiles(m);
        assertThat(files).extracting(InboundEmailFile::getFileName).containsExactly("Прайс ноябрь.docx", "КП.docx");
        assertThat(files).extracting(InboundEmailFile::isPriceLike).containsExactly(true, false);
        ParsedMail bySubject = TestMails.mail().subject("Прейскурант 2026").attachments("ноябрь.docx")
                .files(new MailFile("ноябрь.docx", DOCX, (long) docx.length, docx)).build();
        assertThat(MailIngestWriter.priceFiles(bySubject).get(0).isPriceLike()).isTrue();
    }

    @Test
    void unmatchedLetter_storesFiles_otherKindsDoNot() throws Exception {
        byte[] docx = PriceListFiles.orionLikeDocx();
        ParsedMail m = TestMails.mail().from("ZZ Завод <zz-prices@example.kz>").subject("Прайс-лист")
                .attachments("Прайс.docx").files(new MailFile("Прайс.docx", DOCX, (long) docx.length, docx)).build();
        MailIngestWriter.WriteResult r = writer.write(m, 1L);
        assertThat(inboundFiles.findInfoByInboundIds(List.of(r.inboundId()))).hasSize(1);
        ParsedMail kp = TestMails.mail().subject("Re: [КП-999999998] запрос").attachments("Прайс.docx")
                .files(new MailFile("Прайс.docx", DOCX, (long) docx.length, docx)).build();
        MailIngestWriter.WriteResult r2 = writer.write(kp, 1L);
        assertThat(inboundFiles.findInfoByInboundIds(List.of(r2.inboundId()))).isEmpty();      // ответ поставщика — свой путь
    }

    @Test
    void telegramLine_forPriceLikeLetter() {
        ParsedMail m = TestMails.mail().subject("Прайс").attachments("Прайс.pdf").build();
        Classification c = MailClassifier.classify(m, new ClassifierRules("zakup@westmed.kz", "info@westmed.kz", false));
        String text = MailNotificationComposer.compose(m, c, null, null, MailIngestWriter.PRICE_LINE,
                new ComposeContext("zakup@westmed.kz", Market.KZ, "", OffsetDateTime.now())).text();
        assertThat(text).contains("📒 Похоже на прайс-лист — «Занести как прайс» во «Входящих»");
    }

    @Test
    void fromInbound_supplierBySender_or400_otherMarket404() throws Exception {
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "Из письма");
        d.setEmail("sales@zz-zavod.kz, zz-prices@example.kz");
        distributors.saveAndFlush(d);
        InboundEmail e = inbound.saveAndFlush(InboundEmail.builder().mailbox("zakup@westmed.kz").fromAddress("ZZ <zz-prices@example.kz>")
                .subject("Прайс").receivedAt(OffsetDateTime.now()).type(InboundType.UNMATCHED).status(InboundStatus.NEW).build());
        byte[] xlsx = PriceListFiles.axionLikeXlsx();
        InboundEmailFile f = inboundFiles.saveAndFlush(InboundEmailFile.builder().inboundEmailId(e.getId()).fileName("Прайс.xlsx")
                .mimeType("application/octet-stream").sizeBytes((long) xlsx.length).sha256("zz" + System.nanoTime()).data(xlsx)
                .priceLike(true).build());
        InboundEmail stranger = inbound.saveAndFlush(InboundEmail.builder().mailbox("zakup@westmed.kz").fromAddress("x@nowhere.kz")
                .subject("Прайс").receivedAt(OffsetDateTime.now()).type(InboundType.UNMATCHED).status(InboundStatus.NEW).build());
        InboundEmailFile g = inboundFiles.saveAndFlush(InboundEmailFile.builder().inboundEmailId(stranger.getId()).fileName("Прайс.xlsx")
                .sizeBytes((long) xlsx.length).sha256("zz" + System.nanoTime()).data(xlsx).priceLike(true).build());
        MarketContext.clear();
        mvc.perform(get("/api/inbound").header("X-Market", "KZ"))
                .andExpect(jsonPath("$[?(@.id == " + e.getId() + ")].priceFiles[0].name").value("Прайс.xlsx"));
        mvc.perform(post("/api/price-lists/from-inbound/" + f.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.distributorId").value(d.getId()))
                .andExpect(jsonPath("$.rows.length()").value(4));
        mvc.perform(post("/api/price-lists/from-inbound/" + g.getId()).header("X-Market", "KZ"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Выберите поставщика")));
        mvc.perform(post("/api/price-lists/from-inbound/" + g.getId()).header("X-Market", "KZ").contentType("application/json")
                .content("{\"distributorId\":" + d.getId() + "}")).andExpect(status().isOk());
        mvc.perform(post("/api/price-lists/from-inbound/" + f.getId()).header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(get("/api/price-lists/counts").header("X-Market", "KZ"))
                .andExpect(jsonPath("$['" + d.getId() + "']").value(1));                // тот же файл — один прайс
    }

    @Test
    void fromInbound_twoSuppliersWithSenderAddress_400() throws Exception {
        for (String n : List.of("Двойник 1", "Двойник 2")) {
            Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, n);
            d.setEmail("twin@example.kz");
            distributors.saveAndFlush(d);
        }
        InboundEmail e = inbound.saveAndFlush(InboundEmail.builder().mailbox("zakup@westmed.kz").fromAddress("twin@example.kz")
                .subject("Прайс").receivedAt(OffsetDateTime.now()).type(InboundType.UNMATCHED).status(InboundStatus.NEW).build());
        byte[] xlsx = PriceListFiles.axionLikeXlsx();
        InboundEmailFile f = inboundFiles.saveAndFlush(InboundEmailFile.builder().inboundEmailId(e.getId()).fileName("Прайс.xlsx")
                .sizeBytes((long) xlsx.length).sha256("zz" + System.nanoTime()).data(xlsx).priceLike(true).build());
        MarketContext.clear();
        mvc.perform(post("/api/price-lists/from-inbound/" + f.getId()).header("X-Market", "KZ"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Выберите поставщика")));
    }
}
```

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.PriceListInboundTest' 2>&1 | tail -20`
Expected: ошибка компиляции — нет `MailIngestWriter.priceFiles`, `PRICE_LINE`.

- [ ] **Step 2: Приём zakup@ хранит файлы писем «прочее»**

`MailIngestWriter`:

```java
    /** Файлы писем «прочее» до 15 МБ — «Занести как прайс» (спека 2026-10-23 §5.1). */
    static final long PRICE_FILE_MAX = 15L * 1024 * 1024;
    private static final Pattern PRICE_WORDS = Pattern.compile("(?iu)прайс|прейскурант|price");
    public static final String PRICE_LINE = "📒 Похоже на прайс-лист — «Занести как прайс» во «Входящих»";

    private InboundEmailFileRepository inboundFiles;

    /** Нет бина (тесты, собранные руками) — файлы писем «прочее» не хранятся. */
    @Autowired(required = false)
    public void setInboundFiles(InboundEmailFileRepository inboundFiles) {
        this.inboundFiles = inboundFiles;
    }

    /** Excel, Word, PDF письма с байтами, не больше 15 МБ; «похоже на прайс» — по имени файла или теме письма. */
    public static List<InboundEmailFile> priceFiles(ParsedMail m) {
        boolean subject = m.subject() != null && PRICE_WORDS.matcher(m.subject()).find();
        List<InboundEmailFile> out = new ArrayList<>();
        for (MailFile f : m.files()) {
            if (!f.stored() || f.content().length > PRICE_FILE_MAX) continue;
            ReplyFileKind kind = SupplierReplyFiles.kind(f.name(), f.mimeType(), f.content());
            if (kind != ReplyFileKind.EXCEL && kind != ReplyFileKind.WORD && kind != ReplyFileKind.PDF) continue;
            String name = SupplierReplyFiles.fileName(f.name());
            out.add(InboundEmailFile.builder().fileName(name).mimeType(cut(f.mimeType(), 100)).sizeBytes((long) f.content().length)
                    .sha256(SupplierReplyFiles.sha256(f.content())).data(f.content())
                    .priceLike(subject || PRICE_WORDS.matcher(name).find()).build());
        }
        return out;
    }
```

В `write(ParsedMail, List<PreparedFile>, long)` — перед сборкой уведомления:

```java
        List<InboundEmailFile> priceFiles = c.mailClass() == MailClass.UNMATCHED && inboundFiles != null ? priceFiles(m) : List.of();
        String extra = c.mailClass() == MailClass.SUPPLIER_RESPONSE ? fileLine(fileOutcome)
                : priceFiles.stream().anyMatch(InboundEmailFile::isPriceLike) ? PRICE_LINE : null;
```

вызов `MailNotificationComposer.compose(…, fileLine(fileOutcome), …)` → `compose(…, extra, …)`; после
`inboundRepo.save(e);`:

```java
        for (InboundEmailFile f : priceFiles) {
            f.setInboundEmailId(e.getId());
            inboundFiles.save(f);
        }
```

`MailNotificationComposer` — `case CLIENT_REQUEST, UNMATCHED, OWN, SITE_NOTIFICATION -> other(m, fileLine, ctx);` и в
`other(ParsedMail m, String fileLine, ComposeContext ctx)` после строки «Вложения»:

```java
        if (fileLine != null && !fileLine.isBlank()) head.append(line(fileLine, 300)).append('\n');
```

- [ ] **Step 3: «Входящие» отдают файлы, «Занести как прайс»**

`InboundEmailResponse`:

```java
    /** Файлы письма «прочее» для «Занести как прайс» (спека 2026-10-23 §5.1): без байтов. */
    private List<PriceFile> priceFiles = List.of();

    public record PriceFile(Long id, String name, boolean priceLike) {}
```

`InboundController` — поле `InboundEmailFileRepository inboundFiles` (конструктор); в `list` после имён файлов ответов:

```java
        Map<Long, List<InboundEmailResponse.PriceFile>> price = new HashMap<>();
        List<Long> other = rows.stream().filter(e -> e.getType() == InboundType.UNMATCHED).map(InboundEmail::getId).toList();
        if (!other.isEmpty()) {
            for (Object[] r : inboundFiles.findInfoByInboundIds(other)) {
                price.computeIfAbsent(((Number) r[1]).longValue(), k -> new ArrayList<>())
                        .add(new InboundEmailResponse.PriceFile(((Number) r[0]).longValue(), (String) r[2], Boolean.TRUE.equals(r[3])));
            }
        }
```

и у строки — `r.setPriceFiles(price.getOrDefault(e.getId(), List.of()));`.

`PriceListService` — поля `InboundEmailFileRepository inboundFiles`, `InboundEmailRepository inbound` (конструктор) и:

```java
    /** «Занести как прайс» (спека §5.1): файл письма своего рынка; поставщик — выбранный, иначе по адресу отправителя. */
    public Long fromInbound(Long fileId, Long distributorId, String user) {
        InboundEmailFile f = inboundFiles.findById(fileId).orElseThrow(() -> new NotFoundException("Файл письма не найден: " + fileId));
        InboundEmail e = inbound.findById(f.getInboundEmailId()).orElse(null);
        if (e == null || e.getMarket() != MarketContext.get()) throw new NotFoundException("Файл письма не найден: " + fileId);
        Distributor d = distributorId != null ? supplier(distributorId) : bySender(e.getFromAddress());
        return create(d, f.getFileName(), f.getMimeType(), f.getData(), e.getId(), user);
    }

    private static final Pattern ADDRESS = Pattern.compile("[^\\s<>\"',;]+@[^\\s<>\"',;]+");

    /** Поставщик рынка, у которого среди адресов карточки есть адрес отправителя; не один или ни одного — 400. */
    private Distributor bySender(String from) {
        Matcher m = ADDRESS.matcher(from == null ? "" : from);
        String addr = m.find() ? m.group().toLowerCase(Locale.ROOT) : null;
        if (addr != null) {
            List<Distributor> found = distributors.findAll().stream().filter(d -> d.getMarket() == MarketContext.get())
                    .filter(d -> addresses(d.getEmail()).contains(addr)).toList();
            if (found.size() == 1) return found.get(0);
        }
        throw new BadRequestException("Выберите поставщика — по адресу отправителя он не нашёлся");
    }

    private static List<String> addresses(String field) {
        try {
            return EmailAddresses.parse(field).stream().map(a -> a.toLowerCase(Locale.ROOT)).toList();
        } catch (BadRequestException e) {
            return List.of();                                   // старый кривой адрес в карточке — не наш отправитель
        }
    }

    /** Сколько прайсов у поставщиков рынка — ссылка «Прайсы: N ›» в «Дистрибьюторах». */
    @Transactional(readOnly = true)
    public Map<Long, Long> counts() {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] r : lists.countByDistributor()) out.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        return out;
    }
```

`PriceListController`:

```java
    public record FromInbound(Long distributorId) {}

    @PostMapping("/from-inbound/{fileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PriceListView fromInbound(@PathVariable Long fileId, @RequestBody(required = false) FromInbound in) {
        return views.view(service.fromInbound(fileId, in == null ? null : in.distributorId(), CurrentUser.name()));
    }

    @GetMapping("/counts")
    public Map<Long, Long> counts() {
        return service.counts();
    }
```

- [ ] **Step 4: Прогон — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.pricelist.*' --tests 'com.vladoose.nir.service.mail.*' --tests '*Inbound*' 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 5: Мутации**

1. Хранить файлы и у ответов поставщиков (условие `UNMATCHED` убрать) → `unmatchedLetter_storesFiles_otherKindsDoNot` краснеет.
2. `priceLike` только по имени файла → `priceFiles_onlyOfficeAndPdf_priceLikeByNameOrSubject` краснеет.
3. `fromInbound` без сверки рынка письма → `fromInbound_supplierBySender_or400_otherMarket404` краснеет.
4. `bySender` берёт первого из нескольких (`found.size() == 1` → `!found.isEmpty()`) → `fromInbound_twoSuppliersWithSenderAddress_400`
   краснеет.
Вернуть копиями, `./gradlew compileJava`.

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java && git commit -m "feat(price-lists): «Занести как прайс» из «Входящих» zakup@, строка «📒» в Telegram, число прайсов у поставщиков

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 12: Фронт — «Курсы валют», цена в чужой валюте (ответ поставщика, таблица цен, закупка КП), «по курсу сегодня», «Пересчитать»

**Files:**
- Create: `frontend/src/app/shared/rates.ts`, `frontend/src/app/pages/currency-rates/currency-rates.component.ts`
- Modify: `frontend/src/app/app.routes.ts`, `frontend/src/app/layout/layout.component.ts` (пункт «Система → Курсы валют»),
  `frontend/src/app/app.config.ts` (`LucideCoins`, `LucideReceiptText` — в обоих списках),
  `frontend/src/app/services/api.service.ts`, `frontend/src/app/shared/supplier-reply.ts`,
  `frontend/src/app/pages/price-requests/price-request-reply.component.ts`, `frontend/src/app/shared/private-request.ts`,
  `frontend/src/app/pages/private-requests/pr-prices.component.ts`, `frontend/src/app/shared/client-offer.ts`,
  `frontend/src/app/pages/client-offers/client-offer-items.component.ts`,
  `frontend/src/app/pages/client-offers/client-offer-editor.component.ts`

**Interfaces:**
- Consumes: `GET / PUT /api/currency-rates` (задача 2), поля `Rate.our / today / todayDate` и `Position.currency /
  original / rate / today` экрана сверки (задача 3), `PUT /responses` с `responseCurrency` / `responseOriginal` (задача 3),
  `Cell.currency / original / rate / today` таблицы цен (задача 3), поля закупки строки КП и `ourRates` / `todayRates` /
  `totals.purchaseToday / profitToday` (задача 4).
- Produces: `shared/rates.ts` — `RatesView`, `RateRow`, `RateToday`, `curShort`, `rateText`, `priceCurrencies`,
  `ourRatesOf`, `dayMonth`; `ApiService.getCurrencyRates()`, `saveCurrencyRates(rates)`.

- [ ] **Step 1: Типы и путь**

`frontend/src/app/shared/rates.ts`:

```ts
/** Курсы валют (спека 2026-10-23 §4): наш курс — для закупки в валюте рынка и наценки; курс сегодня — НБ РК. */
export interface RateToday { rate: number; date: string; stale: boolean; }
export interface RateRow {
  currency: string; ourRate: number | null; updatedBy: string | null; updatedAt: string | null;
  today: RateToday | null; marginPct: number | null;
}
export interface RatesView { market: string; marketCurrency: string; todayEnabled: boolean; rates: RateRow[]; }

const SHORT: Record<string, string> = { KZT: 'тг', RUB: 'руб.', USD: '$', EUR: '€', CNY: 'юань' };

/** «руб.», «тг», «$» — рядом с числом (как ExecutionTexts.currency на сервере). */
export function curShort(code: string | null | undefined): string {
  return code ? SHORT[code] ?? code : '';
}

const RATE = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 6, useGrouping: false });

/** Курс без группировки, до 6 знаков: «5,48», «6». */
export function rateText(v: number | null | undefined): string {
  return v == null ? '' : RATE.format(v);
}

/** Валюты переключателя цены: валюта рынка, затем валюты с нашим курсом (спека §10). */
export function priceCurrencies(market: string, ours: Record<string, number> | null | undefined): string[] {
  return [market, ...Object.keys(ours ?? {}).filter(c => c !== market)];
}

/** Наш курс по валютам из ответа /api/currency-rates. */
export function ourRatesOf(v: RatesView | null): Record<string, number> {
  const out: Record<string, number> = {};
  for (const r of v?.rates ?? []) if (r.ourRate != null) out[r.currency] = r.ourRate;
  return out;
}

/** «23.10» из «2026-10-23». */
export function dayMonth(iso: string | null | undefined): string {
  return iso ? iso.slice(8, 10) + '.' + iso.slice(5, 7) : '';
}
```

`ApiService` (импорт `import type { RatesView } from '../shared/rates';`):

```ts
  getCurrencyRates(): Observable<RatesView> {
    return this.http.get<RatesView>(`${this.base}/currency-rates`);
  }
  saveCurrencyRates(rates: { currency: string; ourRate: number | null }[]): Observable<RatesView> {
    return this.http.put<RatesView>(`${this.base}/currency-rates`, { rates });
  }
```

`app.routes.ts` — рядом с `reply-templates`:

```ts
      { path: 'currency-rates', canActivate: [adminGuard],
        loadComponent: () => import('./pages/currency-rates/currency-rates.component').then(m => m.CurrencyRatesComponent) },
```

`layout.component.ts` — в группе «Система» после «Шаблонов ответов»:

```html
            <a *ngIf="auth.isAdmin()" routerLink="/currency-rates" routerLinkActive="active">
              <svg lucideIcon="coins" [size]="16"></svg> Курсы валют
            </a>
```

`app.config.ts` — `LucideCoins, LucideReceiptText` в импорте и в `provideLucideIcons(...)`.

- [ ] **Step 2: Страница «Курсы валют»**

```ts
import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { MarketService } from '../../services/market.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { parseNum } from '../../shared/client-offer';
import { curShort, dayMonth, rateText } from '../../shared/rates';
import type { RatesView } from '../../shared/rates';

/** «Система → Курсы валют» (спека 2026-10-23 §4.1–4.2): наш курс по валютам рынка и курс НБ РК рядом. */
@Component({
  selector: 'app-currency-rates',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="cr-page">
      <h2>Курсы валют</h2>
      <p class="subtitle">Рынок: <b>{{ marketLabel }}</b>. <b>Наш курс</b> переводит закупку в чужой валюте в {{ short(view?.marketCurrency) }}
        — от неё считается цена клиенту. Ставьте с запасом: клиент может купить позже, а курс — вырасти.
        <ng-container *ngIf="view?.todayEnabled"><b>Курс сегодня</b> — Национальный банк РК: по нему видны настоящая закупка и прибыль.</ng-container></p>
      <p class="cr-note" *ngIf="loading">Загружаю…</p>
      <div class="cr-list" *ngIf="view as v">
        <div class="cr-row" *ngFor="let r of v.rates">
          <span class="cr-cur">1 {{ short(r.currency) }}</span>
          <label class="cr-our">наш курс
            <input inputmode="decimal" [(ngModel)]="draft[r.currency]" placeholder="не задан"
                   [attr.aria-label]="'Наш курс: 1 ' + short(r.currency) + ' в ' + short(v.marketCurrency)" />
            {{ short(v.marketCurrency) }}</label>
          <span class="cr-today" *ngIf="v.todayEnabled">
            <ng-container *ngIf="r.today as t; else noToday">сегодня {{ rate(t.rate) }}
              <span [class.cr-stale]="t.stale">· {{ t.stale ? 'курс от ' : '' }}{{ day(t.date) }}</span></ng-container>
            <ng-template #noToday><span class="cr-muted">курса сегодня нет</span></ng-template>
          </span>
          <span class="cr-margin" *ngIf="r.marginPct != null" [class.cr-neg]="r.marginPct < 0">
            запас {{ r.marginPct > 0 ? '+' : '' }}{{ pct(r.marginPct) }} %</span>
          <span class="cr-who" *ngIf="r.updatedBy">{{ r.updatedBy }} · {{ day(r.updatedAt) }}</span>
        </div>
        <p class="cr-note" *ngIf="!v.todayEnabled">Курс сегодня (НБ РК) — только для Казахстана: на этом рынке закупка считается по нашему курсу.</p>
        <div class="cr-actions">
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .cr-page { max-width: 820px; display: flex; flex-direction: column; gap: 10px; }
    .cr-page h2 { margin: 0; }
    .cr-note { margin: 0; font-size: 13px; color: var(--text-muted); }
    .cr-list { display: flex; flex-direction: column; gap: 6px; }
    .cr-row { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: center; padding: 10px 12px; border: 1px solid var(--border);
              border-radius: 10px; background: var(--surface); }
    .cr-cur { font-weight: 600; min-width: 64px; }
    .cr-our { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-muted); }
    .cr-our input { width: 110px; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                    color: var(--text); font-size: 14px; }
    .cr-today, .cr-who { font-size: 13px; color: var(--text-muted); }
    .cr-stale { color: var(--warn-text); }
    .cr-muted { color: var(--text-muted); }
    .cr-margin { font-size: 12px; padding: 2px 9px; border-radius: 999px; color: var(--success-text);
                 background: color-mix(in srgb, var(--success) 15%, transparent); }
    .cr-margin.cr-neg { color: var(--danger-text); background: color-mix(in srgb, var(--danger) 15%, transparent); }
    .cr-actions { margin-top: 4px; }
    @media (max-width: 900px) {
      .cr-our input { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class CurrencyRatesComponent implements OnInit {
  view: RatesView | null = null;
  loading = true;
  saving = false;
  draft: Record<string, string> = {};
  readonly short = curShort;
  readonly rate = rateText;
  readonly day = (iso: string | null) => (iso ? dayMonth(iso.slice(0, 10)) + '.' + iso.slice(0, 4) : '');

  constructor(private api: ApiService, private market: MarketService, private notify: NotificationService,
              private cdr: ChangeDetectorRef) {}

  get marketLabel(): string { return this.market.value === 'KZ' ? 'West-Med (KZ)' : 'Регион-Мед (РФ)'; }

  ngOnInit() {
    this.api.getCurrencyRates().subscribe({
      next: v => this.show(v),
      error: e => { this.loading = false; this.notify.error('Курсы не загрузились: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }

  pct(v: number): string { return String(v).replace('.', ','); }

  private show(v: RatesView) {
    this.view = v;
    this.loading = false;
    this.draft = {};
    for (const r of v.rates) this.draft[r.currency] = r.ourRate == null ? '' : rateText(r.ourRate);
    this.cdr.markForCheck();
  }

  save() {
    const v = this.view;
    if (!v) return;
    const rates: { currency: string; ourRate: number | null }[] = [];
    for (const r of v.rates) {
      const text = (this.draft[r.currency] ?? '').trim();
      const n = text === '' ? null : parseNum(text);
      if (text !== '' && (n == null || n <= 0 || n > 100000 || !/^\d+([.,]\d{1,6})?$/.test(text.replace(/\s/g, '')))) {
        this.notify.error(`Курс ${curShort(r.currency)} — число больше нуля, до 100 000 и не больше 6 знаков после запятой`);
        return;
      }
      rates.push({ currency: r.currency, ourRate: n });
    }
    this.saving = true;
    this.api.saveCurrencyRates(rates).subscribe({
      next: nv => { this.saving = false; this.show(nv); this.notify.success('Курсы сохранены'); },
      error: e => { this.saving = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
```

- [ ] **Step 3: «Ответ поставщика» — цена в чужой валюте и курсы рядом**

`shared/supplier-reply.ts`:
- `ReplyRole` — ещё `'REG'`; в `REPLY_ROLE_OPTIONS` перед «Примечанием» — `{ v: 'REG', l: 'РУ' }` (решение плана 13).
- `ReplyPosition` — `currency: string | null; original: number | null; rate: number | null; today: number | null;`.
- `ReplyRate` — `our: number | null; today: number | null; todayDate: string | null;`.

`price-request-reply.component.ts`:
- поле `rates: RatesView | null = null;` и в `ngOnInit` — `this.api.getCurrencyRates().subscribe({ next: r => { this.rates = r;
  this.cdr.markForCheck(); }, error: () => {} });` (без курсов — переключатель только с валютой рынка);
- выбранная валюта позиции — `private curFor = new Map<number, string>();`,
  `cur(p: ReplyPosition): string { return this.curFor.get(p.itemId) ?? p.currency ?? this.view?.head.currency ?? ''; }`,
  `curs(): string[] { return priceCurrencies(this.view?.head.currency ?? '', ourRatesOf(this.rates)); }`;
- блок курса (`.rate`, после поля курса) — строка:

```html
              <span class="rate-h" *ngIf="rt.our != null">наш курс {{ rateText(rt.our) }}<ng-container *ngIf="rt.today != null">
                · сегодня {{ rateText(rt.today) }}<ng-container *ngIf="rt.todayDate"> ({{ dm(rt.todayDate) }})</ng-container></ng-container></span>
```

- поле цены позиции (`.p-ctl`) — вместо прежнего `<label>Цена за ед. …</label>`:

```html
              <label>Цена за ед.
                <span class="price-cur">
                  <input inputmode="decimal" [value]="num(cur(p) === p.currency ? p.original : p.price)" (change)="savePrice(p, $event)"
                         [disabled]="!admin" placeholder="—" />
                  <select *ngIf="admin && curs().length > 1" [value]="cur(p)" (change)="setCur(p, $event)" aria-label="Валюта цены">
                    <option *ngFor="let c of curs()" [value]="c" [selected]="c === cur(p)">{{ short(c) }}</option>
                  </select>
                </span>
              </label>
              <span class="conv" *ngIf="p.currency && p.original != null">= {{ p.price | money:2 }} по нашему курсу {{ rateText(p.rate) }}<ng-container
                    *ngIf="p.today != null"> · сегодня {{ p.today | money:2 }}</ng-container></span>
```

- методы:

```ts
  readonly short = curShort;
  readonly dm = dayMonth;

  setCur(p: ReplyPosition, ev: Event) {
    this.curFor.set(p.itemId, (ev.target as HTMLSelectElement).value);
    this.cdr.markForCheck();
  }

  savePrice(p: ReplyPosition, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const text = input.value.trim();
    const v = text === '' ? null : parseNum(text);
    if (text !== '' && v == null) { this.notify.error('Цена — число, например 45 000 или 45000,50'); input.value = numText(p.price); return; }
    const market = this.view?.head.currency ?? null;
    const cur = this.cur(p);
    if (cur && market && cur !== market && v != null) {
      this.put([{ itemId: p.itemId, responsePrice: null, responseNote: p.note, responseCurrency: cur, responseOriginal: v }]);
    } else {
      this.put([{ itemId: p.itemId, responsePrice: v, responseNote: p.note, responseCurrency: market }]);
    }
  }
```

(после ответа `put` перечитывает вид — `curFor` очищать не нужно: позиция приходит со своей валютой.) Стили: `.price-cur {
display: flex; gap: 4px; } .price-cur select { width: auto; } .conv { font-size: 12px; color: var(--text-muted); }`.

- [ ] **Step 4: Таблица цен заявки — ячейка в рублях**

`shared/private-request.ts` — у `PriceCell`: `currency: string | null; original: number | null; rate: number | null; today:
number | null;`. `pr-prices.component.ts`:
- `cellText(c)` — у ячейки с ценой в чужой валюте: `${money(c.original)} ${curShort(c.currency)} → ${money(c.price)}` (прежний
  текст — для остальных);
- окно ячейки: в `edit(l, c)` — `ed.currency = c.currency ?? marketCode`, `ed.price = numText(c.currency ? c.original : c.price)`;
  рядом с полем цены — `<select *ngIf="!readonly && curs().length > 1" [(ngModel)]="ed.currency">` (валюты — `priceCurrencies`
  от `getCurrencyRates()` при открытии таблицы, как на экране сверки); под полем — «= 900 000 ₸ по нашему курсу 6 · сегодня
  822 000 ₸» при `ed.cell.currency`;
- `saveCell()` — тело строки:

```ts
    const market = this.marketCode;
    const body = ed.currency && ed.currency !== market && price != null
      ? { itemId: ed.cell.itemId, responsePrice: null, responseNote: ed.note.trim() || null, responseCurrency: ed.currency, responseOriginal: price }
      : { itemId: ed.cell.itemId, responsePrice: price, responseNote: ed.note.trim() || null, responseCurrency: market };
```

(`marketCode` — `this.market.value === 'KZ' ? 'KZT' : 'RUB'` из `MarketService`.)

- [ ] **Step 5: Строка КП — закупка в чужой валюте**

`shared/client-offer.ts`:
- `OfferItem` — `purchaseCurrency?: string | null; purchaseOriginal?: number | null; purchaseRate?: number | null;
  priceListItemId?: number | null; priceListId?: number | null; priceListLabel?: string | null;` и UI-поле
  `_cur?: string | null;` (выбор валюты до ввода цены, на сервер не уходит — Jackson его пропустит);
- `OfferTotals` — `purchaseToday: number | null; profitToday: number | null;`;
- `ClientOffer` — `ourRates?: Record<string, number>; todayRates?: Record<string, { rate: number; date: string }>;`.

`client-offer-items.component.ts`:
- у поля «Закупка» (`.buy-wrap`) — переключатель валюты справа и подпись под полем:

```html
                <span class="buy-wrap"><input inputmode="decimal" [value]="numText(isForeign(it) ? it.purchaseOriginal : it.purchasePrice)"
                       (change)="setPurchase(it, $event)" [disabled]="readonly" placeholder="—" [class.with-unv]="it.purchaseUnverified"
                       [attr.title]="it.purchaseUnverified ? UNV_HINT : null"
                       [attr.aria-label]="'Цена закупки за единицу, как в счёте поставщика, строка ' + (i + 1) + (it.purchaseUnverified ? ', не сверена' : '')" />
                  <span class="unv" *ngIf="it.purchaseUnverified" aria-hidden="true">💡</span></span>
                <select class="buy-cur" *ngIf="!readonly && curs().length > 1" [value]="curOf(it)" (change)="setPurchaseCur(it, $event)"
                        [attr.aria-label]="'Валюта закупки, строка ' + (i + 1)">
                  <option *ngFor="let c of curs()" [value]="c" [selected]="c === curOf(it)">{{ short(c) }}</option>
                </select>
                <span class="buy-conv" *ngIf="isForeign(it) && it.purchaseOriginal != null">= {{ numText(it.purchasePrice) }} по курсу {{ rateText(it.purchaseRate) }}</span>
```

- методы:

```ts
  readonly short = curShort;
  readonly rateText = rateText;

  private get market(): string { return this.offer.currency; }
  curs(): string[] { return priceCurrencies(this.market, this.offer.ourRates); }
  curOf(it: OfferItem): string { return it._cur ?? it.purchaseCurrency ?? this.market; }
  isForeign(it: OfferItem): boolean { return this.curOf(it) !== this.market; }

  /** Смена валюты: в валюту рынка — рубли снимаются, закупка остаётся; в чужую — ждём цену (без неё сервер ответил бы 400). */
  setPurchaseCur(it: OfferItem, ev: Event) {
    const c = (ev.target as HTMLSelectElement).value;
    if (c === this.market) {
      it._cur = null;
      if (it.purchaseCurrency) { it.purchaseCurrency = null; it.purchaseOriginal = null; it.purchaseRate = null; this.unlink(it); this.emit(); }
    } else {
      it._cur = c;
    }
  }
```

  `setPurchase` — в ветке чужой валюты:

```ts
    if (this.isForeign(it)) {
      const cur = this.curOf(it);
      if (v !== it.purchaseOriginal || cur !== it.purchaseCurrency) this.unlink(it);
      const rate = cur === it.purchaseCurrency && it.purchaseRate != null ? it.purchaseRate : this.offer.ourRates?.[cur] ?? null;
      it.purchaseCurrency = v == null ? null : cur;
      it.purchaseOriginal = v;
      it.purchaseRate = v == null ? null : rate;
      it.purchasePrice = v == null || rate == null ? null : Math.round(v * rate * 100) / 100;   // сервер пересчитает сам
      it._cur = v == null ? cur : null;
      input.value = numText(v);
      this.emit();
      return;
    }
```

  (`unlink(it)` — прежние строки «ручная правка снимает ссылку»: `priceRequestItemId`, `purchaseUnverified`,
  `replyRequestId`, плюс `priceListItemId`, `priceListId`, `priceListLabel`; прежняя ветка рынка зовёт его же.)
- `duplicateRow` — копия держит валюту, исходную цену и курс (как сервер, задача 4), ссылки снимает (как раньше) и
  `priceListItemId` тоже.

- [ ] **Step 6: Редактор КП — «по курсу сегодня» и «Пересчитать»**

`client-offer-editor.component.ts`:
- в карточке «Маржа» после «Прибыль»:

```html
                <ng-container *ngIf="o.totals.purchaseToday != null">
                  <div><dt>Закупка по курсу сегодня</dt><dd>{{ money(o.totals.purchaseToday) }}</dd></div>
                  <div><dt>Прибыль по курсу сегодня</dt><dd [class.neg]="(o.totals.profitToday ?? 0) < 0">{{ money(o.totals.profitToday) }}</dd></div>
                </ng-container>
```

  и под `dl` — `<p class="hint" *ngIf="todayLine(o) as t">{{ t }}</p>` (`todayLine`: «Курс сегодня (НБ РК): руб. 5,48 на 23.10»
  по `o.todayRates`);
- плашка «Пересчитать» — первой в `pane-edit` рядом с плашкой новых цен:

```html
          <div class="np-banner" *ngIf="admin && o.status === 'DRAFT' && staleRates(o) as s">
            <span>Наш курс {{ s.label }} — в {{ s.count }} {{ s.count === 1 ? 'строке' : 'строках' }} закупка по прежнему курсу</span>
            <button type="button" class="btn btn-line" (click)="recalcRates(o)">Пересчитать</button>
          </div>
```

```ts
  /** Строки в чужой валюте, у которых курс строки ≠ нашему текущему (спека §4.5): сам курс без «Пересчитать» не меняется. */
  staleRates(o: ClientOffer): { label: string; count: number } | null {
    const ours = o.ourRates ?? {};
    const stale = o.items.filter(it => it.kind === 'ITEM' && it.purchaseCurrency && it.purchaseRate != null
      && ours[it.purchaseCurrency] != null && ours[it.purchaseCurrency] !== it.purchaseRate);
    if (!stale.length) return null;
    const c = stale[0].purchaseCurrency!;
    return { label: `${curShort(c)} сменился: ${rateText(stale[0].purchaseRate)} → ${rateText(ours[c])}`, count: stale.length };
  }

  recalcRates(o: ClientOffer) {
    const ours = o.ourRates ?? {};
    for (const it of o.items) {
      const r = it.purchaseCurrency ? ours[it.purchaseCurrency] : undefined;
      if (it.kind !== 'ITEM' || r == null || it.purchaseOriginal == null || it.purchaseRate === r) continue;
      it.purchaseRate = r;
      it.purchasePrice = Math.round(it.purchaseOriginal * r * 100) / 100;
    }
    this.changed();   // обычное автосохранение: сервер примет текущий наш курс и пересчитает закупку
  }
```

- `merge(o, r)` — в цикле строк: `o.ourRates = r.ourRates; o.todayRates = r.todayRates;` до цикла и в строке:

```ts
        // закупку в чужой валюте считает сервер — берём его число, если экран не сменил исходную цену за время запроса
        if ((it.purchaseCurrency ?? null) === (s.purchaseCurrency ?? null) && (it.purchaseOriginal ?? null) === (s.purchaseOriginal ?? null)
            && s.purchaseCurrency) {
          it.purchasePrice = s.purchasePrice;
          it.purchaseRate = s.purchaseRate ?? null;
        }
        if ((it.priceListItemId ?? null) === (s.priceListItemId ?? null)) {
          it.priceListId = s.priceListId ?? null;
          it.priceListLabel = s.priceListLabel ?? null;
        }
```

- `appliedKey` — `(it.purchasePrice ?? '') + '|' + (it.priceRequestItemId ?? '') + '|' + (it.priceListItemId ?? '')`;
  `mergeApplied` — в ветке «подстановка сменила» ещё `purchaseCurrency`, `purchaseOriginal`, `purchaseRate`,
  `priceListItemId`, `priceListId`, `priceListLabel` из `s`;
- `checkNewPrices` — без условия `!o.tenderId` (решение плана 17).

- [ ] **Step 7: Сборка и проверка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -15`
Expected: сборка без ошибок; начальный бандл ≤ 1,5 МБ (страница курсов — ленивый чанк).

Живьём (бэкенд + `ng serve`, 1280 и 390): «Система → Курсы валют» — наш курс руб. 6, сегодня (вставить строку
`official_rate` SQL-ом, если Нацбанк выключен: `insert into official_rate (source, currency, rate_date, rate) values ('NBRK',
'RUB', current_date, 5.48);`), запас +9,5 %; экран сверки — цена 150 000 руб. → «= 900 000 ₸ по нашему курсу 6 · сегодня
822 000 ₸»; таблица цен заявки — ячейка «150 000 руб. → 900 000»; КП — закупка в руб., «Закупка по курсу сегодня»;
наш курс 6,5 → плашка «Пересчитать» → закупка 975 000. Уборка — `delete from official_rate where source = 'NBRK' and
rate = 5.48; delete from currency_rate;` (если до проверки курсов не было).

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src && git commit -m "feat(rates): страница «Курсы валют», цена в рублях на экране сверки, в таблице цен и в закупке КП, «по курсу сегодня», «Пересчитать»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 13: Фронт — «Прайсы поставщиков» (список, поиск, загрузка), экран проверки прайса, «Дистрибьюторы», «Входящие»

**Files:**
- Create: `frontend/src/app/shared/price-list.ts`, `frontend/src/app/shared/price-hint.ts`,
  `frontend/src/app/pages/price-lists/price-lists.component.ts`, `frontend/src/app/pages/price-lists/price-list-page.component.ts`
- Modify: `frontend/src/app/app.routes.ts`, `frontend/src/app/layout/layout.component.ts` (пункт «Каталог → Прайсы
  поставщиков»), `frontend/src/app/services/api.service.ts`, `frontend/src/app/pages/distributors/distributors.component.ts`,
  `frontend/src/app/pages/inbound/inbound.component.ts`

**Interfaces:**
- Consumes: пути `/api/price-lists…` (задачи 7, 8, 11); `shared/rates.ts` (задача 12); `ConfirmService.ask`; `saveBlob`,
  `responseFile`; `MarketService`.
- Produces: `shared/price-hint.ts` — типы `PriceHint`, `PriceHintRow`, `PriceHistoryEntry` и `hintLine(hint, marketCurrency)`;
  `shared/price-list.ts` — `PriceListView`, `PriceListRow`, `PriceListListItem`, `PriceListColumn`, `PriceLinkCandidates`,
  `PriceListField`, `PriceRowField`; методы `ApiService`: `getPriceLists(supplier?)`, `uploadPriceList(distributorId, file)`,
  `getPriceList(id)`, `patchPriceList(id, field, value)`, `activatePriceList(id)`, `deletePriceList(id)`,
  `addPriceListRow(id)`, `patchPriceListRow(id, rowId, field, value)`, `deletePriceListRow(id, rowId)`,
  `downloadPriceListFile(id)`, `searchPriceLists(q)`, `priceHints(texts)`, `getPriceHistory(rowId)`,
  `getPriceLinkCandidates(q)`, `priceListFromInbound(fileId, distributorId?)`, `getPriceListCounts()`.

- [ ] **Step 1: Типы и методы**

`frontend/src/app/shared/price-hint.ts` (его берёт и не ленивая страница «Тендеры» — только типы и одна функция строки):

```ts
/** Подсказка «📒 по прайсу» (спека 2026-10-23 §6): строки текущих прайсов поставщиков — лучшая первой. */
export interface PriceHintRow {
  rowId: number; priceListId: number; distributorId: number; supplierName: string; manufacturer: boolean;
  section: string | null; name: string; model: string | null; article: string | null; listDate: string | null;
  validTo: string | null; expired: boolean; currency: string; price: number; ourRate: number | null;
  converted: number | null; today: number | null; todayDate: string | null; terms: string | null;
  regText: string | null; regNumber: string | null; regStatus: 'ACTIVE' | 'EXPIRED' | 'NOT_FOUND' | 'NONE' | null; regExpires: string | null;
}
export interface PriceHint { text: string | null; rows: PriceHintRow[]; }
export interface PriceHistoryEntry {
  kind: 'LIST' | 'REPLY'; date: string | null; supplierName: string; currency: string; price: number; pct: number | null;
  priceListId: number | null; priceRequestId: number | null;
}

const NUM = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 });
const SHORT: Record<string, string> = { KZT: 'тг', RUB: 'руб.', USD: '$', EUR: '€', CNY: 'юань' };

export function hintMoney(v: number | null | undefined, currency: string | null | undefined): string {
  return v == null ? '' : NUM.format(v) + ' ' + (currency ? SHORT[currency] ?? currency : '');
}

/** «📒 Аксион · прайс 01.09: 150 000 руб. → 900 000 тг · ещё 2»; строк нет — null. */
export function hintLine(h: PriceHint | null | undefined, marketCurrency: string): string | null {
  const r = h?.rows?.[0];
  if (!r) return null;
  const date = r.listDate ? ` · прайс ${r.listDate.slice(8, 10)}.${r.listDate.slice(5, 7)}` : '';
  const conv = r.currency === marketCurrency ? '' : r.converted != null ? ` → ${hintMoney(r.converted, marketCurrency)}` : ' — впишите наш курс';
  const more = h!.rows.length > 1 ? ` · ещё ${h!.rows.length - 1}` : '';
  return `📒 ${r.supplierName}${date}: ${hintMoney(r.price, r.currency)}${conv}${r.expired ? ' (прайс истёк)' : ''}${more}`;
}
```

`frontend/src/app/shared/price-list.ts`:

```ts
/** Прайсы поставщиков (спека 2026-10-23 §5): ответы /api/price-lists. */
export interface PriceListColumn { index: number; header: string; }
export type RowRegStatus = 'ACTIVE' | 'EXPIRED' | 'NOT_FOUND' | 'NONE' | null;
export interface PriceListRow {
  id: number; lineNo: number; section: string | null; name: string; model: string | null; article: string | null;
  prices: (number | null)[]; ourPrice: number | null; converted: number | null; today: number | null;
  regText: string | null; regNumber: string | null; regStatus: RowRegStatus; regExpires: string | null; noReg: boolean;
  siteProductId: number | null; siteProductName: string | null; siteProductUrl: string | null;
  medEquipmentId: number | null; medEquipmentName: string | null;
  prevPrice: number | null; prevDate: string | null; prevPct: number | null; fileRow: number | null;
}
export interface PriceListView {
  id: number; status: 'DRAFT' | 'ACTIVE'; distributorId: number; distributorName: string; fileName: string; kind: string;
  parseStatus: string; parseNote: string | null; listDate: string | null; validTo: string | null; expired: boolean;
  currency: string | null; retail: boolean; columns: PriceListColumn[]; ourColumn: number | null; discountPct: number;
  terms: string | null; marketCurrency: string; ourRate: number | null; todayRate: number | null; todayDate: string | null;
  createdBy: string | null; createdAt: string; activatedBy: string | null; activatedAt: string | null; rows: PriceListRow[];
}
export interface PriceListListItem {
  id: number; distributorId: number; distributorName: string; status: 'DRAFT' | 'ACTIVE'; listDate: string | null;
  validTo: string | null; expired: boolean; currency: string | null; fileName: string; kind: string; parseStatus: string;
  createdBy: string | null; createdAt: string; itemCount: number;
}
export interface PriceLinkCandidates {
  products: { productId: number; name: string; brand: string | null; url: string | null }[];
  equipment: { id: number; name: string; manufact: string | null }[];
}
export type PriceListField = 'DISTRIBUTOR' | 'LIST_DATE' | 'VALID_TO' | 'CURRENCY' | 'OUR_COLUMN' | 'DISCOUNT_PCT' | 'TERMS';
export type PriceRowField = 'SECTION' | 'NAME' | 'MODEL' | 'ARTICLE' | 'REG_TEXT' | 'SITE_PRODUCT' | 'EQUIPMENT' | `PRICE:${number}`;

/** Статус прайса для чипа: черновик / действующий / истёк. */
export function listState(p: { status: string; expired: boolean }): { text: string; tone: 'muted' | 'ok' | 'warn' } {
  if (p.status === 'DRAFT') return { text: 'черновик', tone: 'muted' };
  return p.expired ? { text: 'истёк', tone: 'warn' } : { text: 'действует', tone: 'ok' };
}

/** «01.09.2026»; пусто — «». */
export function dmy(iso: string | null | undefined): string {
  return iso ? `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}` : '';
}
```

`ApiService` (`import type` — `PriceHint`, `PriceHintRow`, `PriceHistoryEntry` из `../shared/price-hint`; `PriceListView`,
`PriceListListItem`, `PriceLinkCandidates`, `PriceListField`, `PriceRowField` из `../shared/price-list`):

```ts
  getPriceLists(supplier?: number | null): Observable<PriceListListItem[]> {
    return this.http.get<PriceListListItem[]>(`${this.base}/price-lists`, { params: supplier ? { supplier } : {} });
  }
  uploadPriceList(distributorId: number, file: File): Observable<PriceListView> {
    const fd = new FormData();
    fd.append('file', file, file.name);
    fd.append('distributorId', String(distributorId));
    return this.http.post<PriceListView>(`${this.base}/price-lists`, fd);
  }
  getPriceList(id: number): Observable<PriceListView> {
    return this.http.get<PriceListView>(`${this.base}/price-lists/${id}`);
  }
  patchPriceList(id: number, field: PriceListField, value: string | null): Observable<PriceListView> {
    return this.http.patch<PriceListView>(`${this.base}/price-lists/${id}`, { field, value });
  }
  activatePriceList(id: number): Observable<PriceListView> {
    return this.http.post<PriceListView>(`${this.base}/price-lists/${id}/activate`, {});
  }
  deletePriceList(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/price-lists/${id}`);
  }
  addPriceListRow(id: number): Observable<PriceListView> {
    return this.http.post<PriceListView>(`${this.base}/price-lists/${id}/rows`, {});
  }
  patchPriceListRow(id: number, rowId: number, field: PriceRowField, value: string | null): Observable<PriceListView> {
    return this.http.patch<PriceListView>(`${this.base}/price-lists/${id}/rows/${rowId}`, { field, value });
  }
  deletePriceListRow(id: number, rowId: number): Observable<PriceListView> {
    return this.http.delete<PriceListView>(`${this.base}/price-lists/${id}/rows/${rowId}`);
  }
  downloadPriceListFile(id: number): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/price-lists/${id}/file`, { responseType: 'blob', observe: 'response' });
  }
  searchPriceLists(q: string): Observable<PriceHintRow[]> {
    return this.http.get<PriceHintRow[]>(`${this.base}/price-lists/search`, { params: { q, limit: 50 } });
  }
  priceHints(texts: string[]): Observable<PriceHint[]> {
    return this.http.post<PriceHint[]>(`${this.base}/price-lists/hints`, { texts });
  }
  getPriceHistory(rowId: number): Observable<PriceHistoryEntry[]> {
    return this.http.get<PriceHistoryEntry[]>(`${this.base}/price-lists/rows/${rowId}/history`);
  }
  getPriceLinkCandidates(q: string): Observable<PriceLinkCandidates> {
    return this.http.get<PriceLinkCandidates>(`${this.base}/price-lists/link-candidates`, { params: { q } });
  }
  priceListFromInbound(fileId: number, distributorId?: number | null): Observable<PriceListView> {
    return this.http.post<PriceListView>(`${this.base}/price-lists/from-inbound/${fileId}`, distributorId ? { distributorId } : {});
  }
  getPriceListCounts(): Observable<Record<number, number>> {
    return this.http.get<Record<number, number>>(`${this.base}/price-lists/counts`);
  }
```

`app.routes.ts`:

```ts
      { path: 'price-lists',
        loadComponent: () => import('./pages/price-lists/price-lists.component').then(m => m.PriceListsComponent) },
      { path: 'price-lists/:id',
        loadComponent: () => import('./pages/price-lists/price-list-page.component').then(m => m.PriceListPageComponent) },
```

`layout.component.ts` — в группе «Каталог» после «Дистрибьюторов»:

```html
            <a routerLink="/price-lists" routerLinkActive="active">
              <svg lucideIcon="receipt-text" [size]="16"></svg> Прайсы поставщиков
            </a>
```

- [ ] **Step 2: Список, поиск, загрузка — `/price-lists`**

```ts
import { ChangeDetectorRef, Component, DestroyRef, OnDestroy, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { MarketService } from '../../services/market.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { hintMoney } from '../../shared/price-hint';
import type { PriceHintRow } from '../../shared/price-hint';
import { dmy, listState } from '../../shared/price-list';
import type { PriceListListItem } from '../../shared/price-list';

/** «Каталог → Прайсы поставщиков» (спека 2026-10-23 §5.1, §6.2 п. 1): прайсы рынка, поиск «у кого и почём», загрузка. */
@Component({
  selector: 'app-price-lists',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink],
  template: `
    <div class="pl-page">
      <h2>Прайсы поставщиков</h2>
      <p class="subtitle">Цены из прайсов видны в заявках, тендерах и КП — без запроса цен. Закупка в чужой валюте — по нашему курсу
        («Система → Курсы валют»).</p>

      <div class="pl-upload" *ngIf="admin">
        <select [(ngModel)]="uploadSupplier" aria-label="Поставщик прайса">
          <option [ngValue]="null">Поставщик…</option>
          <option *ngFor="let d of suppliers" [ngValue]="d.id">{{ d.name }}</option>
        </select>
        <label class="btn btn-primary" [class.disabled]="!uploadSupplier || uploading">
          {{ uploading ? 'Загружаю…' : 'Загрузить прайс' }}
          <input type="file" hidden accept=".xlsx,.xls,.docx,.doc,.pdf,.png,.jpg,.jpeg" [disabled]="!uploadSupplier || uploading"
                 (change)="upload($event)" />
        </label>
        <span class="pl-hint">Excel, Word, PDF — до 15 МБ</span>
      </div>

      <input type="search" class="pl-search" [(ngModel)]="query" (input)="onQuery()" placeholder="Найти в прайсах: модель, наименование, поставщик"
             aria-label="Поиск по прайсам" />
      <div class="pl-hits" *ngIf="query.trim().length >= 2">
        <p class="pl-hint" *ngIf="searching">Ищу…</p>
        <p class="pl-hint" *ngIf="!searching && !hits.length">Ничего не нашлось</p>
        <a class="pl-hit" *ngFor="let h of hits" [routerLink]="['/price-lists', h.priceListId]">
          <span class="pl-h-name">{{ h.name }}</span>
          <span class="pl-h-meta">{{ h.supplierName }} · прайс {{ dmy(h.listDate) }}<span class="pl-exp" *ngIf="h.expired"> · истёк</span></span>
          <span class="pl-h-price">{{ money(h.price, h.currency) }}<ng-container *ngIf="h.currency !== marketCurrency">
            <ng-container *ngIf="h.converted != null; else noRate"> → {{ money(h.converted, marketCurrency) }}</ng-container>
            <ng-template #noRate> — впишите наш курс</ng-template></ng-container>
            <span class="pl-today" *ngIf="h.today != null"> · сегодня {{ money(h.today, marketCurrency) }}</span></span>
          <span class="pl-h-ru" *ngIf="h.regStatus" [class.pl-bad]="h.regStatus !== 'ACTIVE'">{{ ruText(h.regStatus) }}</span>
        </a>
      </div>

      <div class="pl-filter" *ngIf="supplierFilter">
        Поставщик: <b>{{ supplierName(supplierFilter) }}</b> · <a class="xref" routerLink="/price-lists">все прайсы</a>
      </div>
      <p class="pl-hint" *ngIf="loading">Загружаю…</p>
      <p class="pl-hint" *ngIf="!loading && !lists.length">Прайсов пока нет.</p>
      <div class="pl-list">
        <a class="pl-card" *ngFor="let p of lists" [routerLink]="['/price-lists', p.id]">
          <span class="pl-c-sup">{{ p.distributorName }}</span>
          <span class="pl-chip" [attr.data-tone]="state(p).tone">{{ state(p).text }}</span>
          <span class="pl-c-meta">прайс {{ dmy(p.listDate) || dmy(p.createdAt.slice(0, 10)) }} · {{ p.validTo ? 'до ' + dmy(p.validTo) : 'срок не указан' }}
            · {{ p.currency || '—' }} · позиций: {{ p.itemCount }}</span>
          <span class="pl-c-file">{{ p.fileName }}<ng-container *ngIf="p.createdBy"> · {{ p.createdBy }}</ng-container></span>
        </a>
      </div>
    </div>
  `,
  styles: [`
    .pl-page { max-width: 980px; display: flex; flex-direction: column; gap: 10px; }
    .pl-page h2 { margin: 0; }
    .pl-upload { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .pl-upload select { min-width: 220px; max-width: 100%; }
    .pl-upload .disabled { opacity: .55; pointer-events: none; }
    .pl-hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .pl-search { padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); color: var(--text); }
    .pl-hits, .pl-list { display: flex; flex-direction: column; gap: 6px; }
    .pl-hit, .pl-card { display: grid; grid-template-columns: 1fr auto; gap: 2px 12px; padding: 8px 12px; border: 1px solid var(--border);
                        border-radius: 8px; background: var(--surface); color: var(--text); text-decoration: none; }
    .pl-hit:hover, .pl-card:hover { border-color: var(--accent); }
    .pl-h-name, .pl-c-sup { overflow-wrap: anywhere; font-weight: 600; }
    .pl-h-meta, .pl-c-meta, .pl-c-file { font-size: 12px; color: var(--text-muted); grid-column: 1; }
    .pl-h-price { grid-column: 2; grid-row: 1; text-align: right; white-space: nowrap; }
    .pl-h-ru { grid-column: 2; font-size: 12px; color: var(--success-text); text-align: right; }
    .pl-h-ru.pl-bad, .pl-exp { color: var(--warn-text); }
    .pl-today { color: var(--text-muted); font-size: 12px; }
    .pl-chip { grid-column: 2; grid-row: 1; justify-self: end; font-size: 12px; padding: 2px 9px; border-radius: 999px; }
    .pl-chip[data-tone="ok"] { color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .pl-chip[data-tone="warn"] { color: var(--warn-text); background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .pl-chip[data-tone="muted"] { color: var(--text-muted); border: 1px solid var(--border); }
    .pl-filter { font-size: 13px; }
    @media (max-width: 900px) {
      .pl-hit, .pl-card { grid-template-columns: 1fr; }
      .pl-h-price, .pl-h-ru, .pl-chip { grid-column: 1; grid-row: auto; text-align: left; justify-self: start; white-space: normal; }
      .pl-search { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class PriceListsComponent implements OnInit, OnDestroy {
  lists: PriceListListItem[] = [];
  suppliers: { id: number; name: string }[] = [];
  supplierFilter: number | null = null;
  uploadSupplier: number | null = null;
  uploading = false;
  loading = true;
  query = '';
  hits: PriceHintRow[] = [];
  searching = false;
  private timer: any = null;
  private sub?: Subscription;
  readonly dmy = dmy;
  readonly state = listState;

  constructor(private api: ApiService, private auth: AuthService, private market: MarketService, private notify: NotificationService,
              private route: ActivatedRoute, private router: Router, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get admin(): boolean { return this.auth.isAdmin(); }
  get marketCurrency(): string { return this.market.value === 'KZ' ? 'KZT' : 'RUB'; }

  ngOnInit() {
    this.api.getDistributors().subscribe({ next: d => { this.suppliers = d.map(x => ({ id: x.id, name: x.name })); this.cdr.markForCheck(); } });
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(q => {
      const s = Number(q.get('supplier'));
      this.supplierFilter = Number.isFinite(s) && s > 0 ? s : null;
      if (this.supplierFilter) this.uploadSupplier = this.supplierFilter;
      this.load();
    });
  }

  ngOnDestroy() { clearTimeout(this.timer); }

  private load() {
    this.loading = true;
    this.api.getPriceLists(this.supplierFilter).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: l => { this.lists = l; this.loading = false; this.cdr.markForCheck(); },
      error: e => { this.loading = false; this.notify.error('Прайсы не загрузились: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }

  supplierName(id: number): string { return this.suppliers.find(s => s.id === id)?.name ?? '№ ' + id; }
  money(v: number | null, c: string | null) { return hintMoney(v, c); }

  ruText(s: string): string {
    return s === 'ACTIVE' ? 'РУ ✓' : s === 'EXPIRED' ? 'РУ истекло' : s === 'NONE' ? 'нет РУ' : 'РУ не найдено';
  }

  onQuery() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), 300);
  }

  private search() {
    const q = this.query.trim();
    this.sub?.unsubscribe();
    if (q.length < 2) { this.hits = []; this.searching = false; this.cdr.markForCheck(); return; }
    this.searching = true;
    this.sub = this.api.searchPriceLists(q).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: h => { this.hits = h; this.searching = false; this.cdr.markForCheck(); },
      error: e => { this.searching = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  upload(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !this.uploadSupplier) return;
    if (file.size > 15 * 1024 * 1024) { this.notify.error('Файл больше 15 МБ — уменьшите его и загрузите снова'); return; }
    this.uploading = true;
    this.api.uploadPriceList(this.uploadSupplier, file).subscribe({
      next: v => { this.uploading = false; this.router.navigate(['/price-lists', v.id]); },
      error: e => {
        this.uploading = false;
        this.notify.error(e?.status === 413 ? 'Файл больше 15 МБ — уменьшите его и загрузите снова' : errorText(e));
        this.cdr.markForCheck();
      },
    });
  }
}
```

- [ ] **Step 3: Экран проверки — `/price-lists/:id`**

```ts
import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { numText, parseNum, saveBlob } from '../../shared/client-offer';
import { responseFile } from '../../shared/share';
import { curShort, rateText } from '../../shared/rates';
import { hintMoney } from '../../shared/price-hint';
import { dmy, listState } from '../../shared/price-list';
import type { PriceLinkCandidates, PriceListField, PriceListRow, PriceListView, PriceRowField } from '../../shared/price-list';

/**
 * Экран проверки прайса (спека 2026-10-23 §5.3): шапка, строки с закупкой, тенге, РУ, связью и «было …»; правка — по полю,
 * «Сохранить прайс» — в подсказки. Оператор видит экран без правки.
 */
@Component({
  selector: 'app-price-list-page',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink],
  template: `
    <div class="pp-page" *ngIf="v as p; else loadingTpl">
      <a class="xref" routerLink="/price-lists">← Прайсы поставщиков</a>
      <div class="pp-head">
        <h2>{{ p.distributorName }} <span class="pp-chip" [attr.data-tone]="state(p).tone">{{ state(p).text }}</span></h2>
        <span class="pp-save">{{ saveText }}</span>
      </div>
      <div class="pp-actions">
        <button type="button" class="btn btn-line" (click)="download()">Скачать файл</button>
        <ng-container *ngIf="admin">
          <button type="button" class="btn btn-primary" *ngIf="p.status === 'DRAFT'" [disabled]="busy" (click)="activate()">Сохранить прайс</button>
          <button type="button" class="btn btn-line danger" [disabled]="busy" (click)="remove()">Удалить прайс</button>
        </ng-container>
      </div>
      <p class="pp-note warn" *ngIf="p.parseNote">{{ p.parseNote }}</p>

      <div class="pp-card pp-fields">
        <label>Поставщик
          <select [ngModel]="p.distributorId" (ngModelChange)="patch('DISTRIBUTOR', $event)" [disabled]="!admin">
            <option *ngFor="let d of suppliers" [ngValue]="d.id">{{ d.name }}</option>
          </select></label>
        <label>Дата прайса <input type="date" [value]="p.listDate || ''" (change)="patch('LIST_DATE', val($event))" [disabled]="!admin" /></label>
        <label>Действует до <input type="date" [value]="p.validTo || ''" (change)="patch('VALID_TO', val($event))" [disabled]="!admin" />
          <span class="pp-hint" *ngIf="!p.validTo">срок не указан</span></label>
        <label>Валюта
          <select [ngModel]="p.currency" (ngModelChange)="patch('CURRENCY', $event)" [disabled]="!admin">
            <option *ngIf="p.currency === 'MIXED'" [ngValue]="'MIXED'">не ясна — выберите</option>
            <option *ngFor="let c of currencies" [ngValue]="c">{{ c }}</option>
          </select></label>
        <label>Наша закупка — колонка
          <select [ngModel]="p.ourColumn" (ngModelChange)="patch('OUR_COLUMN', $event == null ? null : String($event))" [disabled]="!admin || !p.columns.length">
            <option *ngFor="let c of p.columns" [ngValue]="c.index">{{ c.header }}</option>
          </select></label>
        <label>Наша скидка, %
          <input inputmode="decimal" [value]="numText(p.discountPct)" (change)="patch('DISCOUNT_PCT', val($event))" [disabled]="!admin" />
          <span class="pp-hint warn" *ngIf="p.retail && !p.discountPct">цены розничные — укажите нашу скидку</span></label>
        <label class="wide">Условия
          <textarea rows="2" maxlength="1000" [value]="p.terms || ''" (change)="patch('TERMS', val($event))" [disabled]="!admin"></textarea></label>
        <p class="pp-hint wide" *ngIf="p.currency && p.currency !== p.marketCurrency && p.currency !== 'MIXED'">
          <ng-container *ngIf="p.ourRate != null; else noRate">Наш курс: 1 {{ short(p.currency) }} = {{ rate(p.ourRate) }} {{ short(p.marketCurrency) }}</ng-container>
          <ng-template #noRate>Нашего курса {{ short(p.currency) }} нет — впишите его в «Система → Курсы валют»</ng-template>
          <ng-container *ngIf="p.todayRate != null"> · сегодня {{ rate(p.todayRate) }} ({{ dmy(p.todayDate) }})</ng-container></p>
      </div>

      <div class="pp-rows">
        <ng-container *ngFor="let r of p.rows; let i = index">
          <div class="pp-section" *ngIf="r.section && r.section !== p.rows[i - 1]?.section">{{ r.section }}</div>
          <div class="pp-row" [class.pp-noprice]="r.ourPrice == null">
            <div class="pp-name">
              <input class="pp-in" [value]="r.name" (change)="patchRow(r, 'NAME', val($event))" [disabled]="!admin" aria-label="Наименование" />
              <span class="pp-model">{{ r.model || '' }}</span>
            </div>
            <div class="pp-prices">
              <label *ngFor="let c of p.columns" [class.pp-our]="c.index === p.ourColumn">
                <span class="pp-cap">{{ c.header }}</span>
                <input inputmode="decimal" [value]="numText(r.prices[c.index])" (change)="patchRow(r, priceField(c.index), val($event))"
                       [disabled]="!admin" [attr.aria-label]="c.header" />
              </label>
            </div>
            <div class="pp-buy">
              <span class="pp-cap">закупка</span>
              <b>{{ money(r.ourPrice, p.currency) || '—' }}</b>
              <span *ngIf="r.converted != null"> → {{ money(r.converted, p.marketCurrency) }}</span>
              <span class="pp-today" *ngIf="r.today != null">сегодня {{ money(r.today, p.marketCurrency) }}</span>
              <span class="pp-prev" *ngIf="r.prevPrice != null">было {{ money(r.prevPrice, p.currency) }} в прайсе от {{ dmy(r.prevDate) }}
                <ng-container *ngIf="r.prevPct != null"> — {{ r.prevPct > 0 ? '+' : '' }}{{ pct(r.prevPct) }} %</ng-container></span>
            </div>
            <div class="pp-reg">
              <span class="pp-ru" [attr.data-st]="r.regStatus">{{ ruText(r) }}</span>
              <input class="pp-in small" [value]="r.regText || ''" (change)="patchRow(r, 'REG_TEXT', val($event))" [disabled]="!admin"
                     placeholder="РУ из прайса" aria-label="РУ из прайса" />
            </div>
            <div class="pp-link">
              <ng-container *ngIf="r.siteProductId; else eqTpl">наш товар: <a [href]="r.siteProductUrl" target="_blank" rel="noopener" *ngIf="r.siteProductUrl; else spName">{{ r.siteProductName }} ↗</a>
                <ng-template #spName>{{ r.siteProductName }}</ng-template></ng-container>
              <ng-template #eqTpl><ng-container *ngIf="r.medEquipmentId; else noneTpl">каталог: {{ r.medEquipmentName }}</ng-container></ng-template>
              <ng-template #noneTpl><span class="pp-muted">без связи</span></ng-template>
              <ng-container *ngIf="admin">
                <button type="button" class="link" (click)="openLink(r)">{{ r.siteProductId || r.medEquipmentId ? 'сменить' : 'связать' }}</button>
                <button type="button" class="link" *ngIf="r.siteProductId || r.medEquipmentId" (click)="unlink(r)">снять</button>
                <button type="button" class="link danger" (click)="deleteRow(r)">удалить строку</button>
              </ng-container>
              <div class="pp-cands" *ngIf="linkFor === r.id">
                <input type="search" [(ngModel)]="linkQuery" (input)="onLinkQuery()" placeholder="Наш товар или позиция каталога" aria-label="Поиск товара" />
                <button type="button" class="pp-cand" *ngFor="let c of cands?.products ?? []" (click)="link(r, 'SITE_PRODUCT', c.productId)">🛒 {{ c.name }}</button>
                <button type="button" class="pp-cand" *ngFor="let c of cands?.equipment ?? []" (click)="link(r, 'EQUIPMENT', c.id)">📦 {{ c.name }}</button>
              </div>
            </div>
          </div>
        </ng-container>
        <button type="button" class="btn btn-line" *ngIf="admin" [disabled]="busy" (click)="addRow()">+ строка</button>
      </div>
    </div>
    <ng-template #loadingTpl><p class="pp-hint">Загружаю…</p></ng-template>
  `,
  styles: [`
    .pp-page { max-width: 1100px; display: flex; flex-direction: column; gap: 10px; }
    .pp-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    .pp-head h2 { margin: 0; }
    .pp-save, .pp-hint, .pp-muted, .pp-model, .pp-cap, .pp-today, .pp-prev { font-size: 12px; color: var(--text-muted); }
    .pp-hint.warn, .pp-note.warn { color: var(--warn-text); }
    .pp-note { margin: 0; font-size: 13px; }
    .pp-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .pp-chip { font-size: 12px; padding: 2px 9px; border-radius: 999px; vertical-align: middle; }
    .pp-chip[data-tone="ok"] { color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .pp-chip[data-tone="warn"] { color: var(--warn-text); background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .pp-chip[data-tone="muted"] { color: var(--text-muted); border: 1px solid var(--border); }
    .pp-card { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; background: var(--surface); }
    .pp-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px 12px; }
    .pp-fields label { display: flex; flex-direction: column; gap: 3px; font-size: 12px; color: var(--text-muted); }
    .pp-fields .wide { grid-column: 1 / -1; }
    .pp-rows { display: flex; flex-direction: column; gap: 6px; }
    .pp-section { margin-top: 8px; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); }
    .pp-row { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1.4fr) minmax(0, 1fr); gap: 6px 12px; padding: 8px 12px;
              border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    .pp-row.pp-noprice { border-color: color-mix(in srgb, var(--warn) 60%, var(--border)); }
    .pp-name { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .pp-in { width: 100%; padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); }
    .pp-in.small { font-size: 12px; }
    .pp-prices { display: flex; gap: 6px; flex-wrap: wrap; }
    .pp-prices label { display: flex; flex-direction: column; gap: 2px; min-width: 110px; flex: 1; }
    .pp-prices label.pp-our input { border-color: var(--accent); }
    .pp-prices input { padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); }
    .pp-buy { display: flex; flex-direction: column; gap: 2px; }
    .pp-reg, .pp-link { grid-column: 1 / -1; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 13px; }
    .pp-ru { font-size: 12px; padding: 2px 9px; border-radius: 999px; border: 1px solid var(--border); color: var(--text-muted); white-space: nowrap; }
    .pp-ru[data-st="ACTIVE"] { border: 0; color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .pp-ru[data-st="EXPIRED"], .pp-ru[data-st="NOT_FOUND"], .pp-ru[data-st="NONE"] { border: 0; color: var(--warn-text);
              background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .pp-cands { flex-basis: 100%; display: flex; flex-direction: column; gap: 4px; }
    .pp-cand { text-align: left; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
               color: var(--text); cursor: pointer; min-height: 36px; }
    .link.danger, .btn.danger { color: var(--danger-text); }
    @media (max-width: 900px) {
      .pp-fields { grid-template-columns: 1fr; }
      .pp-row { grid-template-columns: 1fr; }
      .pp-in, .pp-prices input { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class PriceListPageComponent implements OnInit {
  v: PriceListView | null = null;
  suppliers: { id: number; name: string }[] = [];
  busy = false;
  saveText = '';
  linkFor: number | null = null;
  linkQuery = '';
  cands: PriceLinkCandidates | null = null;
  readonly currencies = ['KZT', 'RUB', 'USD', 'EUR', 'CNY'];
  readonly dmy = dmy;
  readonly state = listState;
  readonly numText = numText;
  readonly short = curShort;
  readonly rate = rateText;
  readonly String = String;
  private id = 0;
  private timer: any = null;

  constructor(private api: ApiService, private auth: AuthService, private confirm: ConfirmService, private notify: NotificationService,
              private route: ActivatedRoute, private router: Router, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get admin(): boolean { return this.auth.isAdmin(); }

  ngOnInit() {
    this.api.getDistributors().subscribe({ next: d => { this.suppliers = d.map(x => ({ id: x.id, name: x.name })); this.cdr.markForCheck(); } });
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(pm => {
      this.id = Number(pm.get('id'));
      this.v = null;
      this.api.getPriceList(this.id).subscribe({
        next: v => { this.v = v; this.cdr.markForCheck(); },
        error: e => { this.notify.error(errorText(e)); this.router.navigate(['/price-lists']); },
      });
    });
  }

  val(ev: Event): string | null {
    const t = (ev.target as HTMLInputElement).value.trim();
    return t === '' ? null : t;
  }

  priceField(i: number): PriceRowField { return `PRICE:${i}`; }
  money(v: number | null, c: string | null) { return hintMoney(v, c); }
  pct(v: number): string { return String(v).replace('.', ','); }

  ruText(r: PriceListRow): string {
    switch (r.regStatus) {
      case 'ACTIVE': return 'РУ ✓' + (r.regExpires ? ' до ' + dmy(r.regExpires) : '');
      case 'EXPIRED': return 'РУ истекло' + (r.regExpires ? ' ' + dmy(r.regExpires) : '');
      case 'NONE': return 'нет РУ';
      case 'NOT_FOUND': return 'РУ не найдено';
      default: return 'не проверено';
    }
  }

  patch(field: PriceListField, value: string | null) {
    this.run(this.api.patchPriceList(this.id, field, value));
  }

  patchRow(r: PriceListRow, field: PriceRowField, value: string | null) {
    if (field.startsWith('PRICE:') && value != null && parseNum(value) == null) { this.notify.error('Цена — числом'); return; }
    this.run(this.api.patchPriceListRow(this.id, r.id, field, field.startsWith('PRICE:') && value != null ? String(parseNum(value)) : value));
  }

  addRow() { this.run(this.api.addPriceListRow(this.id)); }

  deleteRow(r: PriceListRow) {
    this.confirm.ask('Удалить строку прайса?', r.name, { danger: true, confirmLabel: 'Удалить' }).subscribe(ok => {
      if (ok) this.run(this.api.deletePriceListRow(this.id, r.id));
    });
  }

  activate() {
    this.run(this.api.activatePriceList(this.id), () => this.notify.success('Прайс сохранён — его цены в подсказках'));
  }

  remove() {
    this.confirm.ask('Удалить прайс?', 'Строки КП, взятые из него, останутся со своей закупкой.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.deletePriceList(this.id).subscribe({
          next: () => { this.notify.success('Прайс удалён'); this.router.navigate(['/price-lists']); },
          error: e => this.notify.error(errorText(e)),
        });
      });
  }

  download() {
    this.api.downloadPriceListFile(this.id).subscribe({
      next: r => { const f = responseFile(r, this.v?.fileName || 'прайс'); if (f) saveBlob(f, f.name); },
      error: () => this.notify.error('Файл не скачался — попробуйте ещё раз'),
    });
  }

  openLink(r: PriceListRow) {
    this.linkFor = this.linkFor === r.id ? null : r.id;
    this.linkQuery = r.model || r.name;
    this.cands = null;
    if (this.linkFor) this.searchLink();
  }

  onLinkQuery() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.searchLink(), 300);
  }

  private searchLink() {
    const q = this.linkQuery.trim();
    if (q.length < 2) { this.cands = null; this.cdr.markForCheck(); return; }
    this.api.getPriceLinkCandidates(q).subscribe({ next: c => { this.cands = c; this.cdr.markForCheck(); } });
  }

  link(r: PriceListRow, field: 'SITE_PRODUCT' | 'EQUIPMENT', id: number) {
    this.linkFor = null;
    this.run(this.api.patchPriceListRow(this.id, r.id, field, String(id)));
  }

  unlink(r: PriceListRow) {
    this.run(this.api.patchPriceListRow(this.id, r.id, r.siteProductId ? 'SITE_PRODUCT' : 'EQUIPMENT', null));
  }

  private run(obs: Observable<PriceListView>, done?: () => void) {
    this.busy = true;
    this.saveText = 'Сохраняю…';
    obs.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => {
        this.v = v;
        this.busy = false;
        const d = new Date();
        this.saveText = `Сохранено · ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        done?.();
        this.cdr.markForCheck();
      },
      error: e => { this.busy = false; this.saveText = 'Не сохранено: ' + errorText(e); this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
```

- [ ] **Step 4: «Дистрибьюторы» — «Прайсы: N ›»**

`distributors.component.ts`: поле `priceCounts: Record<number, number> = {};`, в `loadData()` — ещё
`this.api.getPriceListCounts().subscribe({ next: c => { this.priceCounts = c; this.cdr.detectChanges(); }, error: () => {} });`;
в ячейке «Название» после меток:

```html
            <a class="xref pl-link" *ngIf="priceCounts[d.id]; else plAdd" [routerLink]="['/price-lists']" [queryParams]="{ supplier: d.id }">Прайсы: {{ priceCounts[d.id] }} ›</a>
            <ng-template #plAdd><a class="xref pl-link" *ngIf="auth.isAdmin()" [routerLink]="['/price-lists']" [queryParams]="{ supplier: d.id }">+ прайс ›</a></ng-template>
```

(`RouterLink` — в `imports` компонента; стиль `.pl-link { margin-left: 6px; font-size: 12px; }`.)

- [ ] **Step 5: «Входящие» — «Занести как прайс»**

`inbound.component.ts`: конструктор — ещё `public auth: AuthService`; в ячейке «Тип» после «📎 …»:

```html
            <span class="muted pl-like" *ngIf="hasPriceLike(r)"> · 📒 похоже на прайс</span>
```

в ячейке действий:

```html
            <ng-container *ngIf="auth.isAdmin()">
              <button *ngFor="let f of r.priceFiles" type="button" class="btn-line-solid" [disabled]="pricing"
                      (click)="toPriceList(r, f)" [attr.title]="f.name">Занести как прайс<ng-container *ngIf="r.priceFiles.length > 1"> · {{ f.name }}</ng-container></button>
            </ng-container>
```

и окно выбора поставщика — когда сервер ответил 400 «Выберите поставщика…»:

```html
    <div class="overlay" *ngIf="pickFile" (click)="pickFile = null">
      <div class="modal" role="dialog" aria-label="Поставщик прайса" (click)="$event.stopPropagation()">
        <h3>Чей это прайс?</h3>
        <p class="hint">По адресу отправителя поставщик не нашёлся — выберите его.</p>
        <select [(ngModel)]="pickSupplier" aria-label="Поставщик">
          <option [ngValue]="null">Поставщик…</option>
          <option *ngFor="let d of suppliers" [ngValue]="d.id">{{ d.name }}</option>
        </select>
        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="!pickSupplier || pricing" (click)="toPriceList(null, pickFile, pickSupplier)">Занести</button>
          <button type="button" class="btn btn-cancel" (click)="pickFile = null">Отмена</button>
        </div>
      </div>
    </div>
```

```ts
  pricing = false;
  pickFile: { id: number; name: string } | null = null;
  pickSupplier: number | null = null;
  suppliers: { id: number; name: string }[] = [];

  hasPriceLike(r: any): boolean { return (r.priceFiles || []).some((f: any) => f.priceLike); }

  toPriceList(_r: any, f: { id: number; name: string }, distributorId?: number | null) {
    this.pricing = true;
    this.api.priceListFromInbound(f.id, distributorId).subscribe({
      next: v => { this.pricing = false; this.pickFile = null; this.router.navigate(['/price-lists', v.id]); },
      error: e => {
        this.pricing = false;
        if (e?.status === 400 && String(errorText(e)).startsWith('Выберите поставщика')) {
          this.pickFile = f;
          if (!this.suppliers.length) this.api.getDistributors().subscribe(d => { this.suppliers = d.map(x => ({ id: x.id, name: x.name })); this.cdr.detectChanges(); });
        } else {
          this.notify.error(errorText(e));
        }
        this.cdr.detectChanges();
      },
    });
  }
```

(`errorText` — из `../../shared/error-text`; у компонента уже есть `Router`.)

- [ ] **Step 6: Письмо с прайсом для живой проверки — `dev-mail.py price <файл>`**

`scripts/dev-mail.py` — в шапку справки строку `python3 scripts/dev-mail.py price <файл> — прайс поставщика без метки КП
(прочее): «Занести как прайс»`, функция и ветка разбора аргументов:

```python
def price(path):
    m = EmailMessage()
    m["From"] = "Отдел продаж ZZ <zz-prices@example.kz>"
    m["To"] = TO
    m["Subject"] = "Прайс-лист на ноябрь"
    m.set_content("Добрый день!\nВысылаем актуальный прайс-лист.\n\nС уважением, отдел продаж")
    name = os.path.basename(path)
    with open(path, "rb") as f:
        m.add_attachment(f.read(), maintype="application", subtype="octet-stream", filename=name)
    send(m)
```

```python
    elif cmd == "price" and len(sys.argv) > 2:
        price(sys.argv[2])
```

- [ ] **Step 7: Сборка и проверка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -15`
Expected: без ошибок; начальный бандл ≤ 1,5 МБ (страницы прайсов — ленивые; в начальном — методы `ApiService`, пункт меню,
иконка).

Живьём (бэкенд + `ng serve`; синтетические прайсы — `PriceListFiles` из тестов сохранить в файлы одноразовым JUnit-методом
или собрать в `jshell`; настоящие Аксион и Орион — тоже можно, они только в локальной базе): загрузить Excel у поставщика из
«Дистрибьюторов» («+ прайс ›» → поставщик подставлен) → экран проверки: колонки, наша колонка «дилерская», закупка
и «→ тг», РУ, связь, правка цены и скидки (закупка пересчитана), «связать» с нашим товаром, «Сохранить прайс» → «действует»;
поиск «дки» → строка; «Входящие» — `python3 scripts/dev-mail.py price <файл прайса>` (бэкенд с приёмом zakup@ на GreenMail,
CLAUDE.md §5; у поставщика проверки — почта `zz-prices@example.kz`) → «📒 похоже на прайс», «Занести как прайс» → экран
проверки с этим поставщиком; без почты у поставщика — окно «Чей это прайс?»; 1280 / 390, обе темы; оператор — без кнопок
записи.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src scripts/dev-mail.py && git commit -m "feat(price-lists): страницы «Прайсы поставщиков» и проверки прайса, «Прайсы ›» у дистрибьюторов, «Занести как прайс» во «Входящих»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 14: Фронт — подсказки «📒 по прайсу» на местах и прайсы в КП («Поставщик ▾», «Из реестра и каталога», «из прайса»)

**Files:**
- Create: `frontend/src/app/shared/price-hint.component.ts`
- Modify: `frontend/src/app/shared/private-request.ts` (`PrLine.priceList`), `frontend/src/app/pages/private-requests/pr-lines.component.ts`,
  `frontend/src/app/pages/private-requests/pr-rfq-dialog.component.ts`, `frontend/src/app/pages/tenders/tender-lots.component.ts`,
  `frontend/src/app/pages/tenders/lot-kp-panel.component.ts`, `frontend/src/app/shared/product-watch.ts`
  (`ProductCatalogItem.priceList`), `frontend/src/app/pages/product-groups/product-catalog.component.ts`,
  `frontend/src/app/shared/client-offer.ts` (`SupplierOffer`), `frontend/src/app/pages/client-offers/client-offer-items.component.ts`,
  `frontend/src/app/pages/client-offers/offer-add-item-dialog.component.ts`

**Interfaces:**
- Consumes: `PriceHint`, `PriceHintRow`, `hintLine`, `hintMoney`, `PriceHistoryEntry` (задача 13), `ApiService.getPriceHistory`,
  `searchPriceLists` (задача 13); поля сервера: `PrivateRequestLineView.priceList`, `TenderLotResponse.priceList /
  priceBudgetPct`, причина `PRICE_LIST`, `ProductCatalogResponse.Item.priceList` (задача 9); `SupplierOfferResponse` с полями
  строки прайса (задача 10).
- Produces: `app-price-hint` (`[hint]`, `[marketCurrency]`, `[open]`, `(closed)`); `AddPick` — ещё `{ kind: 'price'; row:
  PriceHintRow }`.

- [ ] **Step 1: Компонент подсказки**

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../services/api.service';
import { hintLine, hintMoney } from './price-hint';
import type { PriceHint, PriceHintRow, PriceHistoryEntry } from './price-hint';

/**
 * «📒 по прайсу» (спека 2026-10-23 §6.1): строка лучшей цены, по нажатию — все поставщики с курсом сегодня, условиями,
 * сроком прайса, историей и ссылкой на прайс. В не ленивой странице «Тендеры» — только под @defer.
 */
@Component({
  selector: 'app-price-hint',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink],
  template: `
    <button type="button" class="ph-line" *ngIf="!open && line" (click)="open = true">{{ line }}</button>
    <div class="ph-panel" *ngIf="open && hint?.rows?.length">
      <div class="ph-head"><b>По прайсам поставщиков</b>
        <button type="button" class="ph-x" (click)="open = false; closed.emit()" aria-label="Свернуть">свернуть</button></div>
      <div class="ph-row" *ngFor="let r of hint!.rows">
        <div class="ph-top"><span class="ph-sup">{{ r.supplierName }}</span>
          <span class="ph-price">{{ money(r.price, r.currency) }}<ng-container *ngIf="r.currency !== marketCurrency">
            <ng-container *ngIf="r.converted != null; else noRate"> → {{ money(r.converted, marketCurrency) }}</ng-container>
            <ng-template #noRate> — впишите наш курс</ng-template></ng-container></span></div>
        <div class="ph-name">{{ r.name }}</div>
        <div class="ph-meta">
          <span *ngIf="r.today != null">по курсу сегодня — {{ money(r.today, marketCurrency) }} · </span>
          прайс {{ dm(r.listDate) }}<ng-container *ngIf="r.validTo"> · до {{ dmy(r.validTo) }}</ng-container>
          <span class="ph-exp" *ngIf="r.expired"> · прайс истёк</span>
        </div>
        <div class="ph-terms" *ngIf="r.terms">{{ r.terms }}</div>
        <div class="ph-links">
          <a class="xref" [routerLink]="['/price-lists', r.priceListId]">прайс ›</a>
          <button type="button" class="ph-x" (click)="toggleHistory(r)">{{ hist.has(r.rowId) ? 'скрыть историю' : 'история ▾' }}</button>
        </div>
        <ul class="ph-hist" *ngIf="hist.get(r.rowId) as h">
          <li *ngIf="!h.length">Других цен этого товара пока нет</li>
          <li *ngFor="let e of h">{{ e.kind === 'LIST' ? 'прайс' : 'ответ на запрос' }} {{ dmy(e.date) }} {{ e.supplierName }} —
            {{ money(e.price, e.currency) }}<ng-container *ngIf="e.pct != null"> ({{ e.pct > 0 ? '+' : '' }}{{ pct(e.pct) }} %)</ng-container></li>
        </ul>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .ph-line { display: block; width: 100%; text-align: left; border: 0; background: none; padding: 2px 0; color: var(--text-muted);
               font-size: 13px; cursor: pointer; overflow-wrap: anywhere; }
    .ph-line:hover { color: var(--accent); }
    .ph-panel { margin-top: 4px; border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; background: var(--surface);
                display: flex; flex-direction: column; gap: 8px; }
    .ph-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; font-size: 13px; }
    .ph-x { border: 0; background: none; color: var(--accent); cursor: pointer; font-size: 12px; padding: 0; }
    .ph-row { border-top: 1px solid var(--border); padding-top: 6px; display: flex; flex-direction: column; gap: 2px; font-size: 13px; }
    .ph-row:first-of-type { border-top: 0; padding-top: 0; }
    .ph-top { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
    .ph-sup { font-weight: 600; }
    .ph-price { white-space: nowrap; }
    .ph-name, .ph-meta, .ph-terms { color: var(--text-muted); font-size: 12px; overflow-wrap: anywhere; }
    .ph-exp { color: var(--warn-text); }
    .ph-links { display: flex; gap: 12px; align-items: center; }
    .ph-hist { margin: 0; padding-left: 16px; font-size: 12px; color: var(--text-muted); }
    @media (max-width: 900px) { .ph-line, .ph-x { min-height: 40px; } }
  `],
})
export class PriceHintComponent {
  @Input() hint: PriceHint | null | undefined = null;
  @Input() marketCurrency = 'KZT';
  @Input() open = false;
  @Output() closed = new EventEmitter<void>();
  hist = new Map<number, PriceHistoryEntry[]>();

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get line(): string | null { return hintLine(this.hint, this.marketCurrency); }
  money(v: number | null, c: string | null) { return hintMoney(v, c); }
  dm(iso: string | null) { return iso ? iso.slice(8, 10) + '.' + iso.slice(5, 7) : ''; }
  dmy(iso: string | null) { return iso ? `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}` : ''; }
  pct(v: number) { return String(v).replace('.', ','); }

  toggleHistory(r: PriceHintRow) {
    if (this.hist.has(r.rowId)) { this.hist.delete(r.rowId); return; }
    this.api.getPriceHistory(r.rowId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: h => { this.hist.set(r.rowId, h); this.cdr.markForCheck(); },
      error: () => { this.hist.set(r.rowId, []); this.cdr.markForCheck(); },
    });
  }
}
```

- [ ] **Step 2: Частная заявка — позиция и «Запросить цены…»**

`shared/private-request.ts` — `PrLine`: `priceList?: PriceHint | null;` (`import type { PriceHint } from './price-hint';`).

`pr-lines.component.ts` — `PriceHintComponent` в `imports`; под строкой позиции (после `</div>` строки, перед `.details`):

```html
          <app-price-hint *ngIf="l.priceList?.rows?.length" class="pr-price" [hint]="l.priceList" [marketCurrency]="marketCurrency"></app-price-hint>
```

(`get marketCurrency(): string { return this.isKz ? 'KZT' : 'RUB'; }`; стиль `.pr-price { padding: 0 8px 6px 36px; }`, на
телефоне — `padding-left: 8px`.)

`pr-rfq-dialog.component.ts` — рядом с блоком «Госзакуп по позициям»:

```html
        <div class="mkt" *ngIf="priceRows().length">
          <div class="mkt-title">По прайсам поставщиков</div>
          <div class="mkt-row" *ngFor="let h of priceRows()"><span class="mkt-name">{{ h.name }}</span> — {{ h.line }}</div>
        </div>
```

```ts
  /** Позиция → лучшая строка прайса (спека 2026-10-23 §6.2 п. 2) — из вида заявки, без запроса. */
  priceRows(): { name: string; line: string }[] {
    const cur = this.marketSvc.value === 'KZ' ? 'KZT' : 'RUB';
    return this.lines.map(l => ({ name: l.name, line: hintLine(l.priceList, cur) || '' })).filter(h => !!h.line);
  }
```

- [ ] **Step 3: Лот тендера и панель «КП»**

`tender-lots.component.ts` — после строки `.lot-mkt`:

```html
          <div class="lot-price" *ngIf="l.priceList?.rows?.length" (click)="openPrice(l, $event)" title="Цены из прайсов поставщиков — подробно">
            {{ priceLine(l) }}</div>
```

и рядом с панелью цен рынка:

```html
        @defer (when priceOpen.has(l.id)) {
          <app-price-hint *ngIf="priceOpen.has(l.id)" [hint]="l.priceList" [marketCurrency]="cur()" [open]="true"
                          (closed)="priceOpen.delete(l.id)"></app-price-hint>
        }
```

```ts
  /** Лоты, у которых открыта панель цен из прайсов. */
  priceOpen = new Set<number>();

  cur(): string { return this.isKz() ? 'KZT' : 'RUB'; }

  /** «📒 закупка по прайсу: Аксион 150 000 руб. → 900 000 тг · 83 % бюджета». */
  priceLine(l: any): string {
    const line = hintLine(l.priceList, this.cur()) || '';
    return line.replace('📒 ', '📒 закупка по прайсу: ') + (l.priceBudgetPct != null ? ` · ${l.priceBudgetPct} % бюджета` : '');
  }

  openPrice(l: any, e?: Event) {
    e?.stopPropagation();
    if (this.expandedLotId !== l.id) {
      if (this.expandedLotId !== null) this.closePanelsOf(this.expandedLotId);
      this.expandedLotId = l.id;
      this.priceOpen.add(l.id);
    } else if (this.priceOpen.has(l.id)) this.priceOpen.delete(l.id);
    else this.priceOpen.add(l.id);
    this.cdr.detectChanges();
  }
```

`closePanelsOf(id)` — ещё `this.priceOpen.delete(id);`. `PriceHintComponent` — в `imports` (только внутри `@defer` — в
отдельный чанк); `hintLine` — из `../../shared/price-hint` (в начальном бандле — только эта функция). Стиль — как `.lot-mkt`.

`lot-kp-panel.component.ts` — у чипа причины: `[class.reason-price]="r.kind === 'PRICE_LIST'"`, префикс — пусто (подпись
сервера уже «прайс: …»): `{{ r.kind === 'TYPE' ? '✓ ' : r.kind === 'MAKER' || r.kind === 'PRICE_LIST' ? '' : 'возит ' }}`;
стиль `.reason-price { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }`.

- [ ] **Step 4: «Наша продукция → Товары» — закупка и «Без прайса»**

`shared/product-watch.ts` — `ProductCatalogItem`: `priceList?: PriceHint | null;` (`import type`). `product-catalog.component.ts`
(страница ленивая — компонент подсказки прямо, `PriceHintComponent` в `imports`): `type Filter = 'ALL' | 'NO_ATTR' | 'BAD_RU' |
'NO_PRICE';`, в `filters` — `{ key: 'NO_PRICE', label: 'Без прайса' }`, в `passes` — `if (f === 'NO_PRICE') return
!p.priceList?.rows?.length;`; под `.pc-meta` строки товара (на всю ширину карточки):

```html
          <app-price-hint class="pc-price" *ngIf="p.priceList?.rows?.length; else noPrice" [hint]="p.priceList" marketCurrency="KZT"></app-price-hint>
          <ng-template #noPrice><span class="pc-buy-none">нет в прайсах</span></ng-template>
```

(стиль `.pc-price, .pc-buy-none { grid-column: 1 / -1; } .pc-buy-none { font-size: 12px; color: var(--text-muted); }`; разворот
подсказки — с историей цен товара, спека §7.)

- [ ] **Step 5: КП — «Поставщик ▾» со строками прайсов, «📒 из прайса»**

`shared/client-offer.ts` — `SupplierOffer`:

```ts
export interface SupplierOffer {
  priceRequestItemId: number | null; supplierName: string; price: number | null; respondedOn: string | null;
  accepted: boolean; cheapest: boolean; selected: boolean;
  /** запрос ответа — ссылка на экран сверки; auto — 💡 цена ещё не сверена */
  priceRequestId?: number | null; auto?: boolean;
  /** строка прайса (спека 2026-10-23 §6.3); currency / original / rate — исходная цена в чужой валюте */
  priceListItemId?: number | null; priceListId?: number | null; currency?: string | null; original?: number | null;
  rate?: number | null; listDate?: string | null; expired?: boolean;
}
```

`client-offer-items.component.ts`:
- `canPick(it)` — `!this.readonly && !!it.id && it.kind === 'ITEM'` (ответы или строки прайсов есть у любой сохранённой
  строки — спека §6.3); текст пустого списка — «Ответов и строк прайсов по этой позиции нет»;
- пункт списка — у строки прайса своя подпись и выбор только с ценой:

```html
              <button type="button" class="offer" *ngFor="let s of offersList" [class.on]="s.selected" [disabled]="s.price == null"
                      (click)="pickOffer(it, s)">
                <span class="ok" aria-hidden="true">{{ s.selected ? '✓' : '' }}</span>
                <span class="nm">{{ s.priceListItemId ? '📒 ' : '' }}{{ s.supplierName }}</span>
                <span class="pr" *ngIf="s.priceListItemId; else replyPrice">
                  <ng-container *ngIf="s.original != null">{{ numText(s.original) }} {{ short(s.currency) }} × {{ rateText(s.rate) }} = </ng-container>{{ s.price == null ? 'впишите наш курс' : money(s.price) }}</span>
                <ng-template #replyPrice><span class="pr">{{ money(s.price) }}<span *ngIf="s.auto" title="не сверено"> 💡</span></span></ng-template>
                <span class="wh" *ngIf="s.priceListItemId">прайс {{ s.listDate ? dayMonth(s.listDate) : '' }}<ng-container *ngIf="s.expired"> · истёк</ng-container></span>
                <span class="wh" *ngIf="!s.priceListItemId && s.respondedOn">ответил {{ dayMonth(s.respondedOn) }}</span>
                <span class="tag" *ngIf="s.cheapest">дешевле всех</span>
                <span class="tag" *ngIf="s.accepted">принято</span>
              </button>
```

- `pickOffer`:

```ts
  /** Выбор ответа или строки прайса: цена, поставщик, исходная цена и ссылка → автосохранение (поставщика выведет сервер). */
  pickOffer(it: OfferItem, s: SupplierOffer) {
    if (s.price == null) return;
    const foreign = !!s.currency && s.currency !== this.offer.currency && s.original != null;
    it.purchasePrice = s.price;
    it.purchaseCurrency = foreign ? s.currency! : null;
    it.purchaseOriginal = foreign ? s.original! : null;
    it.purchaseRate = foreign ? s.rate ?? null : null;
    it._cur = null;
    it.supplierName = s.supplierName;
    it.priceRequestItemId = s.priceRequestItemId ?? null;
    it.priceListItemId = s.priceListItemId ?? null;
    it.priceListId = s.priceListId ?? null;
    it.priceListLabel = s.priceListItemId ? `${s.supplierName} от ${s.listDate ? dateText(s.listDate) : ''}` : null;
    it.purchaseUnverified = !!s.auto;
    it.replyRequestId = s.priceRequestId ?? null;
    this.offersFor = null;
    this.emit();
  }
```

- метка у закупки и ссылка в «Подробнее»: внутри `.buy-wrap` рядом с 💡 —
  `<span class="pl-mark" *ngIf="it.priceListItemId" title="Закупка из прайса поставщика — запроса цен не было" aria-hidden="true">📒</span>`
  (у поля — `[class.with-unv]="it.purchaseUnverified || it.priceListItemId"`); в «Подробнее» рядом с «Ответ поставщика ›»:

```html
            <a class="reply-link" *ngIf="it.priceListId" [routerLink]="['/price-lists', it.priceListId]">📒 из прайса {{ it.priceListLabel }} ›</a>
```

- [ ] **Step 6: «Из реестра и каталога» — группа «Прайсы поставщиков»**

`offer-add-item-dialog.component.ts`:
- `export type AddPick = … | { kind: 'price'; row: PriceHintRow };`; в `forkJoin` — `prices: this.api.searchPriceLists(q).pipe(fail('прайсы'))`,
  поле `prices: PriceHintRow[] = [];`, сообщение «Ничего не нашлось» — и с учётом `prices.length`; заголовок окна —
  `this.kz ? 'Из реестра, каталога и прайсов' : 'Из каталога и прайсов'`;
- группа первой:

```html
      <div class="group" *ngIf="prices.length">
        <div class="g-title">Прайсы поставщиков</div>
        <button type="button" class="hit" *ngFor="let r of prices" (click)="picked.emit({ kind: 'price', row: r })">
          <span class="h-name">{{ r.name }}</span>
          <span class="h-meta">{{ r.supplierName }} · прайс {{ r.listDate ? r.listDate.slice(8, 10) + '.' + r.listDate.slice(5, 7) : '' }}
            · {{ money(r.price, r.currency) }}<ng-container *ngIf="r.converted != null && r.currency !== marketCurrency"> → {{ money(r.converted, marketCurrency) }}</ng-container><span class="expired" *ngIf="r.expired"> · прайс истёк</span></span>
        </button>
      </div>
```

  (`money(v, c)` — `hintMoney`; `get marketCurrency(): string { return this.kz ? 'KZT' : 'RUB'; }`.)

`client-offer-items.component.ts` — `addPicked`, ветка `price`:

```ts
    } else if (p.kind === 'price') {
      const r = p.row;
      it.name = cutWords(r.name, 500);
      it.model = r.model ?? null;
      it.manufacturer = r.manufacturer ? r.supplierName.slice(0, 500) : null;   // поставщик-завод; иначе производитель пуст
      const foreign = r.currency !== this.offer.currency;
      if (!foreign || r.converted != null) {
        it.purchasePrice = foreign ? r.converted : r.price;
        it.purchaseCurrency = foreign ? r.currency : null;
        it.purchaseOriginal = foreign ? r.price : null;
        it.purchaseRate = foreign ? r.ourRate : null;
        it.priceListItemId = r.rowId;
        it.priceListId = r.priceListId;
        it.priceListLabel = `${r.supplierName} от ${r.listDate ? dateText(r.listDate) : ''}`;
        it.supplierName = r.supplierName;
      } else {
        this.notify.error(`Нашего курса ${r.currency} нет — закупку впишите сами или задайте курс в «Система → Курсы валют»`);
      }
      // РУ: номер есть в реестре — «РУ ✓» (только KZ); «нет РУ» в прайсе — «РУ не найдено»; иначе «не проверено»
      if (this.isKz && r.regNumber && (r.regStatus === 'ACTIVE' || r.regStatus === 'EXPIRED')) {
        it.registrationStatus = 'CONFIRMED';
        it.regNumber = r.regNumber;
        if (this.offer.vatEnabled && prof && 'vatRegistered' in prof) it.vatRate = prof.vatRegistered;
      } else if (this.isKz && r.regStatus === 'NONE') {
        it.registrationStatus = 'NOT_FOUND';
      }
    }
```

- [ ] **Step 7: Сборка и проверка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -15`
Expected: без ошибок; начальный бандл ≤ 1,5 МБ (`app-price-hint` в «Тендерах» — только под `@defer`; проверить, что в
выходном начальном чанке нет текста «По прайсам поставщиков»: `grep -l 'По прайсам поставщиков' dist/frontend/browser/main-*.js`
— пусто).

Живьём (прайс Аксиона-образца сохранён и действует, наш курс руб. 6): частная заявка с позицией «Дефибриллятор ДКИ-Н-12» —
строка «📒 …», разворот, история; «Запросить цены…» — блок «По прайсам поставщиков»; тендер с моделью в работе ДКИ-Н-12 —
«📒 закупка по прайсу: … · N % бюджета», панель; «КП» лота — чип «прайс: 150 000 руб.» у поставщика; «Товары» — колонка и
«Без прайса»; КП: «Поставщик ▾» у строки не из заявки — строка «📒 …», выбор → закупка в руб., «📒» у поля и «📒 из прайса …
›»; «Из реестра и каталога» — группа «Прайсы поставщиков» → строка КП с «РУ ✓»; «Создать КП клиенту» по заявке без ответов
— закупка из прайса; 1280 / 390, обе темы; оператор — только просмотр (0 запросов записи), РФ — прайсы своего рынка.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src && git commit -m "feat(price-lists): подсказки «📒 по прайсу» в заявке, тендере, «КП», «Товарах»; прайсы в «Поставщик ▾» и «Из реестра и каталога»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

### Task 15: Полный прогон, гейт на настоящих прайсах, живая проверка, документация, ревью ветки, мерж

**Files:**
- Modify: `CLAUDE.md` (§5 — подраздел «Прайсы и курсы — локально»; §7 — V44; §8 — блок «Прайс-листы поставщиков и курсы
  валют»; §12 — «Прайсы — фронт»; §13 — число тестов; §15 — пути; §16 — что осталось), `DEPLOY.md` (§13 «Курсы валют и
  прайсы поставщиков»), `docs/PROGRESS.md` («▶ Последняя задача», шапка), `docs/superpowers/specs/2026-10-23-price-lists-
  and-rates-design.md` (§14 — решения при реализации)

- [ ] **Step 1: Полный прогон**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill; ./gradlew cleanTest test 2>&1 | tail -30`
Expected: 0 падений (на 2026-10-22 — 2695 тестов, 6 skipped; новых ≈ 120; skipped — ещё `PriceListCorpusTool`). Упало чужое —
разбираться по §14 CLAUDE.md (живая nirdb, синглтоны, общий контекст почтовых тестов), не «чинить» ожидания.

- [ ] **Step 2: Гейт на настоящих прайсах (Review Focus 5 и спека §12)**

Копии прайсов Аксиона (PDF) и Орион-Си (Word) — в папку вне репозитория (например, `~/Downloads/прайсы-гейт/`). Run:
`cd /Users/vlad/IdeaProjects/AIS && PRICE_LIST_CORPUS=~/Downloads/прайсы-гейт ./gradlew test --tests '*PriceListCorpusTool*' -i 2>&1 | grep -v '^\s*$' | tail -120`
Сверить глазами с файлами: Аксион — 36 строк, у каждой обе цены по своим колонкам («Оптовая» / «Дилерская»), наша колонка —
дилерская, разделы («Реанимация», …), РУ из объединённых ячеек у каждой строки, дата 01.09.2026 и «до» 31.12.2026, валюта RUB,
условия («Цены указаны…», «Предоплата…»); Орион — 7 строк с ценами в рублях, раздел «СТЕРИЛИЗАТОРЫ…», дата 01.11.2025,
«розничный» = да. **Перепутано 0** (цена не той строки, не той колонки). Расхождение — правка разбора с тестом на
синтетическом файле той же вёрстки (настоящие файлы в репозиторий не кладутся), затем повтор гейта.

- [ ] **Step 3: Живая проверка (спека §12)**

Стек: бэкенд с приёмом zakup@ на GreenMail (CLAUDE.md §5, «Почта zakup@ и Telegram — локально», без Telegram), `./gradlew
devMail`, `cd frontend && npm start`. Записать ДО проверки: `select max(id) from price_list; select count(*) from currency_rate;
select count(*) from official_rate; select max(id) from client_offer; select max(id) from inbound_email;`. Пройти по задачам
12–14 (их «Живьём»), плюс: Нацбанк включён (`RATES_NBRK_ENABLED=true`) — в логе при старте строка курсов, «Курсы валют»
показывают курс сегодня с датой; Нацбанк недоступен (`RATES_NBRK_BASE_URL=http://127.0.0.1:9`) — страница и КП работают,
курс — прежний с датой; 1280 / 390, обе темы; оператор (0 запросов записи); РФ (курс сегодня не показывается, прайсы — свои).
Уборка (только dev): остановить бэкенд, затем `delete from price_list where id > <max>; delete from inbound_email where id > <max>;
delete from client_offer where id > <max>;` (строки, файлы, ссылки — каскадом и `SET NULL`), курсы — вернуть как было
(`delete from currency_rate;` / `delete from official_rate;`, если до проверки их не было), курсор zakup@ — по CLAUDE.md §5.

- [ ] **Step 4: Документация**

- `DEPLOY.md` — новый раздел «## 13. Курсы валют и прайсы поставщиков»: код приезжает деплоем (V44 — таблицы и колонки, сид
  роли «РУ»); прайсы и наш курс работают сразу (страницы «Система → Курсы валют», «Каталог → Прайсы поставщиков»); курс НБ РК
  — `RATES_NBRK_ENABLED=true` в `/srv/ais/.env` (пустым не оставлять) → `docker compose up -d ais-backend` → в логе строка
  сбора курса; доступность Нацбанка с прода проверить до включения: `docker compose exec -T ais-backend sh -c 'wget -qO-
  "https://nationalbank.kz/rss/get_rates.cfm?fdate=$(date +%d.%m.%Y)" | head -c 300'` (недоступен — как с Telegram, §8: адрес
  из другого DNS и `extra_hosts`); проверки — `select currency, rate_date, rate from official_rate order by rate_date desc limit 8;`,
  `select market, currency, our_rate from currency_rate;`, `select status, count(*) from price_list group by 1;`; **откат кода
  ниже V44** — сперва `delete from header_synonym where field = 'REG';` (прежний бэкенд роль «РУ» не знает и уронил бы чтение
  словаря заголовков, как V30), прочее прежний код не читает.
- `CLAUDE.md` — §5 подраздел «Прайсы и курсы — локально» (переменные `RATES_NBRK_*`, заглушка Нацбанка не нужна — сбой
  проверяется адресом `127.0.0.1:9`, `dev-mail.py price <файл>`, что записать до проверки и уборка — как в шаге 3); §7 — абзац
  «Прайсы и курсы (V44)» (таблицы и колонки задачи 1); §8 — блок «Прайс-листы поставщиков и курсы валют» в том же стиле, что
  соседние (зачем, наш курс и курс сегодня, ответ в рублях, КП, разбор, экран проверки, связь, подсказки, история,
  «Входящие», проверено — тесты и мутации по задачам, гейт, живьём); §12 — «Прайсы и курсы — фронт» (страницы, `app-price-hint`
  под `@defer` в «Тендерах», переключатель валюты, `_cur`); §13 — число тестов полного прогона; §15 — пути `/api/currency-rates`,
  `/api/price-lists…`, новые поля ответов; §16 — что не делалось (спека §13 и мелочи ревью ветки).
- Спека §14 — решения при реализации (где код разошёлся с текстом спеки: решения плана 14–17, правило курса ответа в
  `checkPurchase` (задача 10), производитель строки КП из прайса — поставщик-завод, бренд из наименования не угадывается,
  `PUT /responses` — поля `responseCurrency` / `responseOriginal`).
- `docs/PROGRESS.md` — «▶ Последняя задача»: блок сделан, в main, ждёт push оператора; включение курса НБ РК — DEPLOY.md §13.
- Память `last-task-pointer.md` — строка про блок.

- [ ] **Step 5: Ревью ветки — самому (CLAUDE.md §2)**

`git diff main...feature/price-lists-and-rates` — целиком свежим взглядом: гард рынка у каждого пути по id; записи — только
ADMIN; сеть вне транзакций; ни одного `findById` без сверки рынка там, где запись приходит от клиента; в начальном бандле —
нет страниц прайсов (маркерами); все пять пунктов Review Focus закрыты тестами (имена тестов — в плане); мутации задач
перепроверены выборочно (по одной на задачу). Найденное — правка с тестом, красным до неё.

- [ ] **Step 6: Мерж**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md DEPLOY.md docs/PROGRESS.md docs/superpowers/specs/2026-10-23-price-lists-and-rates-design.md && git commit -m "docs: прайс-листы поставщиков и курсы валют — CLAUDE.md, DEPLOY.md §13, PROGRESS

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" && git checkout main && git merge --ff-only feature/price-lists-and-rates && git branch -d feature/price-lists-and-rates
```

(в `docs/` лежат чужие неотслеживаемые файлы — диплом, копии записки: `git add` — только перечисленные пути.) Push — оператор
(`! git push origin main`); после деплоя — проверки DEPLOY.md §13 (SSH — с согласия оператора).
