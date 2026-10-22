# Итоги тендеров, «звонить заказчику» и цены рынка — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> В этом проекте — **только executing-plans в основной сессии, без субагентов** (CLAUDE.md §2).

**Goal:** после срока приёма АИС сама узнаёт итоги тендеров goszakup (участники, цены, победитель, договор, цепочка попыток), шлёт в Telegram «звонить заказчику» по несостоявшимся и тихую сводку, а у лота, в пачке бота, «Новом тендере», частной заявке, КП и у товаров показывает цену рынка по подписанным договорам за 2 года.

**Architecture:** новый клиент площадки `GoszakupResultsClient` (v3 GraphQL: объявления пачкой, договоры, цепочки по пункту плана, состоявшиеся лоты по слову, файл протокола). Пакет `service.tenderresults`: разбор протоколов (`protocol/`: ЗЦП — PDF, ОИ и конкурс — HTML/PDF текстом), запись итогов (`TenderResultsWriter`), события и маршрутизация сигналов (`ResultEvents`), проход (`TenderResultsService`), Telegram (`TenderResultsComposer` + `TenderResultsNotifier`), расписание (`TenderResultsScheduler`, свой поток `tender-results`). Пакет `service.market`: набор договоров (`MarketCollector` + `MarketContractWriter`), расчёт (`MarketStats`, `MarketCategories`, `MarketPriceService`). V43 — 6 таблиц и 4 поля лота. Фронт — ленивые панели итога, цен рынка и заказчика.

**Tech Stack:** Spring Boot 3.5.6 / Java 17, JPA (Hibernate 6), Flyway, PostgreSQL 17, PDFBox 3 (`PdfTextExtractor`), Jsoup; Angular 21 standalone; JUnit 5, AssertJ, MockMvc, JDK HttpServer (заглушка goszakup).

**Spec:** `docs/superpowers/specs/2026-10-22-tender-results-market-prices-design.md`

## Global Constraints

- Только рынок KZ и площадка goszakup: проход итогов берёт `market = 'KZ' AND platform = 'GOSZAKUP' AND source = 'PUBLIC_TENDER'`; пути цен рынка на РФ — 400 «Только для Казахстана»; на РФ экраны не меняются.
- Токен goszakup общий с продом: пауза 300 мс между запросами (`PAUSE_MS`), 401 / 403 / 429 останавливают проход (`ProductWatchService.stopping`), повтор временных сбоев — `UpstreamRetry.call`.
- Сеть (goszakup, Telegram) — вне транзакций; запись — `@Transactional`-методами отдельных бинов (CLAUDE.md §6); фоновый поток ставит `MarketContext.set(Market.KZ)` и `clear()` в `finally`.
- Telegram: `sendProducts(text, silent)` / `sendTenders(text, silent)` — простой текст; поля площадки — `TelegramText.oneLine`, сообщение ≤ 3800 знаков; «звонить» по нашим — со звуком, остальное — тихо.
- Время площадки — местное UTC+5 без пояса (`LocalDateTime`, `ImportDeadline.parse`); «сейчас» для сроков — `LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone())`.
- Тестовые протоколы — только обезличенные (поставщики → «Поставщик N» и БИН `9000000000NN`, люди → «Сотрудник N», бенефициары, паспорта, адреса и счета — вон): репозиторий публичный.
- `TENDER_RESULTS_ENABLED` в `.env` пустым не оставлять — пустой булев env роняет старт (CLAUDE.md §14).
- Каждый коммит — строка `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- `./gradlew` и psql — с `dangerouslyDisableSandbox: true`; перед прогоном: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill`. Команды — из корня: `cd /Users/vlad/IdeaProjects/AIS && …`. Логи прогонов — в `.superpowers/sdd/2026-10-22-tender-results-market-prices/`.
- Фронт: новые панели — ленивые (`@defer`), начальный бандл ≤ 1,5 МБ (сейчас 1,45); результаты промисов — `.then`; классы компонентов — не `.empty / .subtitle / .counter / .badge / .form-actions / .field-error` (UI-kit); телефон — карточки (`card-grid`), цели 40 px.
- Ветка — `feature/tender-results-market-prices`; мерж в `main` — `--ff-only`; push — оператор (`! git push origin main`).
- Тест — красный до правки по своей причине; после зелёного — мутация (сломать правку → нужный тест краснеет → вернуть копией файла, не `git checkout`, CLAUDE.md §14).

## Review Focus

1. Протокол незнакомого вида (аукцион, «тендер», новая вёрстка) или битый файл — лот получает «протокол не разобран» и ссылку, проход не падает, сигнал по статусу площадки и цепочке всё равно приходит (тесты — задачи 3 и 5).
2. Площадка отвечает 403 / 429 посреди прохода — проход останавливается, ничего не помечается окончательным и не теряется, следующий проход продолжает (тест — задача 5).
3. Один итог не приходит дважды: повторные проходы, «Проверить итоги» рядом с часовым проходом, рестарт — событие создаётся один раз (`event_at`, проверка вида, у событий цепочки — `ref_key`), отправка ставит `notified_at` после отправки (тесты — задачи 5, 6 и 7).
4. Лот без количества или без цены: цена конкурса «за лот» без количества, сумма ОИ без количества лота, бюджет без количества — цена за единицу пустая, а не деление на ноль (тесты — задачи 3, 5 и 10).
5. Категория с одной строкой, без строк или в упаковках — «договоров нет» вместо пустой панели, медиана одной строки — она сама, штуки не смешиваются с упаковками (тесты — задача 11).

## Решения при планировании (где план точнее спеки)

1. **«Звонить» — ещё и «не состоялся по другой причине»** (`FAILED_OTHER`: все заявки отклонены и т. п.): итог тот же — объявят заново. «1 заявка» — в сводку (спека §7).
2. **Причина «1 заявка» без разобранного протокола** выводится из цепочки: после провала сразу опубликована закупка из одного источника — значит, была одна заявка.
3. **События цепочки** (`REPEAT`, `OI_DONE`, `OI_FAILED`) — только по попыткам, которых нет в АИС; попытка в АИС даёт свои события сама. Появление закупки из одного источника отдельным событием не идёт — его называет текст «1 заявка → из одного источника у X».
4. **«Наш лот» наследуется по пункту плана:** лот повторной попытки (своя карточка в АИС) — наш, если наш лот той же закупки.
5. **Автоимпорт повторов — только запрос и конкурс:** закупку из одного источника не заводим (она закреплена за одним поставщиком и попала бы в пачку как «потенциально наш тендер»).
6. **Сбой прохода для оповещения** — только отказ API площадки; не скачанный или не разобранный протокол — пометка у лота, а не ошибка прохода (иначе «⚠️ не работает» приходило бы из-за одного файла).
7. **Файл протокола качается без токена** (он публичный) и только с хоста площадки `…/download/`, не больше 5 МБ.
8. **Подсказка цены в окне «Запросить цены»** — одним блоком над поставщиками («Госзакуп по позициям»), а не у позиции в каждом поставщике (повторялась бы N раз).
9. **Строка «рынок» в Telegram** — в пачке бота (`ProductDigestComposer`, на проде) и «🏥 Новом тендере»; прежнее сообщение без кнопок бота — без строки.
10. **У лотов в ответе карточки** цена рынка — только из базы (без запросов к площадке); живой запрос — в панели по нажатию и перед отправкой пачки.
11. **«Проверить итоги»** идёт в том же потоке `tender-results` и ждёт до 60 с; не успело — «проверка идёт, обновите позже».
12. **Подписи оповещения о сбое:** «Проверка итогов тендеров» и «Сбор цен рынка» (`ImportOutageNotifier.afterProductRun`).

---

### Task 1: Данные (V43), сущности и репозитории

**Files:**
- Create: `src/main/resources/db/migration/V43__tender_results_market_prices.sql`
- Create: `src/main/java/com/vladoose/nir/entity/LotOutcome.java`, `FailReason.java`, `ProtocolStatus.java`, `ResultEventKind.java`, `ResultTopic.java`, `ResultAttempt.java`, `TenderLotResult.java`, `TenderLotBid.java`, `TenderResultEvent.java`, `MarketContract.java`, `MarketLookup.java`, `GoszakupSubject.java`
- Modify: `src/main/java/com/vladoose/nir/entity/TenderLot.java` (4 поля после `modelByAi`), `src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java` (поля площадки не пишутся из запроса)
- Create: `src/main/java/com/vladoose/nir/repository/TenderLotResultRepository.java`, `TenderLotBidRepository.java`, `TenderResultEventRepository.java`, `MarketContractRepository.java`, `MarketLookupRepository.java`, `GoszakupSubjectRepository.java`
- Modify: `src/main/java/com/vladoose/nir/repository/TenderLotRepository.java`, `TenderRepository.java`
- Test: `src/test/java/com/vladoose/nir/tenderresults/TenderResultsSchemaTest.java`, `src/test/java/com/vladoose/nir/tenderresults/ResultsFixtures.java`

**Interfaces:**
- Produces: `enum LotOutcome { PENDING, DONE, FAILED, CANCELLED; static LotOutcome ofLotStatus(Integer) }`; `enum FailReason { NO_BIDS, ONE_BID, REFUSED, OTHER }`; `enum ProtocolStatus { PARSED, UNKNOWN_FORMAT, FAILED, NO_FILE }`; `enum ResultEventKind { DONE, NO_BIDS, ONE_BID, FAILED_OTHER, OI_DONE, OI_FAILED, CANCELLED, CONTRACT, REPEAT, OUR_BID; boolean urgent() }`; `enum ResultTopic { PRODUCTS, TENDERS, NONE }`; `record ResultAttempt(String anno, String lotNumber, String method, Integer statusId, String status, String publishedAt, String resultsAt, String supplierBin, Long tenderId)`; сущности `TenderLotResult`, `TenderLotBid`, `TenderResultEvent`, `MarketContract`, `MarketLookup`, `GoszakupSubject` (поля — в коде ниже); `TenderLot.extLotId / planPointId / enstruCode / planUnit`; `TenderLotResultRepository.findByTenderLotId(Long)`, `findByTenderId(Long)`, `findByTenderIdIn(Collection<Long>)`, `findByTenderLotIdIn(Collection<Long>)`, `findOpenFailed()`, `findDoneWithoutContract()`; `TenderLotBidRepository.findByResultIdOrderByPlaceAscIdAsc(Long)`, `findByResultIdIn(Collection<Long>)`, `deleteByResultId(Long)`; `TenderResultEventRepository.findUrgentPending()`, `findDigestPending()`, `findByTenderIdOrderByIdAsc(Long)`, `existsByTenderLotIdAndKind(Long, ResultEventKind)`, `existsByTenderLotIdAndKindAndRefKey(Long, ResultEventKind, String)`; `MarketContractRepository.findByLotExtIdIn(Collection<Long>)`, `findByEnstruCodeAndSignedAtGreaterThanEqual(String, LocalDate)`, `findByCustomerBinAndSignedAtGreaterThanEqualOrderBySignedAtDesc(String, LocalDate)`, `findCategories(String pattern, LocalDate from)`; `TenderLotRepository.findByPlanPointIdIn(Collection<Long>)`, `findOurLotIds(Collection<Long>)`; `TenderRepository.findResultCandidates(LocalDateTime now, LocalDateTime from, int limit)`.

- [ ] **Step 1: Тест схемы, «наших» лотов и кандидатов прохода**

`ResultsFixtures` — общий помощник тестов блока (тендер goszakup с лотами):

```java
package com.vladoose.nir.tenderresults;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Тендеры goszakup для тестов итогов: номер объявления «<id>-1», лоты с кодами площадки. */
public final class ResultsFixtures {

    private ResultsFixtures() {}

    /** lots — пары «код лота площадки, наименование». Имя другое, чем у {@link #tender}: две перегрузки с String... неоднозначны. */
    public static Tender tenderIn(TenderRepository tenders, long buyId, LocalDateTime deadlineAt, String region, String... lots) {
        Tender t = Tender.builder().tenderNumber(buyId + "-1").sourceExtId(buyId + "-1").status("COMPLETED")
                .source(Source.PUBLIC_TENDER).platform(TenderPlatform.GOSZAKUP).market(Market.KZ).currency("KZT")
                .description("ZZ закупка " + buyId).customerName("ГКП «ZZ больница»").customerBin("110240019426")
                .region(region).deadline(deadlineAt.toLocalDate()).deadlineAt(deadlineAt).build();
        for (int i = 0; i + 1 < lots.length; i += 2) {
            t.getLots().add(TenderLot.builder().tender(t).sourceLotCode(lots[i]).equipName(lots[i + 1]).quantity(3)
                    .maxCost(new BigDecimal("613900.00")).techSpecStatus(TechSpecStatus.PENDING).build());
        }
        return tenders.saveAndFlush(t);
    }

    public static Tender tender(TenderRepository tenders, long buyId, LocalDateTime deadlineAt, String... lots) {
        return tenderIn(tenders, buyId, deadlineAt, "Западно-Казахстанская область", lots);
    }

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 10, 0);
    public static final LocalDate TODAY = NOW.toLocalDate();
}
```

```java
package com.vladoose.nir.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** V43 (спека 2026-10-22-tender-results-market-prices §12): колонки, JSONB цепочки, «наш лот», кандидаты прохода. */
@SpringBootTest
@Transactional
class TenderResultsSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotResultRepository results;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void v43Columns() {
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'tender_lot' "
                + "and column_name in ('ext_lot_id', 'plan_point_id', 'enstru_code', 'plan_unit')", String.class)).hasSize(4);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name in ('tender_lot_result', "
                + "'tender_lot_bid', 'tender_result_event', 'market_contract', 'market_lookup', 'goszakup_subject')", Integer.class))
                .isEqualTo(6);
    }

    @Test
    void attemptsRoundTrip() {
        Tender t = ResultsFixtures.tender(tenders, 970000001L, NOW.minusDays(2), "88214663-ЗЦП2", "Тележка");
        TenderLotResult r = results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId())
                .tenderId(t.getId()).outcome(LotOutcome.FAILED)
                .attempts(List.of(new ResultAttempt("17780275-1", "88208881-ЗЦП2", "ЗЦП", 370, "Закупка не состоялась",
                        "2026-10-14 11:42:00", "2026-10-16 20:19:00", null, null))).build());
        em.clear();
        assertThat(results.findById(r.getId()).orElseThrow().getAttempts()).extracting(ResultAttempt::anno).containsExactly("17780275-1");
    }

    /** Наш лот (спека §7): модель в работе / запрос цен / заявка — или отметка «наш товар» в тендере из пачки, кроме «не наше». */
    @Test
    void ourLots() {
        Tender t = ResultsFixtures.tender(tenders, 970000002L, NOW.minusDays(2),
                "1-ЗЦП1", "С моделью", "2-ЗЦП1", "С отметкой", "3-ЗЦП1", "ИИ: не наше", "4-ЗЦП1", "Без всего");
        t.setProductNotifiedLevel(ProductMatchLevel.TYPE);
        TenderLot a = t.getLots().get(0), b = t.getLots().get(1), c = t.getLots().get(2), d = t.getLots().get(3);
        c.setAiVerdict(AiVerdict.NO);
        tenders.saveAndFlush(t);
        Long eq = jdbc.queryForObject("insert into med_equipment (name, manufact, market) values ('ZZ модель', 'ZZ', 'KZ') returning id", Long.class);
        jdbc.update("update tender_lot set proposed_equipment_id = ? where id = ?", eq, a.getId());
        Long group = jdbc.queryForObject("insert into product_group (market, name) values ('KZ', 'ZZ группа') returning id", Long.class);
        for (TenderLot l : List.of(b, c)) {
            jdbc.update("insert into tender_lot_product_match (tender_id, lot_id, group_id, level, source) values (?, ?, ?, 'TYPE', 'SEARCH')",
                    t.getId(), l.getId(), group);
        }
        assertThat(lots.findOurLotIds(List.of(a.getId(), b.getId(), c.getId(), d.getId())))
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    /** Кандидаты прохода: срок прошёл не раньше 45 дней назад, есть неокончательный лот; частные заявки и будущие — нет. */
    @Test
    void candidates() {
        Tender passed = ResultsFixtures.tender(tenders, 970000003L, NOW.minusDays(2), "5-ЗЦП1", "Прошёл");
        Tender future = ResultsFixtures.tender(tenders, 970000004L, NOW.plusDays(1), "6-ЗЦП1", "Ещё идёт");
        Tender old = ResultsFixtures.tender(tenders, 970000005L, NOW.minusDays(60), "7-ЗЦП1", "Давно");
        Tender done = ResultsFixtures.tender(tenders, 970000006L, NOW.minusDays(3), "8-ЗЦП1", "Итог окончательный");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(done.getLots().get(0).getId()).tenderId(done.getId())
                .outcome(LotOutcome.DONE).finalAt(OffsetDateTime.now()).build());
        Tender priv = ResultsFixtures.tender(tenders, 970000007L, NOW.minusDays(2), "9-ЗЦП1", "Частная");
        priv.setSource(Source.PRIVATE_REQUEST);
        tenders.saveAndFlush(priv);
        List<Long> ids = tenders.findResultCandidates(NOW, NOW.minusDays(45), 500);
        assertThat(ids).contains(passed.getId()).doesNotContain(future.getId(), old.getId(), done.getId(), priv.getId());
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.tenderresults.TenderResultsSchemaTest' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция: нет `TenderLotResult`, `ResultAttempt`, `findOurLotIds`, `findResultCandidates`.

- [ ] **Step 3: Миграция V43**

```sql
-- Итоги тендеров goszakup, «звонить заказчику» и цены рынка из договоров (спека 2026-10-22-tender-results-market-prices §12):
-- поля площадки у лота, итог лота, участники протокола, события итогов, набор договоров, справочники.
ALTER TABLE tender_lot ADD COLUMN ext_lot_id BIGINT;
ALTER TABLE tender_lot ADD COLUMN plan_point_id BIGINT;
ALTER TABLE tender_lot ADD COLUMN enstru_code VARCHAR(30);
ALTER TABLE tender_lot ADD COLUMN plan_unit VARCHAR(50);
CREATE INDEX idx_tender_lot_plan_point ON tender_lot (plan_point_id) WHERE plan_point_id IS NOT NULL;

CREATE TABLE tender_lot_result (
    id                    BIGSERIAL PRIMARY KEY,
    tender_lot_id         BIGINT      NOT NULL UNIQUE REFERENCES tender_lot (id) ON DELETE CASCADE,
    tender_id             BIGINT      NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    lot_status_id         INT,
    lot_status            VARCHAR(100),
    outcome               VARCHAR(12) NOT NULL,
    fail_reason           VARCHAR(12),
    fail_text             VARCHAR(300),
    results_at            TIMESTAMP,
    protocol_url          VARCHAR(300),
    protocol_name         VARCHAR(200),
    protocol_status       VARCHAR(16),
    protocol_note         VARCHAR(300),
    protocol_attempts     SMALLINT    NOT NULL DEFAULT 0,
    bids_count            INT,
    rejected_count        INT,
    winner_name           VARCHAR(300),
    winner_bin            VARCHAR(20),
    winner_price          NUMERIC(15, 2),
    second_name           VARCHAR(300),
    second_bin            VARCHAR(20),
    second_price          NUMERIC(15, 2),
    min_price             NUMERIC(15, 2),
    max_price             NUMERIC(15, 2),
    our_place             INT,
    our_price             NUMERIC(15, 2),
    our_rejected          BOOLEAN,
    contract_number       VARCHAR(60),
    contract_signed_at    DATE,
    contract_unit_price   NUMERIC(15, 2),
    contract_sum          NUMERIC(17, 2),
    contract_supplier_bin VARCHAR(20),
    attempts              JSONB       NOT NULL DEFAULT '[]',
    next_tender_id        BIGINT REFERENCES tender (id) ON DELETE SET NULL,
    event_at              TIMESTAMPTZ,
    checked_at            TIMESTAMPTZ,
    final_at              TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tender_lot_result_tender ON tender_lot_result (tender_id);

CREATE TABLE tender_lot_bid (
    id            BIGSERIAL PRIMARY KEY,
    result_id     BIGINT  NOT NULL REFERENCES tender_lot_result (id) ON DELETE CASCADE,
    place         INT,
    supplier_name VARCHAR(300),
    supplier_bin  VARCHAR(20),
    unit_price    NUMERIC(15, 2),
    total         NUMERIC(17, 2),
    submitted_at  TIMESTAMP,
    rejected      BOOLEAN NOT NULL DEFAULT FALSE,
    reject_reason VARCHAR(500),
    winner        BOOLEAN NOT NULL DEFAULT FALSE,
    second_place  BOOLEAN NOT NULL DEFAULT FALSE,
    ours          BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_tender_lot_bid_result ON tender_lot_bid (result_id);

CREATE TABLE tender_result_event (
    id            BIGSERIAL PRIMARY KEY,
    tender_id     BIGINT       NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    tender_lot_id BIGINT       NOT NULL REFERENCES tender_lot (id) ON DELETE CASCADE,
    kind          VARCHAR(12)  NOT NULL,
    occurred_at   TIMESTAMP,
    text          VARCHAR(500) NOT NULL,
    urgent        BOOLEAN      NOT NULL,
    topic         VARCHAR(10)  NOT NULL,
    ref_tender_id BIGINT REFERENCES tender (id) ON DELETE SET NULL,
    ref_key       VARCHAR(60),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    notified_at   TIMESTAMPTZ,
    digested_at   TIMESTAMPTZ
);
CREATE INDEX idx_tender_result_event_tender ON tender_result_event (tender_id);
CREATE INDEX idx_tender_result_event_pending ON tender_result_event (id) WHERE notified_at IS NULL OR digested_at IS NULL;

CREATE TABLE market_contract (
    id                BIGSERIAL PRIMARY KEY,
    lot_ext_id        BIGINT         NOT NULL UNIQUE,
    anno              VARCHAR(30),
    lot_number        VARCHAR(50),
    enstru_code       VARCHAR(30),
    name              VARCHAR(500),
    description       VARCHAR(1000),
    extra_desc        TEXT,
    unit              VARCHAR(50),
    customer_bin      VARCHAR(20),
    customer_name     VARCHAR(500),
    region            VARCHAR(100),
    quantity          NUMERIC(15, 3),
    budget_unit_price NUMERIC(15, 2),
    unit_price        NUMERIC(15, 2) NOT NULL,
    supplier_bin      VARCHAR(20),
    signed_at         DATE,
    contract_number   VARCHAR(60),
    trade_method      VARCHAR(150),
    fetched_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_market_contract_code ON market_contract (enstru_code, signed_at);
CREATE INDEX idx_market_contract_customer ON market_contract (customer_bin);

CREATE TABLE market_lookup (
    lookup_key VARCHAR(150) PRIMARY KEY,
    fetched_at TIMESTAMPTZ  NOT NULL,
    rows_count INT          NOT NULL DEFAULT 0,
    error      VARCHAR(300)
);

CREATE TABLE goszakup_subject (
    bin        VARCHAR(20) PRIMARY KEY,
    name_ru    VARCHAR(500),
    phone      VARCHAR(100),
    email      VARCHAR(300),
    not_found  BOOLEAN     NOT NULL DEFAULT FALSE,
    fetched_at TIMESTAMPTZ NOT NULL
);
```

- [ ] **Step 4: Перечисления и запись цепочки**

```java
package com.vladoose.nir.entity;

/** Итог лота на площадке (спека 2026-10-22-tender-results-market-prices §4). */
public enum LotOutcome {
    PENDING, DONE, FAILED, CANCELLED;

    /** Справочник ref_lots_status: 360 — состоялась, 370 — не состоялась, 410 / 430 — отказ и отмена; остальное — ждём. */
    public static LotOutcome ofLotStatus(Integer statusId) {
        if (statusId == null) return PENDING;
        return switch (statusId) {
            case 360 -> DONE;
            case 370 -> FAILED;
            case 410, 430 -> CANCELLED;
            default -> PENDING;
        };
    }
}
```

```java
package com.vladoose.nir.entity;

/** Почему лот не состоялся (из вывода протокола): никто не подал, одна заявка, приглашённый отказался, другое. */
public enum FailReason { NO_BIDS, ONE_BID, REFUSED, OTHER }
```

```java
package com.vladoose.nir.entity;

/** Протокол итогов лота: разобран, вид незнакомый, не разобран / не скачан, файла ещё нет. */
public enum ProtocolStatus { PARSED, UNKNOWN_FORMAT, FAILED, NO_FILE }
```

```java
package com.vladoose.nir.entity;

/** Событие итогов (спека §7, §12). */
public enum ResultEventKind {
    DONE, NO_BIDS, ONE_BID, FAILED_OTHER, OI_DONE, OI_FAILED, CANCELLED, CONTRACT, REPEAT, OUR_BID;

    /** «Звонить заказчику» и наша заявка — сразу; остальное — в сводку дня. */
    public boolean urgent() {
        return this == NO_BIDS || this == FAILED_OTHER || this == OI_FAILED || this == OUR_BID;
    }
}
```

```java
package com.vladoose.nir.entity;

/** Куда сообщение: тема «Тендеры (наши товары)», тема «Тендеры» (ЗКО), никуда — только «История». */
public enum ResultTopic { PRODUCTS, TENDERS, NONE }
```

```java
package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Попытка той же закупки (пункт плана площадки): номер объявления и лота, способ («ЗЦП», «ОИ», «ОК»), статус лота,
 * время публикации и итогов (время площадки строкой), поставщик закупки из одного источника, тендер в АИС.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResultAttempt(String anno, String lotNumber, String method, Integer statusId, String status, String publishedAt,
                            String resultsAt, String supplierBin, Long tenderId) {}
```

- [ ] **Step 5: Сущности**

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Итог лота на площадке (спека 2026-10-22-tender-results-market-prices §4, §12): строка на лот, каскадом от лота. */
@Entity
@Table(name = "tender_lot_result")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenderLotResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_lot_id", nullable = false, unique = true)
    private Long tenderLotId;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "lot_status_id")
    private Integer lotStatusId;

    @Column(name = "lot_status", length = 100)
    private String lotStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private LotOutcome outcome = LotOutcome.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "fail_reason", length = 12)
    private FailReason failReason;

    @Column(name = "fail_text", length = 300)
    private String failText;

    /** Публикация итогов — время площадки (UTC+5), без пояса. */
    @Column(name = "results_at")
    private LocalDateTime resultsAt;

    @Column(name = "protocol_url", length = 300)
    private String protocolUrl;

    @Column(name = "protocol_name", length = 200)
    private String protocolName;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol_status", length = 16)
    private ProtocolStatus protocolStatus;

    @Column(name = "protocol_note", length = 300)
    private String protocolNote;

    @Column(name = "protocol_attempts", nullable = false)
    @Builder.Default
    private int protocolAttempts = 0;

    @Column(name = "bids_count")
    private Integer bidsCount;

    @Column(name = "rejected_count")
    private Integer rejectedCount;

    @Column(name = "winner_name", length = 300)
    private String winnerName;

    @Column(name = "winner_bin", length = 20)
    private String winnerBin;

    @Column(name = "winner_price", precision = 15, scale = 2)
    private BigDecimal winnerPrice;

    @Column(name = "second_name", length = 300)
    private String secondName;

    @Column(name = "second_bin", length = 20)
    private String secondBin;

    @Column(name = "second_price", precision = 15, scale = 2)
    private BigDecimal secondPrice;

    @Column(name = "min_price", precision = 15, scale = 2)
    private BigDecimal minPrice;

    @Column(name = "max_price", precision = 15, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "our_place")
    private Integer ourPlace;

    @Column(name = "our_price", precision = 15, scale = 2)
    private BigDecimal ourPrice;

    @Column(name = "our_rejected")
    private Boolean ourRejected;

    @Column(name = "contract_number", length = 60)
    private String contractNumber;

    @Column(name = "contract_signed_at")
    private LocalDate contractSignedAt;

    @Column(name = "contract_unit_price", precision = 15, scale = 2)
    private BigDecimal contractUnitPrice;

    @Column(name = "contract_sum", precision = 17, scale = 2)
    private BigDecimal contractSum;

    @Column(name = "contract_supplier_bin", length = 20)
    private String contractSupplierBin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<ResultAttempt> attempts = new ArrayList<>();

    @Column(name = "next_tender_id")
    private Long nextTenderId;

    /** Событие итога заведено (сигнал / сводка) — второй раз не заводится. */
    @Column(name = "event_at")
    private OffsetDateTime eventAt;

    @Column(name = "checked_at")
    private OffsetDateTime checkedAt;

    @Column(name = "final_at")
    private OffsetDateTime finalAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
```

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Участник протокола итогов (спека §5): место (у отклонённых — пусто), цена за единицу и сумма, время подачи. */
@Entity
@Table(name = "tender_lot_bid")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenderLotBid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "result_id", nullable = false)
    private Long resultId;

    private Integer place;

    @Column(name = "supplier_name", length = 300)
    private String supplierName;

    @Column(name = "supplier_bin", length = 20)
    private String supplierBin;

    @Column(name = "unit_price", precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(precision = 17, scale = 2)
    private BigDecimal total;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(nullable = false)
    private boolean rejected;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(nullable = false)
    private boolean winner;

    @Column(name = "second_place", nullable = false)
    private boolean secondPlace;

    @Column(nullable = false)
    private boolean ours;
}
```

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Событие итогов (спека §7, §12): сигнал «звонить», наша заявка, строка сводки, строка «Истории» тендера. */
@Entity
@Table(name = "tender_result_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenderResultEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "tender_lot_id", nullable = false)
    private Long tenderLotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ResultEventKind kind;

    /** Время площадки (UTC+5) — когда это случилось на площадке; пусто — неизвестно. */
    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(nullable = false, length = 500)
    private String text;

    @Column(nullable = false)
    private boolean urgent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ResultTopic topic;

    /** Тендер повторной попытки в АИС (для ссылки в «Истории»). */
    @Column(name = "ref_tender_id")
    private Long refTenderId;

    /** Ключ повтора у событий цепочки — номер объявления попытки: текст меняется (название поставщика приходит позже), ключ — нет. */
    @Column(name = "ref_key", length = 60)
    private String refKey;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "notified_at")
    private OffsetDateTime notifiedAt;

    @Column(name = "digested_at")
    private OffsetDateTime digestedAt;
}
```

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Строка набора цен рынка (спека §9): состоявшийся лот goszakup с договором. Ключ — id лота площадки. */
@Entity
@Table(name = "market_contract")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lot_ext_id", nullable = false, unique = true)
    private Long lotExtId;

    @Column(length = 30)
    private String anno;

    @Column(name = "lot_number", length = 50)
    private String lotNumber;

    @Column(name = "enstru_code", length = 30)
    private String enstruCode;

    @Column(length = 500)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "extra_desc", columnDefinition = "TEXT")
    private String extraDesc;

    @Column(length = 50)
    private String unit;

    @Column(name = "customer_bin", length = 20)
    private String customerBin;

    @Column(name = "customer_name", length = 500)
    private String customerName;

    @Column(length = 100)
    private String region;

    @Column(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(name = "budget_unit_price", precision = 15, scale = 2)
    private BigDecimal budgetUnitPrice;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "supplier_bin", length = 20)
    private String supplierBin;

    @Column(name = "signed_at")
    private LocalDate signedAt;

    @Column(name = "contract_number", length = 60)
    private String contractNumber;

    @Column(name = "trade_method", length = 150)
    private String tradeMethod;

    @Column(name = "fetched_at", nullable = false)
    @Builder.Default
    private OffsetDateTime fetchedAt = OffsetDateTime.now();
}
```

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Что и когда запрашивали у площадки для цен рынка (спека §9): «w:<слово>», «c:<БИН>», «daily». */
@Entity
@Table(name = "market_lookup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketLookup {

    @Id
    @Column(name = "lookup_key", length = 150)
    private String lookupKey;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @Column(name = "rows_count", nullable = false)
    private int rowsCount;

    @Column(length = 300)
    private String error;
}
```

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Справочник площадки по БИН (спека §8): название, телефон, почта; «не найден» — площадка БИН не знает. */
@Entity
@Table(name = "goszakup_subject")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoszakupSubject {

    @Id
    @Column(length = 20)
    private String bin;

    @Column(name = "name_ru", length = 500)
    private String nameRu;

    @Column(length = 100)
    private String phone;

    @Column(length = 300)
    private String email;

    @Column(name = "not_found", nullable = false)
    private boolean notFound;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}
```

`TenderLot.java` — после `modelByAi`:

```java
    /** Площадка (спека 2026-10-22-tender-results-market-prices §12): id лота goszakup — ключ строк договора. */
    @Column(name = "ext_lot_id")
    private Long extLotId;

    /** Пункт плана площадки — общий у всех попыток одной закупки (цепочка «запрос → повтор → из одного источника»). */
    @Column(name = "plan_point_id")
    private Long planPointId;

    /** Код ЕНС ТРУ пункта плана — категория цен рынка. */
    @Column(name = "enstru_code", length = 30)
    private String enstruCode;

    /** Единица измерения пункта плана («Штука», «Упаковка»). */
    @Column(name = "plan_unit", length = 50)
    private String planUnit;
```

`TenderLotMapper.java` — в `toEntity` и `updateEntity` после `@Mapping(target = "modelByAi", ignore = true)`:

```java
    @Mapping(target = "extLotId", ignore = true)
    @Mapping(target = "planPointId", ignore = true)
    @Mapping(target = "enstruCode", ignore = true)
    @Mapping(target = "planUnit", ignore = true)
```

- [ ] **Step 6: Репозитории**

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLotResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TenderLotResultRepository extends JpaRepository<TenderLotResult, Long> {

    Optional<TenderLotResult> findByTenderLotId(Long tenderLotId);

    List<TenderLotResult> findByTenderId(Long tenderId);

    List<TenderLotResult> findByTenderIdIn(Collection<Long> tenderIds);

    List<TenderLotResult> findByTenderLotIdIn(Collection<Long> lotIds);

    /** Не состоялись, цепочка не досмотрена — проход ищет следующие попытки (спека §6). */
    @Query("select r from TenderLotResult r where r.outcome = com.vladoose.nir.entity.LotOutcome.FAILED and r.finalAt is null order by r.id")
    List<TenderLotResult> findOpenFailed();

    /** Состоялись, договора ещё нет. */
    @Query("select r from TenderLotResult r where r.outcome = com.vladoose.nir.entity.LotOutcome.DONE and r.contractNumber is null "
            + "and r.finalAt is null order by r.id")
    List<TenderLotResult> findDoneWithoutContract();
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLotBid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TenderLotBidRepository extends JpaRepository<TenderLotBid, Long> {

    List<TenderLotBid> findByResultIdOrderByPlaceAscIdAsc(Long resultId);

    List<TenderLotBid> findByResultIdIn(Collection<Long> resultIds);

    @Modifying
    @Query("delete from TenderLotBid b where b.resultId = :resultId")
    void deleteByResultId(@Param("resultId") Long resultId);
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ResultEventKind;
import com.vladoose.nir.entity.TenderResultEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TenderResultEventRepository extends JpaRepository<TenderResultEvent, Long> {

    /** «Звонить» и наша заявка, ещё не отправленные (тема не «никуда»). */
    @Query("select e from TenderResultEvent e where e.urgent = true and e.notifiedAt is null "
            + "and e.topic <> com.vladoose.nir.entity.ResultTopic.NONE order by e.id")
    List<TenderResultEvent> findUrgentPending();

    /** Сводка дня — только тема нашей продукции. */
    @Query("select e from TenderResultEvent e where e.urgent = false and e.digestedAt is null "
            + "and e.topic = com.vladoose.nir.entity.ResultTopic.PRODUCTS order by e.id")
    List<TenderResultEvent> findDigestPending();

    List<TenderResultEvent> findByTenderIdOrderByIdAsc(Long tenderId);

    boolean existsByTenderLotIdAndKind(Long tenderLotId, ResultEventKind kind);

    boolean existsByTenderLotIdAndKindAndRefKey(Long tenderLotId, ResultEventKind kind, String refKey);
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.MarketContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface MarketContractRepository extends JpaRepository<MarketContract, Long> {

    List<MarketContract> findByLotExtIdIn(Collection<Long> lotExtIds);

    List<MarketContract> findByEnstruCodeAndSignedAtGreaterThanEqual(String enstruCode, LocalDate from);

    List<MarketContract> findByCustomerBinAndSignedAtGreaterThanEqualOrderBySignedAtDesc(String customerBin, LocalDate from);

    /** Категории со словом (pattern — «%слово%» нижним регистром): код, название, описание, число договоров. */
    @Query(value = """
            SELECT enstru_code, MAX(name), MAX(description), COUNT(*)
            FROM market_contract
            WHERE enstru_code IS NOT NULL AND signed_at >= :from
              AND (LOWER(name) LIKE :pattern OR LOWER(COALESCE(description, '')) LIKE :pattern
                   OR LOWER(COALESCE(extra_desc, '')) LIKE :pattern)
            GROUP BY enstru_code
            ORDER BY COUNT(*) DESC
            LIMIT 20
            """, nativeQuery = true)
    List<Object[]> findCategories(@Param("pattern") String pattern, @Param("from") LocalDate from);
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.MarketLookup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketLookupRepository extends JpaRepository<MarketLookup, String> {
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.GoszakupSubject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface GoszakupSubjectRepository extends JpaRepository<GoszakupSubject, String> {
    List<GoszakupSubject> findByBinIn(Collection<String> bins);
}
```

`TenderLotRepository.java` — добавить:

```java
    /** Лоты всех попыток закупок по пунктам плана (наследование «наш лот», спека §7). */
    List<TenderLot> findByPlanPointIdIn(Collection<Long> planPointIds);

    /**
     * Наши лоты (спека 2026-10-22-tender-results-market-prices §7): модель в работе, запрос цен, позиция заявки на участие —
     * или отметка «наш товар» в тендере, о котором была пачка, если ИИ не ответил «не наше» / «аналог».
     */
    @Query(value = """
            SELECT l.id FROM tender_lot l JOIN tender t ON t.id = l.tender_id
            WHERE l.id IN (:ids) AND (
                l.proposed_equipment_id IS NOT NULL
                OR EXISTS (SELECT 1 FROM price_request_item i WHERE i.tender_lot_id = l.id)
                OR EXISTS (SELECT 1 FROM apply_item a WHERE a.tender_lot_id = l.id)
                OR (t.product_notified_level IS NOT NULL
                    AND EXISTS (SELECT 1 FROM tender_lot_product_match m WHERE m.lot_id = l.id)
                    AND COALESCE(l.ai_verdict, '') NOT IN ('NO', 'ANALOG')))
            """, nativeQuery = true)
    List<Long> findOurLotIds(@Param("ids") Collection<Long> ids);
```

`TenderRepository.java` — добавить (импорт `java.time.LocalDateTime` уже есть):

```java
    /**
     * Тендеры для прохода итогов (спека §4): KZ goszakup, срок прошёл (время площадки; только дата — весь день) не раньше
     * from, есть лот без окончательного итога. Новые сроки — первыми.
     */
    @Query(value = """
            SELECT t.id FROM tender t
            WHERE t.market = 'KZ' AND t.platform = 'GOSZAKUP' AND t.source = 'PUBLIC_TENDER' AND t.source_ext_id IS NOT NULL
              AND COALESCE(t.deadline_at, CAST(t.deadline + 1 AS timestamp)) <= :now
              AND COALESCE(t.deadline_at, CAST(t.deadline + 1 AS timestamp)) >= :from
              AND EXISTS (SELECT 1 FROM tender_lot l WHERE l.tender_id = t.id AND l.source_removed_at IS NULL
                          AND NOT EXISTS (SELECT 1 FROM tender_lot_result r WHERE r.tender_lot_id = l.id AND r.final_at IS NOT NULL))
            ORDER BY COALESCE(t.deadline_at, CAST(t.deadline + 1 AS timestamp)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findResultCandidates(@Param("now") LocalDateTime now, @Param("from") LocalDateTime from, @Param("limit") int limit);
```

- [ ] **Step 7: Запустить — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.tenderresults.TenderResultsSchemaTest' 2>&1 | tail -20` (sandbox off)
Expected: PASS (4 теста).

- [ ] **Step 8: Мутации**

1. В `findOurLotIds` убрать строку `AND COALESCE(l.ai_verdict, '') NOT IN ('NO', 'ANALOG')` → `ourLots` красный.
2. В `findResultCandidates` убрать `AND r.final_at IS NOT NULL` → `candidates` красный.
3. В `findResultCandidates` убрать условие `>= :from` → `candidates` красный.
Вернуть копией файла, перекомпилировать.

- [ ] **Step 9: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V43__tender_results_market_prices.sql \
  src/main/java/com/vladoose/nir/entity src/main/java/com/vladoose/nir/repository src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java \
  src/test/java/com/vladoose/nir/tenderresults && git commit -m "$(cat <<'EOF'
feat(results): V43 — итоги лотов, участники, события, набор договоров, справочник площадки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Клиент площадки — итоги, договоры, цепочки, состоявшиеся лоты, протокол; поля площадки при импорте

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/dto/ResultBuy.java`, `ResultLot.java`, `ProtocolFileRef.java`, `ContractRow.java`, `ContractUnitRow.java`, `AttemptLot.java`, `ClosedLot.java`, `SubjectInfo.java`
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupResultsClient.java`, `GoszakupResultsHttpClient.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java` (новые публичные методы рядом с `contracts`)
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/dto/SubjectDto.java` (`phone`, `email`), `dto/LotDto.java` (`id`, `point_list`)
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupTenderWriter.java` (`rebuildLots`: `extLotId`, `planPointId`)
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupResultsClientTest.java`, `GoszakupDtoJsonTest.java` (+1 тест), `src/test/java/com/vladoose/nir/integration/ImportPlanFieldsTest.java`

**Interfaces:**
- Consumes: приватные `v3(...)`, `map`, `text`, `integer`, `longer`, `dec`, `range`, `origin()`, `requestFor`, `http` в `GoszakupHttpClient`.
- Produces: `interface GoszakupResultsClient { boolean isConfigured(); List<ResultBuy> announcements(List<Long> buyIds); List<ContractRow> contractsByBuyIds(List<Long> buyIds); List<ContractRow> contractsByCustomer(String bin, LocalDate from, LocalDate to); List<AttemptLot> lotsByPlanPoints(List<Long> pointIds); SearchPage<ClosedLot> closedLots(String word, LocalDate from, LocalDate to, Long after); List<ClosedLot> lotsByIds(List<Long> lotIds); byte[] downloadProtocol(String url); SubjectInfo subject(String bin); }`; записи `ResultBuy(Long id, String numberAnno, Integer buyStatusId, String resultsAt, String supplierBin, Integer tradeMethodId, String tradeMethodName, List<ResultLot> lots, List<ProtocolFileRef> protocols)`, `ResultLot(Long id, String lotNumber, Integer statusId, String statusName, BigDecimal count, BigDecimal amount, Long planPointId, String enstruCode, String unit, String customerBin)`, `ProtocolFileRef(String name, String url)`, `ContractRow(Long id, Long buyId, String number, String signDate, Integer statusId, String supplierBin, BigDecimal sumWithVat, List<ContractUnitRow> units)`, `ContractUnitRow(Long lotId, BigDecimal unitPrice, BigDecimal quantity, BigDecimal totalWithVat)`, `AttemptLot(Long lotId, String lotNumber, Integer statusId, String statusName, Long planPointId, String anno, Long buyId, Integer tradeMethodId, String tradeMethodName, String publishDate, String endDate, String resultsAt, String supplierBin)`, `ClosedLot(Long id, String lotNumber, String nameRu, String descriptionRu, BigDecimal count, BigDecimal amount, Long buyId, String anno, String customerBin, String customerName, String enstruCode, String extraDesc, String unit, String deliveryPlace, String tradeMethodName)`, `SubjectInfo(String bin, String nameRu, String phone, String email)`; `ContractRow.NOT_CONCLUDED` — статусы договора «не заключён / отказ / недействителен» (330, 430, 461, 480); `LotDto.id`, `LotDto.pointList`.

- [ ] **Step 1: Тест клиента на заглушке площадки**

```java
package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.goszakup.dto.*;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Итоги, договоры, цепочки и цены рынка на v3 (спека 2026-10-22-tender-results-market-prices §3): формы сняты живьём 2026-10-22. */
class GoszakupResultsClientTest {

    HttpServer server;
    final List<String> bodies = new ArrayList<>();
    final List<String> downloads = new ArrayList<>();
    int contractCalls;

    static final String BUYS = """
        {"data":{"TrdBuy":[{"id":17758520,"numberAnno":"17758520-1","refBuyStatusId":350,"itogiDatePublic":"2026-10-14 12:54:49",
          "biinSupplier":null,"refTradeMethodsId":3,"RefTradeMethods":{"nameRu":"Запрос ценовых предложений"},
          "Files":[{"nameRu":"Протокол итогов","originalName":"protocol_PI_17758520.pdf","filePath":"PROTO_URL"},
                   {"nameRu":"Техническая спецификация","originalName":"techspec.pdf","filePath":"https://x/techspec"}],
          "Lots":[{"id":43546011,"lotNumber":"88214663-ЗЦП2","refLotStatusId":360,"count":3,"amount":1841700,"pointList":[88214663],
            "customerBin":"110240019426","RefLotsStatus":{"nameRu":"Закупка состоялась"},
            "Plans":[{"refEnstruCode":"309911.100.000001","RefUnits":{"nameRu":"Штука"}}]}]}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":17758520}}}""";
    static final String CONTRACTS_PAGE1 = """
        {"data":{"Contract":[{"id":25969702,"trdBuyId":17752538,"contractNumber":"070740006294/260209/00","signDate":"2026-10-20 09:28:34",
          "refContractStatusId":190,"supplierBiin":"880808302714","contractSumWnds":43000,
          "ContractUnits":[{"lotId":43533728,"itemPrice":40000,"itemPriceWnds":43000,"quantity":1,"totalSumWnds":43000}]}]},
         "extensions":{"pageInfo":{"hasNextPage":true,"lastId":25969702}}}""";
    static final String CONTRACTS_PAGE2 = """
        {"data":{"Contract":[{"id":25969800,"trdBuyId":17752539,"contractNumber":"2","signDate":"2026-10-21 10:00:00",
          "refContractStatusId":190,"supplierBiin":"880808302715","contractSumWnds":100,
          "ContractUnits":[{"lotId":43533729,"itemPrice":100,"itemPriceWnds":null,"quantity":1,"totalSumWnds":100}]}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":25969800}}}""";
    static final String ATTEMPTS = """
        {"data":{"Lots":[{"id":43625311,"lotNumber":"88111123-ОИ3","refLotStatusId":360,"pointList":[88111123],"trdBuyId":17802320,
          "trdBuyNumberAnno":"17802320-1","RefLotsStatus":{"nameRu":"Закупка состоялась"},
          "TrdBuy":{"id":17802320,"numberAnno":"17802320-1","publishDate":"2026-10-16 19:32:08","endDate":"2026-10-19 19:32:08","itogiDatePublic":"2026-10-20 09:10:00",
            "biinSupplier":"070326551082","refTradeMethodsId":6,"RefTradeMethods":{"nameRu":"Из одного источника по несостоявшимся закупкам"}}}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":43625311}}}""";
    static final String CLOSED = """
        {"data":{"Lots":[{"id":43000001,"lotNumber":"1-ЗЦП1","nameRu":"Дефибриллятор","descriptionRu":"для терапии нарушений сердечного ритма",
          "count":2,"amount":2800000,"trdBuyId":17000001,"trdBuyNumberAnno":"17000001-1","customerBin":"990240006109",
          "customerNameRu":"ГКП «Больница»","RefTradeMethods":{"nameRu":"Запрос ценовых предложений"},
          "Plans":[{"refEnstruCode":"266011.150.000000","extraDescRu":"бифазный, с монитором","RefUnits":{"nameRu":"Штука"},
            "PlansKato":[{"fullDeliveryPlaceNameRu":"Актюбинская область, г.Актобе"}]}]}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":43000001}}}""";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v3/graphql", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            String reply = body.contains("Contract(") ? (contractCalls++ == 0 ? CONTRACTS_PAGE1 : CONTRACTS_PAGE2)
                    : body.contains("TrdBuy(") ? BUYS.replace("PROTO_URL", base + "/download/trd_buy/abc")
                    : body.contains("pointList:$") ? ATTEMPTS : CLOSED;
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.createContext("/download/", ex -> {
            downloads.add(ex.getRequestURI().getPath() + "|" + ex.getRequestHeaders().getFirst("Authorization"));
            boolean missing = ex.getRequestURI().getPath().endsWith("/gone");
            byte[] b = "%PDF-1.4 протокол".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(missing ? 404 : 200, missing ? -1 : b.length);
            if (!missing) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        server.createContext("/v2/subject/biin/", ex -> {
            byte[] b = """
                {"bin":"110240019426","name_ru":"ГКП «Бурлинская РБ»","email":"aksaymed@mail.ru","phone":"31398"}"""
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private GoszakupResultsClient client() {
        return new GoszakupResultsHttpClient(new GoszakupHttpClient(new ObjectMapper(), base() + "/v2", "tok", 50));
    }

    @Test
    void announcements_lotsStatusesPlanAndProtocol() {
        List<ResultBuy> buys = client().announcements(List.of(17758520L));
        assertThat(bodies.get(0)).contains("TrdBuy(filter:{id:$v0}").contains("17758520").contains("itogiDatePublic");
        ResultBuy b = buys.get(0);
        assertThat(b.resultsAt()).isEqualTo("2026-10-14 12:54:49");
        assertThat(b.tradeMethodName()).isEqualTo("Запрос ценовых предложений");
        assertThat(b.protocols()).extracting(ProtocolFileRef::name).containsExactly("protocol_PI_17758520.pdf");
        ResultLot l = b.lots().get(0);
        assertThat(l.statusId()).isEqualTo(360);
        assertThat(l.statusName()).isEqualTo("Закупка состоялась");
        assertThat(l.planPointId()).isEqualTo(88214663L);
        assertThat(l.enstruCode()).isEqualTo("309911.100.000001");
        assertThat(l.unit()).isEqualTo("Штука");
        assertThat(l.count()).isEqualByComparingTo("3");
    }

    @Test
    void contracts_unitPriceWithVatFirst_andAllPages() {
        List<ContractRow> rows = client().contractsByBuyIds(List.of(17752538L, 17752539L));
        assertThat(bodies).hasSize(2);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).units().get(0).unitPrice()).isEqualByComparingTo("43000");
        assertThat(rows.get(1).units().get(0).unitPrice()).as("без цены с НДС — цена без НДС").isEqualByComparingTo("100");
        assertThat(rows.get(0).signDate()).startsWith("2026-10-20");
    }

    @Test
    void attempts_methodSupplierAndPoint() {
        AttemptLot a = client().lotsByPlanPoints(List.of(88111123L)).get(0);
        assertThat(bodies.get(0)).contains("pointList:$v0");
        assertThat(a.tradeMethodId()).isEqualTo(6);
        assertThat(a.supplierBin()).isEqualTo("070326551082");
        assertThat(a.anno()).isEqualTo("17802320-1");
        assertThat(a.planPointId()).isEqualTo(88111123L);
        assertThat(a.publishDate()).isEqualTo("2026-10-16 19:32:08");
        assertThat(a.endDate()).isEqualTo("2026-10-19 19:32:08");
    }

    @Test
    void closedLots_onlyDone_withCategoryUnitPlace() {
        SearchPage<ClosedLot> p = client().closedLots("дефибриллятор", LocalDate.of(2024, 10, 22), LocalDate.of(2026, 10, 22), null);
        assertThat(bodies.get(0)).contains("nameDescriptionRu").contains("360").contains("2024-10-22");
        ClosedLot l = p.items().get(0);
        assertThat(l.enstruCode()).isEqualTo("266011.150.000000");
        assertThat(l.extraDesc()).isEqualTo("бифазный, с монитором");
        assertThat(l.unit()).isEqualTo("Штука");
        assertThat(l.deliveryPlace()).startsWith("Актюбинская область");
        assertThat(l.amount()).isEqualByComparingTo("2800000");
    }

    @Test
    void protocol_onlyFromPlatformHost_withoutToken() {
        byte[] b = client().downloadProtocol(base() + "/download/trd_buy/abc");
        assertThat(new String(b, StandardCharsets.UTF_8)).startsWith("%PDF");
        assertThat(downloads).containsExactly("/download/trd_buy/abc|null");
        assertThat(client().downloadProtocol(base() + "/download/trd_buy/gone")).as("404 — файла нет").isNull();
        assertThatThrownBy(() -> client().downloadProtocol("https://evil.example/download/x")).isInstanceOf(GoszakupCallException.class);
        assertThatThrownBy(() -> client().downloadProtocol(base() + "/v2/subject/biin/1")).isInstanceOf(GoszakupCallException.class);
        assertThat(downloads).hasSize(2);
    }

    @Test
    void subject_phoneAndEmail() {
        SubjectInfo s = client().subject("110240019426");
        assertThat(s.phone()).isEqualTo("31398");
        assertThat(s.email()).isEqualTo("aksaymed@mail.ru");
        assertThat(s.nameRu()).isEqualTo("ГКП «Бурлинская РБ»");
    }
}
```

`GoszakupDtoJsonTest.java` — добавить:

```java
    /** Живой /v2/lots: id лота площадки и пункт плана — ключи договора и цепочки попыток (спека 2026-10-22 §12). */
    @Test
    void parsesLot_idAndPlanPoint() throws Exception {
        LotDto lot = om.readValue("""
            {"id":43546011,"lot_number":"88214663-ЗЦП2","count":3,"amount":1841700,"point_list":[88214663]}""", LotDto.class);
        assertThat(lot.getId()).isEqualTo(43546011L);
        assertThat(lot.getPointList()).containsExactly(88214663L);
    }
```

`ImportPlanFieldsTest.java`:

```java
package com.vladoose.nir.integration;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.goszakup.GoszakupTenderWriter;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Импорт goszakup пишет id лота площадки и пункт плана (спека 2026-10-22-tender-results-market-prices §12). */
@SpringBootTest
@Transactional
class ImportPlanFieldsTest {

    @Autowired GoszakupTenderWriter writer;
    @Autowired TenderRepository tenders;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void writerStoresExtLotIdAndPlanPoint() {
        MarketContext.set(Market.KZ);
        TrdBuyDto d = new TrdBuyDto();
        d.setNumberAnno("979000001-1");
        d.setNameRu("ZZ тележки");
        d.setRefBuyStatusId(220);
        d.setEndDate("2099-01-01 10:00:00");
        LotDto lot = new LotDto();
        lot.setId(43546011L);
        lot.setLotNumber("88214663-ЗЦП2");
        lot.setNameRu("Тележка");
        lot.setCount(new BigDecimal("3"));
        lot.setAmount(new BigDecimal("1841700"));
        lot.setPointList(List.of(88214663L));
        writer.upsertOne(d, null, List.of(lot));
        TenderLot l = tenders.findBySourceExtId("979000001-1").orElseThrow().getLots().get(0);
        assertThat(l.getExtLotId()).isEqualTo(43546011L);
        assertThat(l.getPlanPointId()).isEqualTo(88214663L);
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.GoszakupResultsClientTest' --tests 'com.vladoose.nir.integration.goszakup.GoszakupDtoJsonTest' --tests 'com.vladoose.nir.integration.ImportPlanFieldsTest' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (`GoszakupResultsClient`, DTO, `LotDto.setId`).

- [ ] **Step 3: DTO**

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.util.List;

/** Объявление для итогов (спека 2026-10-22-tender-results-market-prices §4): статус, время итогов, лоты, «Протокол итогов». */
public record ResultBuy(Long id, String numberAnno, Integer buyStatusId, String resultsAt, String supplierBin, Integer tradeMethodId,
                        String tradeMethodName, List<ResultLot> lots, List<ProtocolFileRef> protocols) {}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Лот объявления для итогов: статус, количество и сумма, пункт плана, код ЕНС ТРУ и единица пункта плана. */
public record ResultLot(Long id, String lotNumber, Integer statusId, String statusName, BigDecimal count, BigDecimal amount,
                        Long planPointId, String enstruCode, String unit, String customerBin) {}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

/** Файл «Протокол итогов» объявления. */
public record ProtocolFileRef(String name, String url) {}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Договор goszakup со строками по лотам. Статусы — справочник ref_contract_status. */
public record ContractRow(Long id, Long buyId, String number, String signDate, Integer statusId, String supplierBin,
                          BigDecimal sumWithVat, List<ContractUnitRow> units) {

    /** «Не заключён», «Отказ от закупки и договора», «Передан. Не заключён», «Недействительный по решению суда». */
    public static final Set<Integer> NOT_CONCLUDED = Set.of(330, 430, 461, 480);

    /** Подписан и не из «не заключён / отказ / недействителен». */
    public boolean concluded() {
        return signDate != null && !signDate.isBlank() && (statusId == null || !NOT_CONCLUDED.contains(statusId));
    }
}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Строка договора по лоту: цена за единицу (с НДС, нет — без НДС), количество, сумма с НДС. */
public record ContractUnitRow(Long lotId, BigDecimal unitPrice, BigDecimal quantity, BigDecimal totalWithVat) {}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

/** Попытка закупки по пункту плана (спека §6): лот, статус, объявление, способ, срок приёма, поставщик закупки из одного источника. */
public record AttemptLot(Long lotId, String lotNumber, Integer statusId, String statusName, Long planPointId, String anno, Long buyId,
                         Integer tradeMethodId, String tradeMethodName, String publishDate, String endDate, String resultsAt,
                         String supplierBin) {}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Состоявшийся лот для цен рынка (спека §9): категория, бюджет, заказчик, место поставки, способ закупки. */
public record ClosedLot(Long id, String lotNumber, String nameRu, String descriptionRu, BigDecimal count, BigDecimal amount, Long buyId,
                        String anno, String customerBin, String customerName, String enstruCode, String extraDesc, String unit,
                        String deliveryPlace, String tradeMethodName) {}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

/** Организация площадки по БИН: название, телефон (бывает неполным), почта. */
public record SubjectInfo(String bin, String nameRu, String phone, String email) {}
```

`SubjectDto.java` — после `nameRu`:

```java
    private String phone;
    private String email;
```

`LotDto.java` — добавить поля (импорт `java.util.List`):

```java
    /** id лота площадки — ключ строк договора (спека 2026-10-22 §12). */
    private Long id;
    /** Пункты плана лота — общие у всех попыток закупки. */
    @JsonProperty("point_list") private List<Long> pointList;
```

- [ ] **Step 4: Методы `GoszakupHttpClient`** (после `contracts(...)`)

```java
    // --- итоги, договоры, цепочки и цены рынка (спека 2026-10-22-tender-results-market-prices §3–§9) ---

    private static final String RESULT_LOT_FIELDS = "id lotNumber refLotStatusId count amount pointList customerBin "
            + "RefLotsStatus{ nameRu } Plans{ refEnstruCode RefUnits{ nameRu } }";
    private static final String RESULT_BUY_FIELDS = "id numberAnno refBuyStatusId itogiDatePublic biinSupplier refTradeMethodsId "
            + "RefTradeMethods{ nameRu } Files{ nameRu originalName filePath } Lots{ " + RESULT_LOT_FIELDS + " }";
    private static final String CONTRACT_FIELDS = "id trdBuyId contractNumber signDate refContractStatusId supplierBiin "
            + "contractSumWnds ContractUnits{ lotId itemPrice itemPriceWnds quantity totalSumWnds }";
    private static final String ATTEMPT_FIELDS = "id lotNumber refLotStatusId pointList trdBuyId trdBuyNumberAnno "
            + "RefLotsStatus{ nameRu } TrdBuy{ id numberAnno publishDate endDate itogiDatePublic biinSupplier refTradeMethodsId "
            + "RefTradeMethods{ nameRu } }";
    private static final String CLOSED_LOT_FIELDS = "id lotNumber nameRu descriptionRu count amount trdBuyId trdBuyNumberAnno "
            + "customerBin customerNameRu RefTradeMethods{ nameRu } "
            + "Plans{ refEnstruCode extraDescRu RefUnits{ nameRu } PlansKato{ fullDeliveryPlaceNameRu } }";
    /** Предел файла протокола: протоколы — десятки и сотни КБ. */
    static final long MAX_PROTOCOL_BYTES = 5L * 1024 * 1024;
    private static final int MAX_PAGES = 10;

    public List<ResultBuy> resultAnnouncements(List<Long> buyIds) {
        if (buyIds == null || buyIds.isEmpty()) return List.of();
        List<Integer> ids = buyIds.stream().map(Long::intValue).toList();
        return map(v3("TrdBuy", RESULT_BUY_FIELDS, List.of(new F("id", "[Int]", ids)), Math.min(200, ids.size()), null, null),
                GoszakupHttpClient::resultBuyOf);
    }

    public List<ContractRow> contractsByBuyIds(List<Long> buyIds) {
        if (buyIds == null || buyIds.isEmpty()) return List.of();
        return pages("Contract", CONTRACT_FIELDS,
                List.of(new F("trdBuyId", "[Int]", buyIds.stream().map(Long::intValue).toList())), GoszakupHttpClient::contractOf);
    }

    public List<ContractRow> contractsByCustomer(String bin, LocalDate from, LocalDate to) {
        if (bin == null || bin.isBlank()) return List.of();
        return pages("Contract", CONTRACT_FIELDS,
                List.of(new F("customerBin", "String", bin), new F("signDate", "[String]", range(from, to))), GoszakupHttpClient::contractOf);
    }

    public List<AttemptLot> lotsByPlanPoints(List<Long> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) return List.of();
        return pages("Lots", ATTEMPT_FIELDS,
                List.of(new F("pointList", "[Int]", pointIds.stream().map(Long::intValue).toList())), GoszakupHttpClient::attemptOf);
    }

    public SearchPage<ClosedLot> closedLots(String word, LocalDate from, LocalDate to, Long after) {
        Long[] next = new Long[1];
        JsonNode items = v3("Lots", CLOSED_LOT_FIELDS, List.of(new F("nameDescriptionRu", "String", word),
                new F("refLotStatusId", "[Int]", List.of(360)), new F("lastUpdateDate", "[String]", range(from, to))),
                SEARCH_PAGE, after, next);
        return new SearchPage<>(map(items, GoszakupHttpClient::closedLotOf), next[0]);
    }

    public List<ClosedLot> lotsByIds(List<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty()) return List.of();
        List<Integer> ids = lotIds.stream().map(Long::intValue).toList();
        return map(v3("Lots", CLOSED_LOT_FIELDS, List.of(new F("id", "[Int]", ids)), Math.min(200, ids.size()), null, null),
                GoszakupHttpClient::closedLotOf);
    }

    /**
     * Файл «Протокол итогов»: только с хоста площадки и только из /download/ (адрес — из её ответа, чужой хост — отказ без
     * запроса), без токена (файл публичный), не больше 5 МБ. 404 — null.
     */
    public byte[] downloadProtocol(String url) {
        if (url == null || !url.startsWith(origin() + "/download/")) {
            throw new GoszakupCallException("goszakup: протокол не на площадке — не качаем", false, null);
        }
        HttpRequest req = requestFor(url).GET().timeout(FILE_DEADLINE).build();
        HttpResponse<byte[]> resp;
        try {
            resp = UpstreamHttp.exchange(http, req, MAX_PROTOCOL_BYTES, FILE_DEADLINE);
        } catch (UpstreamIoException e) {
            throw new GoszakupCallException("goszakup: протокол не скачан: " + e.getMessage(), e.retryable(), e);
        }
        int code = resp.statusCode();
        if (code == 404) return null;
        if (code / 100 != 2) {
            throw new GoszakupCallException("goszakup: протокол — HTTP " + code, code, UpstreamRetry.retryableStatus(code), null);
        }
        return resp.body();
    }

    /** Все страницы (не больше 10 по 200) — договоры и цепочки бывают длиннее одной страницы. */
    private <T> List<T> pages(String root, String fields, List<F> filters, java.util.function.Function<JsonNode, T> f) {
        List<T> out = new ArrayList<>();
        Long after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Long[] next = new Long[1];
            out.addAll(map(v3(root, fields, filters, SEARCH_PAGE, after, next), f));
            after = next[0];
            if (after == null) break;
        }
        return out;
    }

    private static Long firstLong(JsonNode arr) {
        return arr != null && arr.isArray() && arr.size() > 0 && arr.get(0).isNumber() ? arr.get(0).asLong() : null;
    }

    private static JsonNode firstPlan(JsonNode lot) {
        JsonNode plans = lot.path("Plans");
        return plans.isArray() && plans.size() > 0 ? plans.get(0) : null;
    }

    private static ResultBuy resultBuyOf(JsonNode b) {
        List<ProtocolFileRef> protocols = new ArrayList<>();
        for (JsonNode f : b.path("Files")) {
            if ("Протокол итогов".equals(text(f, "nameRu"))) protocols.add(new ProtocolFileRef(text(f, "originalName"), text(f, "filePath")));
        }
        List<ResultLot> lots = new ArrayList<>();
        for (JsonNode l : b.path("Lots")) {
            JsonNode plan = firstPlan(l);
            lots.add(new ResultLot(longer(l, "id"), text(l, "lotNumber"), integer(l, "refLotStatusId"), text(l.path("RefLotsStatus"), "nameRu"),
                    dec(l, "count"), dec(l, "amount"), firstLong(l.path("pointList")), plan == null ? null : text(plan, "refEnstruCode"),
                    plan == null ? null : text(plan.path("RefUnits"), "nameRu"), text(l, "customerBin")));
        }
        return new ResultBuy(longer(b, "id"), text(b, "numberAnno"), integer(b, "refBuyStatusId"), text(b, "itogiDatePublic"),
                text(b, "biinSupplier"), integer(b, "refTradeMethodsId"), text(b.path("RefTradeMethods"), "nameRu"), lots, protocols);
    }

    private static ContractRow contractOf(JsonNode c) {
        List<ContractUnitRow> units = new ArrayList<>();
        for (JsonNode u : c.path("ContractUnits")) {
            java.math.BigDecimal withVat = dec(u, "itemPriceWnds");
            java.math.BigDecimal price = withVat != null && withVat.signum() > 0 ? withVat : dec(u, "itemPrice");
            units.add(new ContractUnitRow(longer(u, "lotId"), price, dec(u, "quantity"), dec(u, "totalSumWnds")));
        }
        return new ContractRow(longer(c, "id"), longer(c, "trdBuyId"), text(c, "contractNumber"), text(c, "signDate"),
                integer(c, "refContractStatusId"), text(c, "supplierBiin"), dec(c, "contractSumWnds"), units);
    }

    private static AttemptLot attemptOf(JsonNode l) {
        JsonNode b = l.path("TrdBuy");
        return new AttemptLot(longer(l, "id"), text(l, "lotNumber"), integer(l, "refLotStatusId"), text(l.path("RefLotsStatus"), "nameRu"),
                firstLong(l.path("pointList")), text(l, "trdBuyNumberAnno"), longer(l, "trdBuyId"), integer(b, "refTradeMethodsId"),
                text(b.path("RefTradeMethods"), "nameRu"), text(b, "publishDate"), text(b, "endDate"), text(b, "itogiDatePublic"),
                text(b, "biinSupplier"));
    }

    private static ClosedLot closedLotOf(JsonNode l) {
        JsonNode plan = firstPlan(l);
        JsonNode kato = plan == null ? null : plan.path("PlansKato");
        String place = kato != null && kato.isArray() && kato.size() > 0 ? text(kato.get(0), "fullDeliveryPlaceNameRu") : null;
        return new ClosedLot(longer(l, "id"), text(l, "lotNumber"), text(l, "nameRu"), text(l, "descriptionRu"), dec(l, "count"),
                dec(l, "amount"), longer(l, "trdBuyId"), text(l, "trdBuyNumberAnno"), text(l, "customerBin"), text(l, "customerNameRu"),
                plan == null ? null : text(plan, "refEnstruCode"), plan == null ? null : text(plan, "extraDescRu"),
                plan == null ? null : text(plan.path("RefUnits"), "nameRu"), place, text(l.path("RefTradeMethods"), "nameRu"));
    }
```

Импорты в `GoszakupHttpClient` (если нет): `java.time.LocalDate`, `java.util.ArrayList`, `com.vladoose.nir.integration.goszakup.dto.*` уже подключены звёздочкой; `UpstreamHttp`, `UpstreamIoException`, `UpstreamRetry` — уже есть.

- [ ] **Step 5: Интерфейс и бин клиента**

```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Итоги, договоры, цепочки попыток и цены рынка на goszakup (спека 2026-10-22-tender-results-market-prices §3). Свой бин со
 * своим экземпляром {@link GoszakupHttpClient} (тот же адрес и токен) — как поиск по стране: мок {@link GoszakupClient} в
 * тестах не задевает итоги.
 */
public interface GoszakupResultsClient {
    boolean isConfigured();
    List<ResultBuy> announcements(List<Long> buyIds);
    List<ContractRow> contractsByBuyIds(List<Long> buyIds);
    List<ContractRow> contractsByCustomer(String bin, LocalDate from, LocalDate to);
    List<AttemptLot> lotsByPlanPoints(List<Long> pointIds);
    SearchPage<ClosedLot> closedLots(String word, LocalDate from, LocalDate to, Long after);
    List<ClosedLot> lotsByIds(List<Long> lotIds);
    /** null — файла нет (404). */
    byte[] downloadProtocol(String url);
    /** null — площадка БИН не знает. */
    SubjectInfo subject(String bin);
}
```

```java
package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class GoszakupResultsHttpClient implements GoszakupResultsClient {

    private final GoszakupHttpClient http;

    @Autowired
    public GoszakupResultsHttpClient(ObjectMapper objectMapper,
                                     @Value("${goszakup.api.base-url:https://ows.goszakup.gov.kz/v2}") String baseUrl,
                                     @Value("${goszakup.api.token:}") String token) {
        this(new GoszakupHttpClient(objectMapper, baseUrl, token, 50));
    }

    public GoszakupResultsHttpClient(GoszakupHttpClient http) {
        this.http = http;
    }

    @Override public boolean isConfigured() { return http.isConfigured(); }
    @Override public List<ResultBuy> announcements(List<Long> buyIds) { return http.resultAnnouncements(buyIds); }
    @Override public List<ContractRow> contractsByBuyIds(List<Long> buyIds) { return http.contractsByBuyIds(buyIds); }
    @Override public List<ContractRow> contractsByCustomer(String bin, LocalDate from, LocalDate to) {
        return http.contractsByCustomer(bin, from, to);
    }
    @Override public List<AttemptLot> lotsByPlanPoints(List<Long> pointIds) { return http.lotsByPlanPoints(pointIds); }
    @Override public SearchPage<ClosedLot> closedLots(String word, LocalDate from, LocalDate to, Long after) {
        return http.closedLots(word, from, to, after);
    }
    @Override public List<ClosedLot> lotsByIds(List<Long> lotIds) { return http.lotsByIds(lotIds); }
    @Override public byte[] downloadProtocol(String url) { return http.downloadProtocol(url); }

    @Override
    public SubjectInfo subject(String bin) {
        SubjectDto s = http.fetchSubject(bin);
        return s == null ? null : new SubjectInfo(bin, s.getNameRu(), s.getPhone(), s.getEmail());
    }
}
```

- [ ] **Step 6: Импорт пишет id лота и пункт плана** — `GoszakupTenderWriter.rebuildLots`, после `lot.setSourceRemovedAt(null);`:

```java
            // площадка (спека 2026-10-22-tender-results-market-prices §12): ключ договора и цепочки попыток
            if (d.getId() != null) lot.setExtLotId(d.getId());
            if (d.getPointList() != null && !d.getPointList().isEmpty()) lot.setPlanPointId(d.getPointList().get(0));
```

- [ ] **Step 7: Запустить — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.*' --tests 'com.vladoose.nir.integration.ImportPlanFieldsTest' 2>&1 | tail -20` (sandbox off)
Expected: PASS (прежние тесты пакета — тоже).

- [ ] **Step 8: Мутации**

1. `downloadProtocol`: убрать проверку `startsWith(origin() + "/download/")` → `protocol_onlyFromPlatformHost_withoutToken` красный.
2. `contractOf`: брать всегда `itemPrice` → `contracts_unitPriceWithVatFirst_andAllPages` красный.
3. `pages`: оборвать цикл после первой страницы → тот же тест красный.
4. `rebuildLots`: убрать `setPlanPointId` → `ImportPlanFieldsTest` красный.

- [ ] **Step 9: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration src/test/java/com/vladoose/nir/integration && git commit -m "$(cat <<'EOF'
feat(results): клиент goszakup — итоги, договоры, цепочки, состоявшиеся лоты, протокол; id лота и пункт плана при импорте

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Разбор протоколов итогов — ЗЦП, из одного источника, конкурс

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/tenderresults/protocol/ProtocolFormat.java`, `ProtocolBid.java`, `LotProtocol.java`, `ProtocolResult.java`, `ProtocolText.java`, `ProtocolNums.java`, `ProtocolBuilder.java`, `ZcpProtocolParser.java`, `OiProtocolParser.java`, `KonkursProtocolParser.java`, `ProtocolParser.java`
- Create: `src/test/resources/goszakup/protocols/zcp-winner.txt`, `zcp-no-bids.txt`, `zcp-one-bid.txt`, `oi-failed.txt`, `oi-done.html`, `konkurs-winners.html`, `konkurs-no-bids.html` (обезличенные)
- Test: `src/test/java/com/vladoose/nir/tenderresults/ProtocolParserTest.java`

**Interfaces:**
- Consumes: `PdfTextExtractor.extract(byte[])`, Jsoup, `FailReason`; в тесте — `ReplyFixtures.textPdf(String...)`.
- Produces: `enum ProtocolFormat { ZCP, OI, KONKURS, UNKNOWN }`; `record ProtocolBid(Integer place, String name, String bin, BigDecimal unitPrice, BigDecimal total, LocalDateTime submittedAt, boolean rejected, String rejectReason)`; `record LotProtocol(String lotCode, List<ProtocolBid> bids, int bidsCount, int rejectedCount, String winnerBin, String secondBin, FailReason failReason, String conclusion)`; `record ProtocolResult(ProtocolFormat format, Map<String, LotProtocol> lots) { static ProtocolResult unknown() }`; `ProtocolParser.parse(byte[]) → ProtocolResult` (бросает только на сбое внутри разбора — проход ловит), `ProtocolParser.parseLines(List<String>)`; `ProtocolText.lines(byte[])`, `textLines(String)`, `russian(List<String>)`.

- [ ] **Step 1: Обезличенные фикстуры** (скрипты — в scratchpad сессии, в репозиторий не идут)

Скачать протоколы (токен читается из файла, не печатается):

```python
# get_protocols.py — в scratchpad
import json, os, urllib.request
TOKEN = open(os.path.expanduser('~/.config/ais/goszakup.token')).read().strip()
def q(query, variables):
    req = urllib.request.Request('https://ows.goszakup.gov.kz/v3/graphql',
        data=json.dumps({'query': query, 'variables': variables}).encode(),
        headers={'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json'})
    return json.loads(urllib.request.urlopen(req, timeout=60).read())
for anno in ['17758520-1', '17757894-1', '17779323-1', '17802508-1', '17752538-1', '17593937-1', '17277265-1']:
    r = q('query($a:String){ TrdBuy(filter:{numberAnno:$a}, limit:1){ Files{ nameRu originalName filePath } } }', {'a': anno})
    f = [f for f in r['data']['TrdBuy'][0]['Files'] if f['nameRu'] == 'Протокол итогов'][0]
    data = urllib.request.urlopen(f['filePath'], timeout=60).read()
    ext = '.pdf' if data[:4] == b'%PDF' else '.html'
    open('pi_' + anno.split('-')[0] + ext, 'wb').write(data)
    print(anno, f['originalName'], len(data))
```

PDF → текст тем же PDFBox, что в коде (jar из кеша Gradle):

```java
// PdfText.java — в scratchpad; запуск: java -cp "<pdfbox-3.0.7>:<fontbox-3.0.7>:<pdfbox-io-3.0.7>:<commons-logging-1.3.5>" PdfText.java in.pdf out.txt
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.nio.file.*;
public class PdfText {
    public static void main(String[] a) throws Exception {
        try (var d = Loader.loadPDF(Files.readAllBytes(Path.of(a[0])))) {
            Files.writeString(Path.of(a[1]), new PDFTextStripper().getText(d));
        }
    }
}
```

Обезличивание (поставщики → «Поставщик N», их БИН / ИИН → `9000000000NN` по порядку появления; люди → «Сотрудник N»; таблицы «бенефициарном владении» — вон, ФИО бенефициара в таблице победителя → «Владелец», паспорта, адреса и счета → «—»):

```python
# anonymize.py — в scratchpad
import re, sys

ROW_ZCP = re.compile(r'^(\d+) (.+?) (\d{12}) ([\d.]+) ([\d.]+) ([\d.]+) (\d{2}\.\d{2}\.\d{4})\s+(\d{2}:\d{2}:\d{2})\s*$')

def anonymize_text(t, people):
    t = t.replace('\t', ' ')
    names, bins = {}, {}
    for line in t.splitlines():
        m = ROW_ZCP.match(line.strip())
        if m:
            add(names, bins, m.group(2).strip(), m.group(3))
    for m in re.finditer(r'^(.+?) (\d{12}) Адрес:', t, flags=re.M):
        add(names, bins, m.group(1).strip(), m.group(2))
    return replace_all(t, names, bins, people)

def anonymize_html(h, people):
    people = list(people) + [m.group(1).strip() for m in re.finditer(r'<td>\s*\d+\s*</td>\s*<td>([^<]+)</td>\s*<td>[^<]*</td>\s*<td>\s*(?:Төраға|Мүше|Хатшы|Председатель|Член комиссии|Секретарь)\s*</td>', h)]
    people = list(dict.fromkeys(people))
    h = re.sub(r'<table[^>]*class="benefit"[^>]*>\s*<caption>[^<]*(?:бенефициарном владении|бенефициарлық иелігі)[^<]*</caption>.*?</table>', '', h, flags=re.S)
    h = re.sub(r'(<td[^>]*>\s*\d{12}\s*</td>\s*<td[^>]*>)\s*[А-ЯЁӘҒҚҢӨҰҮҺІ][а-яёәғқңөұүһі]+ [А-ЯЁӘҒҚҢӨҰҮҺІ][а-яёәғқңөұүһі]+(?: [А-ЯЁӘҒҚҢӨҰҮҺІ][а-яёәғқңөұүһі]+)?\s*(</td>)', r'\1Владелец\2', h)
    h = re.sub(r'\b\d{9} \d{2}\.\d{2}\.\d{4},?', '—', h)
    h = re.sub(r'>\s+<', '><', h)
    h = re.sub(r'[ \t]{2,}', ' ', h)
    h = re.sub(r'(КБе|ИИК|ЖСК|БИК|БСК)[^<|]*', r'\1: —', h)
    names, bins = {}, {}
    for m in re.finditer(r'<td>\s*\d+\s*</td>\s*<td>([^<]+)</td>\s*<td>\s*(\d{12})\s*</td>', h):
        add(names, bins, m.group(1).strip(), m.group(2))
    for m in re.finditer(r'<td>([^<]+)</td>\s*<td>\s*(\d{12})\s*</td>', h):
        add(names, bins, m.group(1).strip(), m.group(2))
    return replace_all(h, names, bins, people)

def add(names, bins, name, bin_):
    if bin_ not in bins:
        bins[bin_] = '9000000000%02d' % (len(bins) + 1)
        names.setdefault(name, 'Поставщик %d' % len(bins))
    else:
        names.setdefault(name, 'Поставщик %d' % (list(bins).index(bin_) + 1))

def replace_all(t, names, bins, people):
    t = re.sub(r'(Адрес:|Мекенжай:)[^|\n<]*', r'\1 —', t)
    for name in sorted(names, key=len, reverse=True):
        t = t.replace(name, names[name])
    for b, fake in bins.items():
        t = t.replace(b, fake)
    for i, p in enumerate(people, 1):
        t = t.replace(p, 'Сотрудник %d' % i)
    return t

if __name__ == '__main__':
    src, dst, kind = sys.argv[1], sys.argv[2], sys.argv[3]
    data = open(src, encoding='utf-8', errors='replace').read()
    out = anonymize_text(data, sys.argv[4:]) if kind == 'text' else anonymize_html(data, sys.argv[4:])
    open(dst, 'w', encoding='utf-8').write(out)
    print(dst, 'остались 12-значные:', sorted(set(re.findall(r'\b\d{12}\b', out))))
```

```bash
cd <scratchpad> && python3 get_protocols.py
for n in 17758520 17757894 17779323 17802508; do java -cp "$CP" PdfText.java pi_$n.pdf raw_$n.txt; done
D=/Users/vlad/IdeaProjects/AIS/src/test/resources/goszakup/protocols && mkdir -p $D
python3 anonymize.py raw_17758520.txt $D/zcp-winner.txt text
python3 anonymize.py raw_17757894.txt $D/zcp-no-bids.txt text
python3 anonymize.py raw_17779323.txt $D/zcp-one-bid.txt text
python3 anonymize.py raw_17802508.txt $D/oi-failed.txt text "<ФИО представителя из протокола>"
python3 anonymize.py pi_17752538.html $D/oi-done.html html "<ФИО представителя из протокола>"
python3 anonymize.py pi_17593937.html $D/konkurs-winners.html html
python3 anonymize.py pi_17277265.html $D/konkurs-no-bids.html html
```

Проверка: «остались 12-значные» — только `9000000000NN` и БИН больниц (`110240019426`, `080740007819`); `grep -E '[0-9]{9} [0-9]{2}\.[0-9]{2}\.[0-9]{4}'` по фикстурам — пусто; ФИО (`grep -E '[А-ЯЁ][а-яё]+ [А-ЯЁ][а-яё]+ [А-ЯЁ][а-яё]+(вич|вна|ович|евна|улы|кызы)'`) — пусто. ФИО представителей в протоколах ОИ — передать аргументом (видны в таблице «Информация о представителе…»).

- [ ] **Step 2: Тест разбора**

```java
package com.vladoose.nir.tenderresults;

import com.vladoose.nir.entity.FailReason;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import com.vladoose.nir.service.tenderresults.protocol.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Протоколы итогов (спека 2026-10-22-tender-results-market-prices §5) — на обезличенных настоящих протоколах. */
class ProtocolParserTest {

    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = ProtocolParserTest.class.getResourceAsStream("/goszakup/protocols/" + name)) {
            return in.readAllBytes();
        }
    }

    /** Текст PDF — как его отдаёт PDFBox (фикстура — уже текст: обезличить PDF нельзя). */
    private static ProtocolResult text(String name) throws IOException {
        return ProtocolParser.parseLines(ProtocolText.textLines(new String(bytes(name), StandardCharsets.UTF_8)));
    }

    @Test
    void zcp_winner_tenBids_sortedByPrice() throws IOException {
        ProtocolResult r = text("zcp-winner.txt");
        assertThat(r.format()).isEqualTo(ProtocolFormat.ZCP);
        LotProtocol l = r.lots().get("88214663-ЗЦП2");
        assertThat(l.bidsCount()).isEqualTo(10);
        assertThat(l.rejectedCount()).isZero();
        assertThat(l.bids()).hasSize(10);
        ProtocolBid first = l.bids().get(0);
        assertThat(first.place()).isEqualTo(1);
        assertThat(first.name()).isEqualTo("Поставщик 1");
        assertThat(first.bin()).isEqualTo("900000000001");
        assertThat(first.unitPrice()).isEqualByComparingTo("251000");
        assertThat(first.total()).isEqualByComparingTo("753000");
        assertThat(first.submittedAt()).isEqualTo(LocalDateTime.of(2026, 10, 13, 17, 42, 30));
        assertThat(l.bids().get(9).unitPrice()).isEqualByComparingTo("613500");
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        assertThat(l.secondBin()).isEqualTo("900000000002");
        assertThat(l.failReason()).isNull();
    }

    @Test
    void zcp_noBids() throws IOException {
        LotProtocol l = text("zcp-no-bids.txt").lots().get("88208881-ЗЦП1");
        assertThat(l.bidsCount()).isZero();
        assertThat(l.bids()).isEmpty();
        assertThat(l.failReason()).isEqualTo(FailReason.NO_BIDS);
    }

    @Test
    void zcp_oneBid() throws IOException {
        LotProtocol l = text("zcp-one-bid.txt").lots().get("88111123-ЗЦП2");
        assertThat(l.bidsCount()).isEqualTo(1);
        assertThat(l.bids().get(0).unitPrice()).isEqualByComparingTo("3700000");
        assertThat(l.failReason()).isEqualTo(FailReason.ONE_BID);
        assertThat(l.winnerBin()).isNull();
    }

    @Test
    void oi_failed_supplierRefused() throws IOException {
        ProtocolResult r = text("oi-failed.txt");
        assertThat(r.format()).isEqualTo(ProtocolFormat.OI);
        LotProtocol l = r.lots().get("88208881-ОИ3");
        assertThat(l.bids()).extracting(ProtocolBid::bin).containsExactly("900000000001");
        assertThat(l.bids().get(0).total()).isNull();
        assertThat(l.bidsCount()).isZero();
        assertThat(l.failReason()).isEqualTo(FailReason.REFUSED);
    }

    @Test
    void oi_done_html() throws IOException {
        ProtocolResult r = ProtocolParser.parse(bytes("oi-done.html"));
        assertThat(r.format()).isEqualTo(ProtocolFormat.OI);
        LotProtocol l = r.lots().get("88138321-ОИ2");
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        assertThat(l.bids().get(0).total()).isEqualByComparingTo("43000");
        assertThat(l.bidsCount()).isEqualTo(1);
        assertThat(l.failReason()).isNull();
    }

    @Test
    void konkurs_winnersAndFailedLots_html() throws IOException {
        ProtocolResult r = ProtocolParser.parse(bytes("konkurs-winners.html"));
        assertThat(r.format()).isEqualTo(ProtocolFormat.KONKURS);
        assertThat(r.lots()).hasSize(8);
        LotProtocol ekg = r.lots().get("87830010-ОК1");
        assertThat(ekg.bidsCount()).isEqualTo(2);
        assertThat(ekg.bids()).extracting(ProtocolBid::bin).containsExactly("900000000002", "900000000001");
        assertThat(ekg.bids().get(0).total()).isEqualByComparingTo("8745000");
        assertThat(ekg.bids().get(0).unitPrice()).isEqualByComparingTo("8745000");
        assertThat(ekg.winnerBin()).isEqualTo("900000000002");
        assertThat(ekg.secondBin()).isEqualTo("900000000001");
        assertThat(ekg.failReason()).isNull();
        LotProtocol cable = r.lots().get("87696595-ОК1");
        assertThat(cable.bidsCount()).isZero();
        assertThat(cable.failReason()).isEqualTo(FailReason.NO_BIDS);
        assertThat(cable.winnerBin()).isNull();
    }

    @Test
    void konkurs_noBids_html() throws IOException {
        LotProtocol l = ProtocolParser.parse(bytes("konkurs-no-bids.html")).lots().get("80997074-ОК3");
        assertThat(l.failReason()).isEqualTo(FailReason.NO_BIDS);
    }

    /** Цена конкурса — за лот: за единицу делится на количество из перечня лотов (Review Focus 4). */
    @Test
    void konkurs_unitPriceDividedByQuantity() {
        ProtocolResult r = ProtocolParser.parseLines(List.of(
                "Протокол об итогах государственных закупок способом конкурса № 1-ОК1",
                "1 | 5-ОК1 | Насос | 2 | 100 | 200",
                "№ лота 5-ОК1",
                "Информация о представленных заявках на участие в конкурсе (лоте): 1",
                "1 | Поставщик 1 | 900000000001 | 01.10.2026 10:00:00.000",
                "Расчет условных цен участников конкурса:",
                "1 | Поставщик 1 | 900000000001 | 200 | 180 | 0 | 0 | 180 | 1 | 0 | 0 | 01.10.2026 10:00:00",
                "1. Определить победителем по лоту №5-ОК1:",
                "1 | Поставщик 1 | 900000000001 | Владелец"));
        LotProtocol l = r.lots().get("5-ОК1");
        assertThat(l.bids().get(0).unitPrice()).isEqualByComparingTo("90.00");
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        LotProtocol noQty = ProtocolParser.parseLines(List.of(
                "Протокол об итогах государственных закупок способом конкурса № 1-ОК1",
                "№ лота 6-ОК1",
                "Расчет условных цен участников конкурса:",
                "1 | Поставщик 1 | 900000000001 | 200 | 180 | 0 | 0 | 180 | 1 | 0 | 0 | 01.10.2026 10:00:00")).lots().get("6-ОК1");
        assertThat(noQty.bids().get(0).unitPrice()).as("количества нет — цены за единицу нет").isNull();
        assertThat(noQty.bids().get(0).total()).isEqualByComparingTo("180");
    }

    /** Файл PDF целиком (а не текст) — через PDFBox; название, перенесённое на следующую строку, склеивается. */
    @Test
    void zcp_pdfBytes_andWrappedName() throws Exception {
        byte[] pdf = ReplyFixtures.textPdf(
                "Протокол об итогах 1-ЗЦП1",
                "Лот № 5-ЗЦП1",
                "Потенциальными поставщиками представлены следующие ценовые предложения: 1",
                "1 Товарищество с ограниченной",
                "ответственностью Поставщик 900000000001 1000 0 2000 13.10.2026 17:42:30",
                "Признать государственную закупку по лоту № 5-ЗЦП1 несостоявшейся в связи с представлением одного ценового предложения");
        LotProtocol l = ProtocolParser.parse(pdf).lots().get("5-ЗЦП1");
        assertThat(l.bids().get(0).name()).isEqualTo("Товарищество с ограниченной ответственностью Поставщик");
        assertThat(l.bids().get(0).unitPrice()).isEqualByComparingTo("1000");
        assertThat(l.failReason()).isEqualTo(FailReason.ONE_BID);
    }

    /** Незнакомый вид и мусор — UNKNOWN, не исключение (Review Focus 1). */
    @Test
    void unknownFormatAndGarbage() {
        assertThat(ProtocolParser.parseLines(List.of("Протокол об итогах электронного аукциона № 1-ЭА1", "что-то")).format())
                .isEqualTo(ProtocolFormat.UNKNOWN);
        assertThat(ProtocolParser.parse("просто текст".getBytes(StandardCharsets.UTF_8)).format()).isEqualTo(ProtocolFormat.UNKNOWN);
        assertThat(ProtocolParser.parse(new byte[0]).format()).isEqualTo(ProtocolFormat.UNKNOWN);
    }
}
```

- [ ] **Step 3: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.tenderresults.ProtocolParserTest' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (нет пакета `protocol`).

- [ ] **Step 4: Записи разбора**

```java
package com.vladoose.nir.service.tenderresults.protocol;

/** Вид протокола итогов (спека 2026-10-22-tender-results-market-prices §5). */
public enum ProtocolFormat { ZCP, OI, KONKURS, UNKNOWN }
```

```java
package com.vladoose.nir.service.tenderresults.protocol;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Участник протокола: место (у отклонённых — пусто), цена за единицу (конкурс / ОИ — из суммы и количества), сумма, время подачи. */
public record ProtocolBid(Integer place, String name, String bin, BigDecimal unitPrice, BigDecimal total, LocalDateTime submittedAt,
                          boolean rejected, String rejectReason) {}
```

```java
package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.entity.FailReason;

import java.util.List;

/** Итог лота по протоколу: участники, сколько подали и отклонено, победитель и второе место, причина провала, вывод. */
public record LotProtocol(String lotCode, List<ProtocolBid> bids, int bidsCount, int rejectedCount, String winnerBin, String secondBin,
                          FailReason failReason, String conclusion) {}
```

```java
package com.vladoose.nir.service.tenderresults.protocol;

import java.util.Map;

/** Разбор файла: вид и итоги по номеру лота площадки. */
public record ProtocolResult(ProtocolFormat format, Map<String, LotProtocol> lots) {
    public static ProtocolResult unknown() {
        return new ProtocolResult(ProtocolFormat.UNKNOWN, Map.of());
    }
}
```

```java
package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.entity.FailReason;

import java.util.ArrayList;
import java.util.List;

/** Накопитель итога лота при разборе. Победитель есть — причины провала нет. */
final class ProtocolBuilder {

    final String code;
    final List<ProtocolBid> bids = new ArrayList<>();
    Integer bidsCount;
    Integer rejectedCount;
    String winnerBin;
    String secondBin;
    String conclusion;
    FailReason failReason;

    ProtocolBuilder(String code) {
        this.code = code;
    }

    LotProtocol build() {
        int accepted = (int) bids.stream().filter(b -> !b.rejected()).count();
        int rejected = (int) bids.stream().filter(ProtocolBid::rejected).count();
        return new LotProtocol(code, List.copyOf(bids), bidsCount != null ? bidsCount : accepted,
                rejectedCount != null ? rejectedCount : rejected, winnerBin, secondBin, winnerBin != null ? null : failReason, conclusion);
    }
}
```

```java
package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.entity.FailReason;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Числа, время и причины провала в протоколах. */
final class ProtocolNums {

    private static final Pattern DATE_TIME = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final Pattern COUNT = Pattern.compile(":\\s*(\\d+)\\s*$");

    private ProtocolNums() {}

    /** «251000», «274787.77», «45 000.00» → число; не число — null. */
    static BigDecimal money(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\s\\u00A0]", "").replace(',', '.');
        return t.matches("\\d+(\\.\\d+)?") ? new BigDecimal(t) : null;
    }

    /** «13.10.2026 17:42:30» (и с долями секунды) → время площадки. */
    static LocalDateTime dateTime(String s) {
        Matcher m = DATE_TIME.matcher(s == null ? "" : s);
        if (!m.find()) return null;
        try {
            return LocalDateTime.parse(m.group(1) + " " + m.group(2), FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** «…предложения: 10», «(лоте): 2» → число в конце; нет — 0. */
    static int count(String line) {
        Matcher m = COUNT.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Причина провала по выводу протокола: одна заявка — раньше «отсутствия» (в одной фразе бывают оба слова). */
    static FailReason reason(String conclusion) {
        String t = conclusion == null ? "" : conclusion.toLowerCase(Locale.ROOT);
        if (t.contains("одного ценового предложения") || t.contains("одной заявки") || t.contains("единственной заявки")) {
            return FailReason.ONE_BID;
        }
        if (t.contains("отсутстви")) return FailReason.NO_BIDS;
        if (t.contains("отказал")) return FailReason.REFUSED;
        return FailReason.OTHER;
    }

    static boolean isBin(String s) {
        return s != null && s.trim().matches("\\d{12}");
    }
}
```

- [ ] **Step 5: Строки протокола**

```java
package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.util.PdfTextExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Протокол итогов → строки (спека 2026-10-22-tender-results-market-prices §5): PDF — текст PDFBox по строкам; HTML — заголовки,
 * абзацы (по &lt;br&gt;), подписи таблиц и строки таблиц ячейками через « | ». Пробелы, неразрывные пробелы и табуляции — одним
 * пробелом. Русская часть — с первой строки «Протокол об итогах» (казахская идёт раньше).
 */
public final class ProtocolText {

    /** &lt;br&gt; внутри абзаца — разрыв строки; символ, который Jsoup пробелом не считает и не схлопывает. */
    private static final String BR = "\u2028";

    private ProtocolText() {}

    /** null — не PDF и не HTML или PDF не читается. */
    public static List<String> lines(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            String text = PdfTextExtractor.extract(bytes);
            return text == null ? null : textLines(text);
        }
        String html = new String(bytes, StandardCharsets.UTF_8);
        String low = html.toLowerCase(Locale.ROOT);
        if (!low.contains("<table") && !low.contains("<html")) return null;
        Document doc = Jsoup.parse(html.replaceAll("(?i)<br\\s*/?>", BR));
        List<String> out = new ArrayList<>();
        for (Element e : doc.body().select("h1, h2, h3, p, caption, tr")) {
            if (e.tagName().equals("tr")) {
                String row = e.children().stream().filter(c -> c.tagName().equals("td") || c.tagName().equals("th"))
                        .map(c -> clean(c.text())).collect(Collectors.joining(" | "));
                if (!row.replace("|", "").isBlank()) out.add(row);
            } else {
                for (String part : e.text().split(BR)) {
                    String t = clean(part);
                    if (!t.isEmpty()) out.add(t);
                }
            }
        }
        return out;
    }

    /** Текст (PDFBox) → строки без пустых. */
    public static List<String> textLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\\r?\\n")) {
            String t = clean(l);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** С первой строки «Протокол об итогах»; её нет — все строки. */
    public static List<String> russian(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Протокол об итогах")) return lines.subList(i, lines.size());
        }
        return lines;
    }

    static String clean(String s) {
        return s == null ? "" : s.replace(BR, " ").replaceAll("[\\s\\u00A0]+", " ").trim();
    }
}
```

- [ ] **Step 6: Три разборщика и выбор вида**

```java
package com.vladoose.nir.service.tenderresults.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Протокол запроса ценовых предложений (PDF): раздел лота — «Лот № …»; отклонённые порталом и ценовые предложения —
 * «<№> <название> <БИН> <цена за ед.> <сумма по ст. 13> <общая сумма> <дата> <время>» (название бывает перенесено);
 * вывод — «Определить победителем по лоту № … : <БИН> …, … второе место <БИН> …» или «Признать … несостоявшейся в связи с …».
 */
final class ZcpProtocolParser {

    private static final Pattern LOT = Pattern.compile("^Лот №\\s*(\\S+)$");
    private static final Pattern ROW = Pattern.compile("^(\\d+) (.+?) (\\d{12}) (\\d+(?:\\.\\d+)?) (\\d+(?:\\.\\d+)?) (\\d+(?:\\.\\d+)?) "
            + "(\\d{2}\\.\\d{2}\\.\\d{4}) (\\d{2}:\\d{2}:\\d{2})$");
    private static final Pattern REJECTED_ROW = Pattern.compile("^(\\d+) (.+?) (\\d{12}) (.+)$");
    private static final Pattern WINNER = Pattern.compile("победителем по лоту №\\s*(\\S+?)\\s*:\\s*(\\d{12})");
    private static final Pattern SECOND = Pattern.compile("второе место\\s+(\\d{12})");
    private static final Pattern FAILED_LOT = Pattern.compile("по лоту №\\s*(\\S+)\\s+несостоявшейся");

    private enum Mode { NONE, REJECTED, BIDS }

    private ZcpProtocolParser() {}

    static Map<String, LotProtocol> parse(List<String> ru) {
        Map<String, ProtocolBuilder> lots = new LinkedHashMap<>();
        ProtocolBuilder cur = null;
        Mode mode = Mode.NONE;
        for (int i = 0; i < ru.size(); i++) {
            String line = ru.get(i);
            Matcher lot = LOT.matcher(line);
            if (lot.matches()) {
                cur = lots.computeIfAbsent(lot.group(1), ProtocolBuilder::new);
                mode = Mode.NONE;
                continue;
            }
            Matcher w = WINNER.matcher(line);
            if (w.find()) {
                ProtocolBuilder b = lots.computeIfAbsent(w.group(1), ProtocolBuilder::new);
                b.winnerBin = w.group(2);
                Matcher s = SECOND.matcher(line);
                if (s.find()) b.secondBin = s.group(1);
                b.conclusion = line;
                mode = Mode.NONE;
                continue;
            }
            if (line.startsWith("Признать")) {
                Matcher f = FAILED_LOT.matcher(line);
                ProtocolBuilder b = f.find() ? lots.computeIfAbsent(f.group(1), ProtocolBuilder::new) : cur;
                if (b != null) {
                    b.failReason = ProtocolNums.reason(line);
                    b.conclusion = line;
                }
                mode = Mode.NONE;
                continue;
            }
            if (cur == null) continue;
            if (line.contains("автоматически отклоненные")) {
                cur.rejectedCount = ProtocolNums.count(line);
                mode = Mode.REJECTED;
                continue;
            }
            if (line.contains("представлены следующие ценовые предложения")) {
                cur.bidsCount = ProtocolNums.count(line);
                mode = Mode.BIDS;
                continue;
            }
            if (mode == Mode.BIDS) {
                String joined = line;
                for (int k = 0; k <= 2 && i + k < ru.size(); k++) {   // название бывает перенесено: склеить до двух следующих строк
                    if (k > 0) joined = joined + " " + ru.get(i + k);
                    Matcher r = ROW.matcher(joined);
                    if (r.matches()) {
                        cur.bids.add(new ProtocolBid(Integer.parseInt(r.group(1)), r.group(2).trim(), r.group(3),
                                ProtocolNums.money(r.group(4)), ProtocolNums.money(r.group(6)),
                                ProtocolNums.dateTime(r.group(7) + " " + r.group(8)), false, null));
                        i += k;
                        break;
                    }
                }
            } else if (mode == Mode.REJECTED) {
                Matcher r = REJECTED_ROW.matcher(line);
                if (r.matches()) cur.bids.add(new ProtocolBid(null, r.group(2).trim(), r.group(3), null, null, null, true, r.group(4).trim()));
            }
        }
        Map<String, LotProtocol> out = new LinkedHashMap<>();
        lots.forEach((code, b) -> out.put(code, b.build()));
        return out;
    }
}
```

```java
package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.entity.FailReason;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Протокол закупки из одного источника по несостоявшимся (HTML; сорвавшиеся — PDF): «№ лота: …», приглашённый поставщик
 * (название и БИН), его цена («выделенная сумма, цена поставщика, время»), решение — «Решено заключить договор…» или
 * «…не состоялась в связи с тем, что … отказался…».
 */
final class OiProtocolParser {

    private static final Pattern LOT = Pattern.compile("№ лота:\\s*(\\S+)");
    private static final Pattern BIN = Pattern.compile("(\\d{12})");
    private static final Pattern PRICE = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*\\|?\\s*(\\d+(?:\\.\\d+)?)\\s*\\|?\\s*"
            + "(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})");

    private OiProtocolParser() {}

    static Map<String, LotProtocol> parse(List<String> ru) {
        String code = null, name = null, bin = null, conclusion = null, section = "";
        BigDecimal price = null;
        LocalDateTime at = null;
        boolean done = false;
        FailReason fail = null;
        for (String line : ru) {
            Matcher lot = LOT.matcher(line);
            if (code == null && lot.find()) {
                code = lot.group(1);
                continue;
            }
            if (line.startsWith("Информация о приглашенном")) { section = "SUPPLIER"; continue; }
            if (line.startsWith("Информация о ценовом предложении")) { section = "PRICE"; continue; }
            if (line.startsWith("Результаты соответствия")) { section = ""; continue; }
            if (line.contains("Решено заключить договор")) {
                done = true;
                conclusion = line;
                continue;
            }
            if (line.contains("не состоялась")) {
                fail = ProtocolNums.reason(line);
                conclusion = line;
                continue;
            }
            if (section.equals("SUPPLIER") && bin == null) {
                Matcher m = BIN.matcher(line);
                if (m.find()) {
                    bin = m.group(1);
                    name = line.substring(0, m.start()).replace("|", "").trim();
                }
            } else if (section.equals("PRICE") && price == null) {
                Matcher m = PRICE.matcher(line);
                if (m.find()) {
                    price = ProtocolNums.money(m.group(2));
                    at = ProtocolNums.dateTime(m.group(3));
                }
            }
        }
        if (code == null) return Map.of();
        ProtocolBuilder b = new ProtocolBuilder(code);
        if (bin != null) b.bids.add(new ProtocolBid(price == null ? null : 1, name, bin, null, price, at, false, null));
        b.bidsCount = price == null ? 0 : 1;
        b.rejectedCount = 0;
        if (done && bin != null) b.winnerBin = bin;
        else b.failReason = fail != null ? fail : FailReason.OTHER;
        b.conclusion = conclusion;
        return Map.of(code, b.build());
    }
}
```

```java
package com.vladoose.nir.service.tenderresults.protocol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Протокол открытого конкурса (HTML): перечень лотов (количество), раздел лота «№ лота …», таблицы «заявки», «отклонённые»,
 * «расчёт условных цен» (цена поставщика — за лот), «Определить победителем по лоту …», «занявший второе место»; провал —
 * «Признать … по лоту № … несостоявшейся в связи с …». Строки таблиц — ячейки через « | ».
 */
final class KonkursProtocolParser {

    private static final Pattern LOT = Pattern.compile("^№ лота\\s+(\\S+)");
    private static final Pattern WINNER_CAPTION = Pattern.compile("победителем по лоту №\\s*\\S+?\\s*:?$");
    private static final Pattern FAILED_LOT = Pattern.compile("по лоту №\\s*(\\S+)\\s+несостоявшейся");
    private static final Pattern LOT_CODE = Pattern.compile("\\d+-[А-ЯA-Z]+\\d+");

    private enum Mode { NONE, APPS, REJECTED, PRICES, WINNER, SECOND }

    private KonkursProtocolParser() {}

    static Map<String, LotProtocol> parse(List<String> ru) {
        Map<String, BigDecimal> qty = quantities(ru);
        Map<String, ProtocolBuilder> lots = new LinkedHashMap<>();
        Map<String, List<ProtocolBid>> apps = new LinkedHashMap<>();
        ProtocolBuilder cur = null;
        Mode mode = Mode.NONE;
        for (String line : ru) {
            Matcher lot = LOT.matcher(line);
            if (lot.find()) {
                cur = lots.computeIfAbsent(lot.group(1), ProtocolBuilder::new);
                mode = Mode.NONE;
                continue;
            }
            if (line.startsWith("Признать")) {
                Matcher f = FAILED_LOT.matcher(line);
                ProtocolBuilder b = f.find() ? lots.computeIfAbsent(f.group(1), ProtocolBuilder::new) : cur;
                if (b != null) {
                    b.failReason = ProtocolNums.reason(line);
                    b.conclusion = line;
                }
                mode = Mode.NONE;
                continue;
            }
            if (cur == null) continue;
            if (!line.contains(" | ")) {
                if (line.startsWith("Информация о представленных заявках")) { cur.bidsCount = ProtocolNums.count(line); mode = Mode.APPS; }
                else if (line.startsWith("Отклоненные заявки")) { cur.rejectedCount = ProtocolNums.count(line); mode = Mode.REJECTED; }
                else if (line.startsWith("Расчет условных цен")) mode = Mode.PRICES;
                else if (WINNER_CAPTION.matcher(line).find()) mode = Mode.WINNER;
                else if (line.contains("занявший второе место")) mode = Mode.SECOND;
                else if (!line.startsWith("Решение конкурсной")) mode = Mode.NONE;
                continue;
            }
            String[] c = line.split(" \\| ", -1);
            if (c.length < 3 || !c[0].trim().matches("\\d+") || !ProtocolNums.isBin(c[2])) continue;
            String name = c[1].trim();
            String bin = c[2].trim();
            switch (mode) {
                case APPS -> apps.computeIfAbsent(cur.code, k -> new ArrayList<>())
                        .add(new ProtocolBid(null, name, bin, null, null, ProtocolNums.dateTime(c[c.length - 1]), false, null));
                case REJECTED -> cur.bids.add(new ProtocolBid(null, name, bin, null, null, null, true, c.length > 3 ? c[3].trim() : null));
                case PRICES -> {
                    BigDecimal total = c.length > 4 ? ProtocolNums.money(c[4]) : null;
                    BigDecimal q = qty.get(cur.code);
                    BigDecimal unit = total == null || q == null || q.signum() <= 0 ? null : total.divide(q, 2, RoundingMode.HALF_UP);
                    cur.bids.add(new ProtocolBid(Integer.parseInt(c[0].trim()), name, bin, unit, total,
                            ProtocolNums.dateTime(c[c.length - 1]), false, null));
                }
                case WINNER -> { if (cur.winnerBin == null) cur.winnerBin = bin; }
                case SECOND -> { if (cur.secondBin == null) cur.secondBin = bin; }
                default -> { }
            }
        }
        // заявки есть, а расчёта цен нет (лот сорвался после рассмотрения) — участники без цен
        apps.forEach((code, list) -> {
            ProtocolBuilder b = lots.get(code);
            if (b != null && b.bids.stream().allMatch(ProtocolBid::rejected)) b.bids.addAll(0, list);
        });
        Map<String, LotProtocol> out = new LinkedHashMap<>();
        lots.forEach((code, b) -> out.put(code, b.build()));
        return out;
    }

    /** Перечень лотов в начале протокола: «№ | № лота | Наименование | Количество | …» → количество по номеру лота. */
    static Map<String, BigDecimal> quantities(List<String> ru) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (String line : ru) {
            String[] c = line.split(" \\| ", -1);
            if (c.length >= 4 && c[0].trim().matches("\\d+") && LOT_CODE.matcher(c[1].trim()).matches()) {
                BigDecimal q = ProtocolNums.money(c[3]);
                if (q != null) out.putIfAbsent(c[1].trim(), q);
            }
        }
        return out;
    }
}
```

```java
package com.vladoose.nir.service.tenderresults.protocol;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Разбор «Протокола итогов» goszakup (спека 2026-10-22-tender-results-market-prices §5). Вид — по первой строке русской части;
 * незнакомый вид, пустой разбор, нечитаемый файл — UNKNOWN. Сбой внутри разбора — исключение: проход его ловит и помечает
 * протокол «не разобран».
 */
public final class ProtocolParser {

    private static final Pattern ZCP_HEAD = Pattern.compile("Протокол об итогах \\d+-ЗЦП\\d*");

    private ProtocolParser() {}

    public static ProtocolResult parse(byte[] bytes) {
        List<String> lines = ProtocolText.lines(bytes);
        return lines == null ? ProtocolResult.unknown() : parseLines(lines);
    }

    public static ProtocolResult parseLines(List<String> lines) {
        List<String> ru = ProtocolText.russian(lines);
        if (ru.isEmpty()) return ProtocolResult.unknown();
        String head = ru.get(0);
        ProtocolFormat format;
        Map<String, LotProtocol> lots;
        if (head.contains("способом конкурса")) {
            format = ProtocolFormat.KONKURS;
            lots = KonkursProtocolParser.parse(ru);
        } else if (head.contains("из одного источника")) {
            format = ProtocolFormat.OI;
            lots = OiProtocolParser.parse(ru);
        } else if (ZCP_HEAD.matcher(head).find()) {
            format = ProtocolFormat.ZCP;
            lots = ZcpProtocolParser.parse(ru);
        } else {
            return ProtocolResult.unknown();
        }
        return lots.isEmpty() ? ProtocolResult.unknown() : new ProtocolResult(format, lots);
    }
}
```

- [ ] **Step 7: Запустить — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.tenderresults.ProtocolParserTest' 2>&1 | tail -20` (sandbox off)
Expected: PASS (10 тестов). Если строка конкурса не совпала — сверить вид строк с `ProtocolText.lines` (вывести `lines` в тесте временно), не подгонять фикстуру руками.

- [ ] **Step 8: Мутации**

1. `ProtocolNums.reason`: проверку «одного ценового предложения» поставить после «отсутстви» → без изменений; поменять возвращаемое на `NO_BIDS` → `zcp_oneBid` красный.
2. `ZcpProtocolParser`: убрать склейку строк (`k <= 0`) → `zcp_pdfBytes_andWrappedName` красный.
3. `KonkursProtocolParser`: `unit = total` без деления → `konkurs_unitPriceDividedByQuantity` красный.
4. `KonkursProtocolParser`: убрать `case WINNER` → `konkurs_winnersAndFailedLots_html` красный.
5. `OiProtocolParser`: `fail` всегда `OTHER` → `oi_failed_supplierRefused` красный.
6. `ProtocolText.russian`: вернуть все строки → тесты HTML краснеют (казахская часть разбирается первой).

- [ ] **Step 9: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/tenderresults/protocol \
  src/test/resources/goszakup/protocols src/test/java/com/vladoose/nir/tenderresults/ProtocolParserTest.java && git commit -m "$(cat <<'EOF'
feat(results): разбор протоколов итогов goszakup — запрос цен, из одного источника, конкурс (обезличенные фикстуры)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Справочник площадки — контакты заказчика и названия поставщиков

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/tenderresults/GoszakupSubjects.java`
- Create: `src/test/java/com/vladoose/nir/tenderresults/FakeGoszakupResultsClient.java`
- Test: `src/test/java/com/vladoose/nir/service/tenderresults/GoszakupSubjectsTest.java`

**Interfaces:**
- Consumes: `GoszakupResultsClient.subject(String)`, `GoszakupSubjectRepository`, `ProductWatchService.stopping(RuntimeException)`.
- Produces: `GoszakupSubjects.get(String bin) → SubjectInfo` (null — нет или площадка не знает), `GoszakupSubjects.names(Collection<String> bins, int fetchLimit) → Map<String, String>`; тестовый `FakeGoszakupResultsClient` (поля `buys`, `contracts`, `attempts`, `closed`, `lotsById`, `files`, `subjects`, `calls`, `forbidFromCall`, `downloadError`, `configured`).

- [ ] **Step 1: Фейк клиента (общий для задач 4–10)**

```java
package com.vladoose.nir.tenderresults;

import com.vladoose.nir.integration.goszakup.GoszakupCallException;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.dto.*;

import java.time.LocalDate;
import java.util.*;

/** Управляемый фейк итогов goszakup: ответы по id объявления, пункту плана, слову, адресу файла; 403 с N-го вызова. */
public class FakeGoszakupResultsClient implements GoszakupResultsClient {
    public boolean configured = true;
    public final Map<Long, ResultBuy> buys = new HashMap<>();
    public final List<ContractRow> contracts = new ArrayList<>();
    public final Map<Long, List<AttemptLot>> attempts = new HashMap<>();
    public final Map<String, List<ClosedLot>> closed = new HashMap<>();
    public final Map<Long, ClosedLot> lotsById = new HashMap<>();
    public final Map<String, byte[]> files = new HashMap<>();
    public final Map<String, SubjectInfo> subjects = new HashMap<>();
    public final List<String> calls = new ArrayList<>();
    /** С какого по счёту вызова отвечать 403 (0 — никогда). */
    public int forbidFromCall = 0;
    /** Сбой скачивания протокола (не стоп-код). */
    public RuntimeException downloadError;
    /** Сбой справочника (не стоп-код). */
    public RuntimeException subjectError;

    private void call(String what) {
        calls.add(what);
        if (forbidFromCall > 0 && calls.size() >= forbidFromCall) {
            throw new GoszakupCallException("goszakup API 403 на /v3/graphql", 403, false, null);
        }
    }

    @Override public boolean isConfigured() { return configured; }

    @Override public List<ResultBuy> announcements(List<Long> ids) {
        call("buys:" + ids);
        return ids.stream().map(buys::get).filter(Objects::nonNull).toList();
    }

    @Override public List<ContractRow> contractsByBuyIds(List<Long> ids) {
        call("contracts:" + ids);
        return contracts.stream().filter(c -> ids.contains(c.buyId())).toList();
    }

    @Override public List<ContractRow> contractsByCustomer(String bin, LocalDate from, LocalDate to) {
        call("customer:" + bin);
        return List.copyOf(contracts);
    }

    @Override public List<AttemptLot> lotsByPlanPoints(List<Long> points) {
        call("chain:" + points);
        return points.stream().flatMap(p -> attempts.getOrDefault(p, List.of()).stream()).toList();
    }

    @Override public SearchPage<ClosedLot> closedLots(String w, LocalDate f, LocalDate t, Long a) {
        call("closed:" + w);
        return new SearchPage<>(closed.getOrDefault(w, List.of()), null);
    }

    @Override public List<ClosedLot> lotsByIds(List<Long> ids) {
        call("lots:" + ids);
        return ids.stream().map(lotsById::get).filter(Objects::nonNull).toList();
    }

    @Override public byte[] downloadProtocol(String url) {
        call("file:" + url);
        if (downloadError != null) throw downloadError;
        return files.get(url);
    }

    @Override public SubjectInfo subject(String bin) {
        call("subject:" + bin);
        if (subjectError != null) throw subjectError;
        return subjects.get(bin);
    }
}
```

- [ ] **Step 2: Тест справочника**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.GoszakupSubject;
import com.vladoose.nir.integration.goszakup.GoszakupCallException;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Справочник площадки (спека §8): 30 дней у себя, «не найден» запоминается, сбой — что есть, стоп-коды — наверх. */
@SpringBootTest
@Transactional
class GoszakupSubjectsTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    @Autowired GoszakupSubjectRepository repo;
    FakeGoszakupResultsClient fake;
    GoszakupSubjects subjects;

    @BeforeEach
    void setUp() {
        repo.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        subjects = new GoszakupSubjects(fake, repo, CLOCK, ms -> { });
    }

    private void cached(String bin, String name, String phone, OffsetDateTime at) {
        repo.saveAndFlush(GoszakupSubject.builder().bin(bin).nameRu(name).phone(phone).fetchedAt(at).build());
    }

    @Test
    void freshCache_noCall() {
        cached("110240019426", "ГКП «Больница»", "31398", NOW.minusDays(1));
        assertThat(subjects.get("110240019426").phone()).isEqualTo("31398");
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void stale_refetched() {
        cached("110240019426", "ГКП «Больница»", "31398", NOW.minusDays(40));
        fake.subjects.put("110240019426", new SubjectInfo("110240019426", "ГКП «Больница»", "8 (71133) 31398", "a@b.kz"));
        SubjectInfo s = subjects.get("110240019426");
        assertThat(s.phone()).isEqualTo("8 (71133) 31398");
        assertThat(repo.findById("110240019426").orElseThrow().getEmail()).isEqualTo("a@b.kz");
    }

    @Test
    void notFound_remembered() {
        assertThat(subjects.get("999999999999")).isNull();
        assertThat(repo.findById("999999999999").orElseThrow().isNotFound()).isTrue();
        assertThat(subjects.get("999999999999")).isNull();
        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void failure_returnsCached_stopCodeThrows() {
        cached("110240019426", "ГКП «Больница»", "31398", NOW.minusDays(40));
        fake.subjectError = new GoszakupCallException("goszakup API 500", 500, false, null);
        assertThat(subjects.get("110240019426").phone()).isEqualTo("31398");
        fake.subjectError = null;
        fake.forbidFromCall = 1;
        assertThatThrownBy(() -> subjects.get("110240019427")).isInstanceOf(GoszakupCallException.class);
    }

    @Test
    void names_limitAndInvalid() {
        cached("100000000001", "ТОО Один", null, NOW);
        fake.subjects.put("100000000002", new SubjectInfo("100000000002", "ТОО Два", null, null));
        fake.subjects.put("100000000003", new SubjectInfo("100000000003", "ТОО Три", null, null));
        Map<String, String> names = subjects.names(List.of("100000000001", "100000000002", "100000000003", "123"), 1);
        assertThat(names).containsEntry("100000000001", "ТОО Один").containsEntry("100000000002", "ТОО Два").hasSize(2);
        assertThat(fake.calls).containsExactly("subject:100000000002");
        assertThat(subjects.get("123")).isNull();
    }
}
```

- [ ] **Step 3: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.GoszakupSubjectsTest' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (нет `GoszakupSubjects`).

- [ ] **Step 4: Справочник**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.GoszakupSubject;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.service.productwatch.ProductWatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Справочник площадки по БИН (спека 2026-10-22-tender-results-market-prices §8): название, телефон, почта. У себя — 30 дней,
 * дальше — площадка; «не найден» тоже запоминается. Сбой площадки — что есть; 401 / 403 / 429 — наверх (общий токен).
 */
@Service
public class GoszakupSubjects {

    static final Duration FRESH = Duration.ofDays(30);
    static final long PAUSE_MS = 300;

    private final GoszakupResultsClient client;
    private final GoszakupSubjectRepository repo;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public GoszakupSubjects(GoszakupResultsClient client, GoszakupSubjectRepository repo) {
        this(client, repo, Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public GoszakupSubjects(GoszakupResultsClient client, GoszakupSubjectRepository repo, Clock clock, UpstreamRetry.Sleeper sleeper) {
        this.client = client;
        this.repo = repo;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public SubjectInfo get(String bin) {
        if (bin == null || !bin.matches("\\d{12}")) return null;
        OffsetDateTime now = OffsetDateTime.now(clock);
        GoszakupSubject s = repo.findById(bin).orElse(null);
        if (s != null && s.getFetchedAt().isAfter(now.minus(FRESH))) return info(s);
        if (!client.isConfigured()) return info(s);
        try {
            SubjectInfo fresh;
            try {
                fresh = UpstreamRetry.call(sleeper, () -> client.subject(bin));
            } finally {
                pause();   // токен общий с продом (спека §14)
            }
            GoszakupSubject row = s != null ? s : GoszakupSubject.builder().bin(bin).build();
            if (fresh != null) {
                row.setNameRu(cut(fresh.nameRu(), 500));
                row.setPhone(cut(fresh.phone(), 100));
                row.setEmail(cut(fresh.email(), 300));
            }
            row.setNotFound(fresh == null);
            row.setFetchedAt(now);
            return info(repo.save(row));
        } catch (RuntimeException e) {
            if (ProductWatchService.stopping(e)) throw e;
            return info(s);
        }
    }

    /** Названия: из справочника; без записи — с площадки, не больше fetchLimit за вызов (стоп-код — остальные без названий). */
    public Map<String, String> names(Collection<String> bins, int fetchLimit) {
        Set<String> want = bins.stream().filter(b -> b != null && b.matches("\\d{12}"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> out = new HashMap<>();
        Set<String> known = new HashSet<>();
        for (GoszakupSubject s : repo.findByBinIn(want)) {
            known.add(s.getBin());
            if (s.getNameRu() != null) out.put(s.getBin(), s.getNameRu());
        }
        int fetched = 0;
        for (String b : want) {
            if (known.contains(b) || fetched >= fetchLimit) continue;
            fetched++;
            try {
                SubjectInfo i = get(b);
                if (i != null && i.nameRu() != null) out.put(b, i.nameRu());
            } catch (RuntimeException e) {
                break;
            }
        }
        return out;
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static SubjectInfo info(GoszakupSubject s) {
        if (s == null || s.isNotFound() && s.getNameRu() == null) return null;
        return new SubjectInfo(s.getBin(), s.getNameRu(), s.getPhone(), s.getEmail());
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
```

- [ ] **Step 5: Запустить — зелёный; мутации**

Run: тот же — PASS (5 тестов).
Мутации: (1) `FRESH` → `Duration.ZERO` → `freshCache_noCall` красный; (2) не запоминать `notFound` (`row.setNotFound(false)`) → `notFound_remembered` красный; (3) в `catch` не пробрасывать стоп-код → `failure_returnsCached_stopCodeThrows` красный; (4) убрать `fetched >= fetchLimit` → `names_limitAndInvalid` красный.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/tenderresults/GoszakupSubjects.java \
  src/test/java/com/vladoose/nir/tenderresults/FakeGoszakupResultsClient.java src/test/java/com/vladoose/nir/service/tenderresults && \
  git commit -m "$(cat <<'EOF'
feat(results): справочник площадки — контакты заказчика и названия поставщиков с хранением на 30 дней

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Проход итогов — статусы, протоколы, договоры, события и темы

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/tenderresults/ResultTexts.java`, `ResultEvents.java`, `TenderResultsWriter.java`, `TenderResultsService.java`
- Modify: `src/main/java/com/vladoose/nir/repository/TenderLotResultRepository.java` (`findUnsettled`, `findOpen`), `src/main/java/com/vladoose/nir/service/tendernotify/NewTenderNotifier.java` (`parseRegions` — public, `regionMatches`)
- Modify (тесты): `src/test/java/com/vladoose/nir/tenderresults/ResultsFixtures.java` (`URL`, `buy`, `lot`, `markOurs`, `protocolPdf`)
- Test: `src/test/java/com/vladoose/nir/service/tenderresults/ResultTextsTest.java`, `TenderResultsServiceTest.java`

**Interfaces:**
- Consumes: задачи 1–4 (`TenderLotResult` и др., `GoszakupResultsClient`, `ProtocolParser.parse`, `GoszakupSubjects`, `FakeGoszakupResultsClient`), `ProductWatchService.stopping`, `ProductWatchService.importAnnouncement`, `CompanyProfileService.forMarket(Market.KZ).getBinInn()`, `ImportDeadline.parse/passed`, `TelegramText.safeCut`, `UpstreamRetry`.
- Produces:
  - `ResultTexts`: `money(BigDecimal)`, `range(BigDecimal, BigDecimal)`, `unit(String planUnit, String lotUnit)`, `method(String lotNumber)`, `methodName(String method)`, `bids(int)`, `done(TenderLotResult, String unit, BigDecimal budget)`, `noBids()`, `oneBid(String name, BigDecimal price, String unit)`, `failedOther(String failText, ProtocolStatus)`, `cancelled()`, `contract(String supplier, BigDecimal unitPrice, String unit, String number, LocalDate signedAt)`, `repeat(String anno, String method, LocalDateTime endAt)`, `oiDone(String supplier)`, `oiFailed(String supplier)`, `ourBid(boolean won, Integer place, Integer total, BigDecimal ourPrice, String winnerName, BigDecimal winnerPrice, boolean rejected, String rejectReason, String unit)`, `who(String name, String bin)`.
  - `ResultEvents`: `Set<Long> ours(Collection<TenderLot>)`, `static ResultTopic topic(ResultEventKind, boolean ours, boolean zko)`, `boolean zko(Tender)`, `boolean add(Tender, TenderLot, ResultEventKind, String refKey, LocalDateTime occurredAt, String text, Long refTenderId, boolean ours, boolean silentPass, LocalDateTime nowLocal, OffsetDateTime now)`, `static boolean stale(LocalDateTime occurredAt, LocalDateTime nowLocal)`; `STALE_DAYS = 3`.
  - `TenderResultsWriter`: `record Candidate(long tenderId, String anno, long buyId)`, `record ProtocolJob(long tenderId, String url, List<Long> resultIds)`, `firstRun()`, `candidates(LocalDateTime now, int limit)`, `candidate(long tenderId, LocalDateTime now)`, `applyBuy(Candidate, ResultBuy, OffsetDateTime)`, `protocolFailed(ProtocolJob, String note)`, `applyProtocol(ProtocolJob, ProtocolResult, String ourBin)`, `buysAwaitingContract(Long tenderId)`, `applyContracts(List<ContractRow>, LocalDateTime nowLocal, OffsetDateTime now, boolean silent)`, `settle(Long tenderId, LocalDateTime nowLocal, OffsetDateTime now, boolean silent)`, `closeStale(LocalDateTime nowLocal, OffsetDateTime now)`, `pendingCustomerBins(int limit)`, `static Long buyId(String anno)`, `String name(String bin)`; константы `TRACK_DAYS = 30`, `WINDOW_DAYS = 45`, `PROTOCOL_ATTEMPTS = 5`.
  - `TenderResultsService`: `interface Importer { long importAnnouncement(String numberAnno); }`, `run(ImportSummary)`, `check(long tenderId, ImportSummary)`, `checkable(long tenderId)`; конструктор для тестов `(GoszakupResultsClient, TenderResultsWriter, GoszakupSubjects, Supplier<String> ourBin, Importer, Clock, UpstreamRetry.Sleeper)`.
  - `NewTenderNotifier.parseRegions(String)` (public), `NewTenderNotifier.regionMatches(Set<String> regions, String region)`.

- [ ] **Step 1: Тест текстов**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.ProtocolStatus;
import com.vladoose.nir.entity.TenderLotResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Тексты итогов (спека 2026-10-22-tender-results-market-prices §7, §11). Пробелы в числах — неразрывные. */
class ResultTextsTest {

    private static final String S = "\u00A0";

    @Test
    void money() {
        assertThat(ResultTexts.money(new BigDecimal("251000"))).isEqualTo("251" + S + "000" + S + "₸");
        assertThat(ResultTexts.money(new BigDecimal("4500.5"))).isEqualTo("4" + S + "500,50" + S + "₸");
        assertThat(ResultTexts.money(new BigDecimal("3700000"))).isEqualTo("3,7" + S + "млн" + S + "₸");
        assertThat(ResultTexts.money(new BigDecimal("1340000"))).isEqualTo("1,34" + S + "млн" + S + "₸");
        assertThat(ResultTexts.money(null)).isEqualTo("—");
    }

    @Test
    void range() {
        assertThat(ResultTexts.range(new BigDecimal("251000"), new BigDecimal("613500"))).isEqualTo("251–613" + S + "тыс.");
        assertThat(ResultTexts.range(new BigDecimal("1200000"), new BigDecimal("3400000"))).isEqualTo("1,2–3,4" + S + "млн");
        assertThat(ResultTexts.range(new BigDecimal("900"), new BigDecimal("950"))).isEqualTo("900–950" + S + "₸");
        assertThat(ResultTexts.range(new BigDecimal("900"), new BigDecimal("900"))).isEqualTo("900" + S + "₸");
    }

    @Test
    void unitMethodBids() {
        assertThat(ResultTexts.unit("Штука", null)).isEqualTo("шт");
        assertThat(ResultTexts.unit("Упаковка", null)).isEqualTo("уп");
        assertThat(ResultTexts.unit(null, "компл")).isEqualTo("компл");
        assertThat(ResultTexts.unit(null, null)).isEqualTo("шт");
        assertThat(ResultTexts.method("88208881-ЗЦП2")).isEqualTo("ЗЦП");
        assertThat(ResultTexts.method("88208881-ОИ3")).isEqualTo("ОИ");
        assertThat(ResultTexts.method("12345")).isNull();
        assertThat(ResultTexts.methodName("ОК")).isEqualTo("конкурс");
        assertThat(ResultTexts.bids(1)).isEqualTo("1 заявка");
        assertThat(ResultTexts.bids(3)).isEqualTo("3 заявки");
        assertThat(ResultTexts.bids(10)).isEqualTo("10 заявок");
        assertThat(ResultTexts.bids(21)).isEqualTo("21 заявка");
        assertThat(ResultTexts.bids(12)).isEqualTo("12 заявок");
    }

    @Test
    void eventTexts() {
        TenderLotResult r = TenderLotResult.builder().winnerName("ИП Әбдібай").winnerBin("900000000001").winnerPrice(new BigDecimal("251000"))
                .bidsCount(10).minPrice(new BigDecimal("251000")).maxPrice(new BigDecimal("613500")).build();
        assertThat(ResultTexts.done(r, "шт", new BigDecimal("613900")))
                .isEqualTo("победил ИП Әбдібай — 251" + S + "000" + S + "₸ за шт · 10 заявок (251–613" + S + "тыс.) · бюджет 613" + S + "900" + S + "₸");
        assertThat(ResultTexts.done(new TenderLotResult(), "шт", null)).isEqualTo("состоялся");
        assertThat(ResultTexts.oneBid("ИП БОЛАТ", new BigDecimal("3700000"), "шт"))
                .isEqualTo("1 заявка → из одного источника у ИП БОЛАТ, 3,7" + S + "млн" + S + "₸ за шт");
        assertThat(ResultTexts.failedOther("Признать … по лоту № 1-ЗЦП1 несостоявшейся в связи с отклонением всех заявок",
                ProtocolStatus.PARSED)).isEqualTo("не состоялся (отклонением всех заявок) — объявят заново");
        assertThat(ResultTexts.failedOther(null, ProtocolStatus.UNKNOWN_FORMAT)).isEqualTo("не состоялся (протокол не разобран) — объявят заново");
        assertThat(ResultTexts.contract("ТОО «Поставщик»", new BigDecimal("43000"), "шт", "070740006294/260209/00", LocalDate.of(2026, 10, 20)))
                .isEqualTo("договор с ТОО «Поставщик» — 43" + S + "000" + S + "₸ за шт, № 070740006294/260209/00 от 20.10.2026");
        assertThat(ResultTexts.repeat("17790001-1", "запрос цен", LocalDateTime.of(2026, 10, 30, 10, 0)))
                .isEqualTo("объявлена повторная попытка № 17790001-1 (запрос цен), приём до 30.10 10:00");
        assertThat(ResultTexts.oiFailed("ИП Айя")).isEqualTo("из одного источника сорвалось (ИП Айя не подписал) — объявят заново");
        assertThat(ResultTexts.oiDone(null)).isEqualTo("из одного источника — состоялся");
    }

    @Test
    void ourBid() {
        assertThat(ResultTexts.ourBid(true, 1, 10, new BigDecimal("251000"), null, null, false, null, "шт"))
                .isEqualTo("🏆 Выиграли: наша цена 251" + S + "000" + S + "₸ за шт");
        assertThat(ResultTexts.ourBid(false, 2, 4, new BigDecimal("260000"), "ИП X", new BigDecimal("247000"), false, null, "шт"))
                .isEqualTo("📉 Наше место 2 из 4: наша цена 260" + S + "000" + S + "₸ за шт, победил ИП X — 247" + S + "000" + S + "₸ (−5 %)");
        assertThat(ResultTexts.ourBid(false, null, 4, null, null, null, true, "не соответствует ТЗ", "шт"))
                .isEqualTo("⛔ Наша заявка отклонена: не соответствует ТЗ");
        assertThat(ResultTexts.ourBid(false, 1, 1, new BigDecimal("100"), null, null, false, null, "шт"))
                .isEqualTo("📋 Наша заявка: место 1 из 1, наша цена 100" + S + "₸ за шт");
    }
}
```

- [ ] **Step 2: Помощники тестов прохода** — `ResultsFixtures.java`, добавить (импорты: `com.vladoose.nir.integration.goszakup.dto.*`, `org.springframework.jdbc.core.JdbcTemplate`, `org.apache.pdfbox.pdmodel.*`, `org.apache.pdfbox.pdmodel.common.PDRectangle`, `org.apache.pdfbox.pdmodel.font.PDType0Font`, `java.io.*`, `java.nio.charset.StandardCharsets`, `java.util.List`):

```java
    public static final String URL = "https://ows.goszakup.gov.kz/download/trd_buy/zz";

    /** Объявление площадки: итоги и протокол (url пусто — файла нет). */
    public static ResultBuy buy(long buyId, String resultsAt, String protocolUrl, ResultLot... lots) {
        return new ResultBuy(buyId, buyId + "-1", 350, resultsAt, null, 3, "Запрос ценовых предложений", List.of(lots),
                protocolUrl == null ? List.of() : List.of(new ProtocolFileRef("protocol_PI_" + buyId + ".pdf", protocolUrl)));
    }

    public static ResultLot lot(long lotId, String code, int status, long planPoint) {
        String name = status == 360 ? "Закупка состоялась" : status == 370 ? "Закупка не состоялась"
                : status == 430 ? "Закупка отменена" : "Опубликован (прием ценовых предложений)";
        return new ResultLot(lotId, code, status, name, new BigDecimal("3"), new BigDecimal("1841700"), planPoint,
                "309911.100.000001", "Штука", "110240019426");
    }

    /** Наш лот: модель в работе. */
    public static void markOurs(JdbcTemplate jdbc, TenderLot lot) {
        Long eq = jdbc.queryForObject("insert into med_equipment (name, manufact, market) values ('ZZ модель', 'ZZ', 'KZ') returning id",
                Long.class);
        jdbc.update("update tender_lot set proposed_equipment_id = ? where id = ?", eq, lot.getId());
    }

    /** Текстовая фикстура протокола (как её отдаёт PDFBox) → PDF по 40 строк на широкой странице: проход качает и разбирает его как файл площадки. */
    public static byte[] protocolPdf(String resource) throws Exception {
        String text;
        try (InputStream in = ResultsFixtures.class.getResourceAsStream("/goszakup/protocols/" + resource)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> lines = text.lines().map(l -> l.replace('\t', ' ').strip()).filter(l -> !l.isEmpty()).toList();
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             InputStream font = ResultsFixtures.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            PDType0Font f = PDType0Font.load(d, font);
            for (int i = 0; i < lines.size(); i += 40) {
                PDPage page = new PDPage(new PDRectangle(2400, 900));
                d.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.beginText();
                    cs.setFont(f, 9);
                    cs.newLineAtOffset(20, 860);
                    for (String l : lines.subList(i, Math.min(lines.size(), i + 40))) {
                        cs.showText(l);
                        cs.newLineAtOffset(0, -18);
                    }
                    cs.endText();
                }
            }
            d.save(out);
            return out.toByteArray();
        }
    }
```

- [ ] **Step 3: Тест прохода**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.GoszakupCallException;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Проход итогов (спека 2026-10-22-tender-results-market-prices §4, §5, §7): фейк площадки, PDF протокола, фиксированные часы. */
@SpringBootTest
@Transactional
class TenderResultsServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);   // 10:00 по Уральску = NOW

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotResultRepository results;
    @Autowired TenderLotBidRepository bids;
    @Autowired TenderResultEventRepository events;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired TenderResultsWriter writer;
    @Autowired JdbcTemplate jdbc;

    FakeGoszakupResultsClient fake;
    TenderResultsService service;
    String our = "999999999999";
    final List<String> imported = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        // nirdb постоянная: «первый проход» решает наличие строк итогов — чистим в транзакции теста (откат после)
        events.deleteAllInBatch();
        results.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        GoszakupSubjects subjects = new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { });
        service = new TenderResultsService(fake, writer, subjects, () -> our, anno -> {
            imported.add(anno);
            return -1L;
        }, CLOCK, ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Строка итогов уже есть — проход не первый, события не молчат. */
    private void notFirst() {
        Tender t = ResultsFixtures.tender(tenders, 970009999L, NOW.minusDays(40), "0-ЗЦП1", "Прошлый");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId())
                .outcome(LotOutcome.DONE).finalAt(OffsetDateTime.now(CLOCK)).eventAt(OffsetDateTime.now(CLOCK)).build());
    }

    private Tender zcp(long buyId, String code, int status, String protocol, String region) throws Exception {
        Tender t = ResultsFixtures.tenderIn(tenders, buyId, NOW.minusDays(8), region, code, "Тележка медицинская");
        String url = URL + buyId;
        fake.buys.put(buyId, buy(buyId, "2026-10-21 12:54:49", url, lot(43500000L + buyId % 1000, code, status, 88000000L + buyId % 1000)));
        if (protocol != null) fake.files.put(url, protocol.endsWith(".txt") ? protocolPdf(protocol) : protocol.getBytes(StandardCharsets.UTF_8));
        return t;
    }

    private ImportSummary check(Tender t) {
        ImportSummary sum = new ImportSummary();
        service.check(t.getId(), sum);
        return sum;
    }

    private TenderLotResult result(Tender t) {
        return results.findByTenderLotId(t.getLots().get(0).getId()).orElseThrow();
    }

    @Test
    void done_bidsWinnerRange_digestForOurLot() throws Exception {
        notFirst();
        Tender t = zcp(970001001L, "88214663-ЗЦП2", 360, "zcp-winner.txt", "Западно-Казахстанская область");
        markOurs(jdbc, t.getLots().get(0));
        ImportSummary sum = check(t);
        assertThat(sum.getErrors()).isZero();
        TenderLotResult r = result(t);
        assertThat(r.getOutcome()).isEqualTo(LotOutcome.DONE);
        assertThat(r.getResultsAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 12, 54, 49));
        assertThat(r.getProtocolStatus()).isEqualTo(ProtocolStatus.PARSED);
        assertThat(r.getBidsCount()).isEqualTo(10);
        assertThat(r.getWinnerName()).isEqualTo("Поставщик 1");
        assertThat(r.getWinnerPrice()).isEqualByComparingTo("251000");
        assertThat(r.getSecondBin()).isEqualTo("900000000002");
        assertThat(r.getMinPrice()).isEqualByComparingTo("251000");
        assertThat(r.getMaxPrice()).isEqualByComparingTo("613500");
        assertThat(bids.findByResultIdOrderByPlaceAscIdAsc(r.getId())).hasSize(10);
        TenderLot lot = lots.findById(t.getLots().get(0).getId()).orElseThrow();
        assertThat(lot.getExtLotId()).isNotNull();
        assertThat(lot.getEnstruCode()).isEqualTo("309911.100.000001");
        assertThat(lot.getPlanUnit()).isEqualTo("Штука");
        List<TenderResultEvent> ev = events.findByTenderIdOrderByIdAsc(t.getId());
        assertThat(ev).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.DONE);
        assertThat(ev.get(0).getTopic()).isEqualTo(ResultTopic.PRODUCTS);
        assertThat(ev.get(0).isUrgent()).isFalse();
        assertThat(ev.get(0).getDigestedAt()).as("ждёт сводки").isNull();
        assertThat(ev.get(0).getText()).startsWith("победил Поставщик 1 — 251\u00A0000\u00A0₸ за шт · 10 заявок (251–613\u00A0тыс.)");
    }

    /** «Звонить» по чужому лоту ЗКО — сразу в тему «Тендеры» (спека §7, вариант B). */
    @Test
    void noBids_zkoNotOurs_urgentToTenders() throws Exception {
        notFirst();
        Tender t = zcp(970001002L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getFailReason()).isEqualTo(FailReason.NO_BIDS);
        TenderResultEvent e = events.findByTenderIdOrderByIdAsc(t.getId()).get(0);
        assertThat(e.getKind()).isEqualTo(ResultEventKind.NO_BIDS);
        assertThat(e.isUrgent()).isTrue();
        assertThat(e.getTopic()).isEqualTo(ResultTopic.TENDERS);
        assertThat(e.getNotifiedAt()).as("ждёт отправки").isNull();
        assertThat(e.getText()).isEqualTo("никто не подал — объявят заново");
    }

    /** Чужой лот не из ЗКО — только «История». */
    @Test
    void noBids_outsideZko_historyOnly() throws Exception {
        notFirst();
        Tender t = zcp(970001003L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "г. Астана");
        check(t);
        TenderResultEvent e = events.findByTenderIdOrderByIdAsc(t.getId()).get(0);
        assertThat(e.getTopic()).isEqualTo(ResultTopic.NONE);
        assertThat(e.getNotifiedAt()).isNotNull();
        assertThat(e.getDigestedAt()).isNotNull();
    }

    /** Наша заявка в протоколе — сразу, тема нашей продукции, по любому лоту (спека §7). */
    @Test
    void ourBid_place2_urgentProducts() throws Exception {
        notFirst();
        our = "900000000002";
        Tender t = zcp(970001004L, "88214663-ЗЦП2", 360, "zcp-winner.txt", "г. Астана");
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getOurPlace()).isEqualTo(2);
        assertThat(r.getOurPrice()).isNotNull();
        assertThat(r.getOurRejected()).isFalse();
        List<TenderResultEvent> ev = events.findByTenderIdOrderByIdAsc(t.getId());
        assertThat(ev).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.DONE, ResultEventKind.OUR_BID);
        TenderResultEvent bid = ev.get(1);
        assertThat(bid.getTopic()).isEqualTo(ResultTopic.PRODUCTS);
        assertThat(bid.isUrgent()).isTrue();
        assertThat(bid.getNotifiedAt()).isNull();
        assertThat(bid.getText()).startsWith("📉 Наше место 2 из 10: наша цена").contains("победил Поставщик 1");
        assertThat(ev.get(0).getTopic()).as("итог чужого лота не из ЗКО — без сообщения").isEqualTo(ResultTopic.NONE);
    }

    /** Первый проход — молча (спека §7). */
    @Test
    void firstRun_silent() throws Exception {
        Tender t = zcp(970001005L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        ImportSummary sum = check(t);
        TenderResultEvent e = events.findByTenderIdOrderByIdAsc(t.getId()).get(0);
        assertThat(e.getNotifiedAt()).isNotNull();
        assertThat(sum.getMessage()).contains("первый проход");
    }

    /** 403 посреди прохода — стоп, ничего не окончательно и не потеряно; следующий проход доделывает (Review Focus 2). */
    @Test
    void forbiddenMidPass_stops_nothingLost() throws Exception {
        notFirst();
        Tender t = zcp(970001006L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        fake.forbidFromCall = 2;                       // 1 — объявления, 2 — файл протокола
        ImportSummary sum = check(t);
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).contains("403");
        TenderLotResult r = result(t);
        assertThat(r.getOutcome()).isEqualTo(LotOutcome.FAILED);
        assertThat(r.getProtocolAttempts()).as("стоп-код попыткой не считается").isZero();
        assertThat(r.getFinalAt()).isNull();
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).isEmpty();
        fake.forbidFromCall = 0;
        check(t);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.NO_BIDS);
    }

    /** Повторные проверки — одно событие, протокол качается один раз (Review Focus 3). */
    @Test
    void repeatedChecks_oneEvent_oneDownload() throws Exception {
        notFirst();
        Tender t = zcp(970001007L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        check(t);
        check(t);
        check(t);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).hasSize(1);
        assertThat(fake.calls.stream().filter(c -> c.startsWith("file:")).count()).isEqualTo(1);
    }

    /** Незнакомый протокол — пометка и ссылка, а «звонить» всё равно приходит (Review Focus 1). */
    @Test
    void unknownProtocol_stillCallSignal() throws Exception {
        notFirst();
        Tender t = zcp(970001008L, "1-ЭА1", 370, "<html><body><p>Протокол об итогах электронного аукциона № 1-ЭА1</p></body></html>",
                "Западно-Казахстанская область");
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getProtocolStatus()).isEqualTo(ProtocolStatus.UNKNOWN_FORMAT);
        assertThat(r.getProtocolNote()).contains("не знаком");
        assertThat(r.getProtocolUrl()).startsWith(URL);
        TenderResultEvent e = events.findByTenderIdOrderByIdAsc(t.getId()).get(0);
        assertThat(e.getKind()).isEqualTo(ResultEventKind.FAILED_OTHER);
        assertThat(e.isUrgent()).isTrue();
        assertThat(e.getText()).isEqualTo("не состоялся (протокол не разобран) — объявят заново");
    }

    /** Файл не качается — до 5 попыток, потом событие без протокола. */
    @Test
    void downloadFailures_fiveAttempts_thenSettles() throws Exception {
        notFirst();
        Tender t = zcp(970001009L, "88208881-ЗЦП1", 370, "zcp-no-bids.txt", "Западно-Казахстанская область");
        fake.downloadError = new GoszakupCallException("goszakup: протокол — HTTP 500", 500, true, null);
        for (int i = 0; i < 4; i++) check(t);
        assertThat(result(t).getProtocolAttempts()).isEqualTo(4);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).isEmpty();
        ImportSummary sum = check(t);
        assertThat(sum.getErrors()).as("протокол — не ошибка прохода (решение 6)").isZero();
        assertThat(result(t).getProtocolStatus()).isEqualTo(ProtocolStatus.FAILED);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind)
                .containsExactly(ResultEventKind.FAILED_OTHER);
    }

    /** Договор: последний подписанный, «не заключён» — мимо; итог окончательный, событие с названием поставщика. */
    @Test
    void contract_latestConcluded_final() throws Exception {
        notFirst();
        Tender t = zcp(970001010L, "88214663-ЗЦП2", 360, "zcp-winner.txt", "Западно-Казахстанская область");
        long extLot = 43500000L + 10;
        fake.contracts.add(new ContractRow(1L, 970001010L, "070740006294/260209/00", "2026-10-20 09:28:34", 190, "900000000001",
                new BigDecimal("753000"), List.of(new ContractUnitRow(extLot, new BigDecimal("251000"), new BigDecimal("3"), new BigDecimal("753000")))));
        fake.contracts.add(new ContractRow(2L, 970001010L, "НЕ-ТОТ", "2026-10-21 09:00:00", 330, "900000000002",
                new BigDecimal("1"), List.of(new ContractUnitRow(extLot, new BigDecimal("1"), new BigDecimal("3"), new BigDecimal("3")))));
        fake.subjects.put("900000000001", new SubjectInfo("900000000001", "ТОО «Поставщик»", null, null));
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getContractNumber()).isEqualTo("070740006294/260209/00");
        assertThat(r.getContractSignedAt()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(r.getContractUnitPrice()).isEqualByComparingTo("251000");
        assertThat(r.getFinalAt()).isNotNull();
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind)
                .containsExactly(ResultEventKind.CONTRACT, ResultEventKind.DONE);
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId()).get(0).getText()).startsWith("договор с ТОО «Поставщик»");
    }

    @Test
    void cancelled_finalWithoutProtocol() throws Exception {
        notFirst();
        Tender t = zcp(970001011L, "1-ЗЦП1", 430, null, "Западно-Казахстанская область");
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getOutcome()).isEqualTo(LotOutcome.CANCELLED);
        assertThat(r.getFinalAt()).isNotNull();
        assertThat(fake.calls).noneMatch(c -> c.startsWith("file:"));
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.CANCELLED);
    }

    @Test
    void pending_noEventNoDownload() throws Exception {
        notFirst();
        Tender t = zcp(970001012L, "1-ЗЦП1", 220, "zcp-no-bids.txt", "Западно-Казахстанская область");
        check(t);
        assertThat(result(t).getOutcome()).isEqualTo(LotOutcome.PENDING);
        assertThat(fake.calls).noneMatch(c -> c.startsWith("file:"));
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).isEmpty();
    }

    /** ОИ, у лота нет количества — цены за единицу нет, сумма есть (Review Focus 4). */
    @Test
    void oiDone_noQuantity_unitPriceEmpty() throws Exception {
        notFirst();
        Tender t = zcp(970001013L, "88138321-ОИ2", 360, null, "Западно-Казахстанская область");
        t.getLots().get(0).setQuantity(null);
        tenders.saveAndFlush(t);
        fake.files.put(URL + 970001013L, getClass().getResourceAsStream("/goszakup/protocols/oi-done.html").readAllBytes());
        check(t);
        TenderLotResult r = result(t);
        assertThat(r.getWinnerBin()).isEqualTo("900000000001");
        assertThat(r.getWinnerPrice()).isNull();
        assertThat(bids.findByResultIdOrderByPlaceAscIdAsc(r.getId()).get(0).getTotal()).isEqualByComparingTo("43000");
        assertThat(events.findByTenderIdOrderByIdAsc(t.getId())).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.OI_DONE);
    }

    /** Проход по расписанию: свежий срок — в работе; итог, ждущий дольше 30 дней от срока, — закрыт. */
    @Test
    void run_processesFreshAndClosesStale() throws Exception {
        notFirst();
        Tender fresh = ResultsFixtures.tender(tenders, 970001014L, NOW.minusMinutes(1), "88208881-ЗЦП1", "Тележка");
        fake.buys.put(970001014L, buy(970001014L, "2026-10-22 09:59:00", URL + 970001014L, lot(43501014L, "88208881-ЗЦП1", 370, 1L)));
        fake.files.put(URL + 970001014L, protocolPdf("zcp-no-bids.txt"));
        Tender old = ResultsFixtures.tender(tenders, 970001015L, NOW.minusDays(40), "1-ЗЦП1", "Давно ждём");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(old.getLots().get(0).getId()).tenderId(old.getId()).build());
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(events.findByTenderIdOrderByIdAsc(fresh.getId())).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.NO_BIDS);
        assertThat(results.findByTenderLotId(old.getLots().get(0).getId()).orElseThrow().getFinalAt()).isNotNull();
        assertThat(sum.getMessage()).startsWith("Итоги: тендеров проверено");
    }
}
```

- [ ] **Step 4: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.*' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (нет `ResultTexts`, `TenderResultsService`, `ResultsFixtures.buy`).

- [ ] **Step 5: Тексты**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.ProtocolStatus;
import com.vladoose.nir.entity.TenderLotResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Тексты итогов (спека 2026-10-22-tender-results-market-prices §7, §11): событие («История», Telegram, сводка) — без названия
 * лота и заказчика, их добавляет место показа. Числа — с неразрывными пробелами. Чистые функции.
 */
public final class ResultTexts {

    static final char NBSP = '\u00A0';
    private static final BigDecimal THOUSAND = new BigDecimal(1000);
    private static final BigDecimal MILLION = new BigDecimal(1_000_000);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private ResultTexts() {}

    /** «251 000 ₸», «4 500,50 ₸», «3,7 млн ₸»; пусто — «—». */
    public static String money(BigDecimal v) {
        if (v == null) return "—";
        if (v.abs().compareTo(MILLION) >= 0) return num(v.divide(MILLION, 2, RoundingMode.HALF_UP)) + NBSP + "млн" + NBSP + "₸";
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
        BigDecimal abs = r.abs();
        String whole = group(abs.setScale(0, RoundingMode.DOWN).toBigInteger().toString());
        int cents = abs.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        return (r.signum() < 0 ? "−" : "") + whole + (cents == 0 ? "" : "," + (cents < 10 ? "0" : "") + cents) + NBSP + "₸";
    }

    /** «251–613 тыс.», «1,2–3,4 млн», «900–950 ₸» (вниз до целого: «от … до …» не завышаем); равные — одно число. */
    public static String range(BigDecimal min, BigDecimal max) {
        if (min == null || max == null) return money(min == null ? max : min);
        if (min.compareTo(max) == 0) return money(min);
        if (max.compareTo(MILLION) >= 0) {
            return num(min.divide(MILLION, 2, RoundingMode.DOWN)) + "–" + num(max.divide(MILLION, 2, RoundingMode.DOWN)) + NBSP + "млн";
        }
        if (min.compareTo(THOUSAND) >= 0) {
            return min.divide(THOUSAND, 0, RoundingMode.DOWN).toPlainString() + "–"
                    + max.divide(THOUSAND, 0, RoundingMode.DOWN).toPlainString() + NBSP + "тыс.";
        }
        return num(min.setScale(2, RoundingMode.HALF_UP)) + "–" + num(max.setScale(2, RoundingMode.HALF_UP)) + NBSP + "₸";
    }

    /** Единица: своя у лота (частная заявка), иначе пункта плана; «Штука» → «шт». Пусто — «шт». */
    public static String unit(String planUnit, String lotUnit) {
        String u = lotUnit != null && !lotUnit.isBlank() ? lotUnit : planUnit;
        if (u == null || u.isBlank()) return "шт";
        String t = u.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "штука", "шт", "шт." -> "шт";
            case "упаковка", "уп", "уп.", "упак", "упак." -> "уп";
            case "комплект", "компл", "компл." -> "компл";
            case "килограмм", "кг" -> "кг";
            case "литр", "л" -> "л";
            case "метр", "м" -> "м";
            case "флакон", "фл", "фл." -> "фл";
            default -> t.length() <= 12 ? t : t.substring(0, 12);
        };
    }

    /** Способ по номеру лота: «88208881-ЗЦП2» → «ЗЦП», «…-ОИ3» → «ОИ», «…-ОК1» → «ОК». */
    public static String method(String lotNumber) {
        if (lotNumber == null || !lotNumber.contains("-")) return null;
        String tail = lotNumber.substring(lotNumber.lastIndexOf('-') + 1).replaceAll("\\d", "").trim();
        return tail.isEmpty() ? null : tail;
    }

    public static String methodName(String method) {
        if (method == null) return "закупка";
        return switch (method) {
            case "ЗЦП" -> "запрос цен";
            case "ОИ" -> "из одного источника";
            case "ОК" -> "конкурс";
            case "ЭА", "А" -> "аукцион";
            default -> method;
        };
    }

    /** «1 заявка», «3 заявки», «10 заявок». */
    public static String bids(int n) {
        int d = n % 10, h = n % 100;
        String w = d == 1 && h != 11 ? "заявка" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "заявки" : "заявок";
        return n + " " + w;
    }

    /** «победил ИП X — 251 000 ₸ за шт · 10 заявок (251–613 тыс.) · бюджет 613 900 ₸»; протокол не разобран — «состоялся». */
    public static String done(TenderLotResult r, String unit, BigDecimal budget) {
        StringBuilder b = new StringBuilder();
        if (r.getWinnerName() != null || r.getWinnerBin() != null) {
            b.append("победил ").append(who(r.getWinnerName(), r.getWinnerBin()));
            if (r.getWinnerPrice() != null) b.append(" — ").append(money(r.getWinnerPrice())).append(" за ").append(unit);
        } else {
            b.append("состоялся");
        }
        if (r.getBidsCount() != null && r.getBidsCount() > 0) {
            b.append(" · ").append(bids(r.getBidsCount()));
            if (r.getMinPrice() != null && r.getMaxPrice() != null && r.getMinPrice().compareTo(r.getMaxPrice()) != 0) {
                b.append(" (").append(range(r.getMinPrice(), r.getMaxPrice())).append(')');
            }
        }
        if (budget != null) b.append(" · бюджет ").append(money(budget));
        return b.toString();
    }

    public static String noBids() {
        return "никто не подал — объявят заново";
    }

    public static String oneBid(String name, BigDecimal price, String unit) {
        return "1 заявка → из одного источника у " + (name == null ? "единственного участника" : name)
                + (price == null ? "" : ", " + money(price) + " за " + unit);
    }

    /** «не состоялся (<причина из вывода протокола>) — объявят заново». */
    public static String failedOther(String failText, ProtocolStatus status) {
        String why = null;
        if (failText != null) {
            int i = failText.indexOf("в связи с");
            why = i < 0 ? null : failText.substring(i + "в связи с".length()).trim().replaceAll("[.;]+$", "");
            if (why != null && why.length() > 150) why = why.substring(0, 150).trim() + "…";
        }
        if (why == null || why.isEmpty()) why = status == ProtocolStatus.PARSED ? "причина — в протоколе" : "протокол не разобран";
        return "не состоялся (" + why + ") — объявят заново";
    }

    public static String cancelled() {
        return "отменён";
    }

    public static String contract(String supplier, BigDecimal unitPrice, String unit, String number, LocalDate signedAt) {
        StringBuilder b = new StringBuilder("договор с ").append(supplier == null ? "поставщиком" : supplier);
        if (unitPrice != null) b.append(" — ").append(money(unitPrice)).append(" за ").append(unit);
        if (number != null && !number.isBlank()) b.append(", № ").append(number.trim());
        if (signedAt != null) b.append(" от ").append(signedAt.format(DATE));
        return b.toString();
    }

    public static String repeat(String anno, String method, LocalDateTime endAt) {
        return "объявлена повторная попытка № " + anno + " (" + method + ")" + (endAt == null ? "" : ", приём до " + endAt.format(DAY_TIME));
    }

    public static String oiDone(String supplier) {
        return "из одного источника" + (supplier == null ? "" : " у " + supplier) + " — состоялся";
    }

    public static String oiFailed(String supplier) {
        return "из одного источника сорвалось (" + (supplier == null ? "поставщик" : supplier) + " не подписал) — объявят заново";
    }

    /** Наша заявка в протоколе: выиграли / место N из M с разницей к победителю / отклонена. */
    public static String ourBid(boolean won, Integer place, Integer total, BigDecimal ourPrice, String winnerName, BigDecimal winnerPrice,
                                boolean rejected, String rejectReason, String unit) {
        if (rejected) return "⛔ Наша заявка отклонена" + (rejectReason == null || rejectReason.isBlank() ? "" : ": " + rejectReason.trim());
        String price = ourPrice == null ? "" : "наша цена " + money(ourPrice) + " за " + unit;
        if (won) return "🏆 Выиграли" + (price.isEmpty() ? "" : ": " + price);
        String where = "место " + (place == null ? "?" : place) + " из " + (total == null ? "?" : total);
        if (winnerName == null && winnerPrice == null) return "📋 Наша заявка: " + where + (price.isEmpty() ? "" : ", " + price);
        StringBuilder b = new StringBuilder("📉 Наше ").append(where).append(':');
        if (!price.isEmpty()) b.append(' ').append(price).append(',');
        b.append(" победил ").append(winnerName == null ? "другой участник" : winnerName);
        if (winnerPrice != null) {
            b.append(" — ").append(money(winnerPrice));
            if (ourPrice != null && ourPrice.signum() > 0) {
                long pct = winnerPrice.subtract(ourPrice).multiply(BigDecimal.valueOf(100)).divide(ourPrice, 0, RoundingMode.HALF_UP).longValue();
                b.append(" (").append(pct < 0 ? "−" + (-pct) : "+" + pct).append(NBSP).append("%)");
            }
        }
        return b.toString();
    }

    /** Название, иначе «БИН …». */
    public static String who(String name, String bin) {
        if (name != null && !name.isBlank()) return name.trim();
        return bin == null ? "участник" : "БИН " + bin;
    }

    private static String num(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    static String group(String digits) {
        StringBuilder b = new StringBuilder();
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) b.append(NBSP);
            b.append(digits.charAt(i));
        }
        return b.toString();
    }
}
```

- [ ] **Step 6: Регион ЗКО — общий с «Новым тендером»** — `NewTenderNotifier.java`: `static Set<String> parseRegions(String csv)` → `public static Set<String> parseRegions(String csv)`; добавить рядом и использовать в `qualifies`:

```java
    /** Регион из списка уведомлений; пустой список («*») — любой. */
    public static boolean regionMatches(Set<String> regions, String region) {
        return regions.isEmpty() || regions.contains(norm(region));
    }
```

и в `qualifies`: `return regions.isEmpty() || regions.contains(norm(t.getRegion()));` → `return regionMatches(regions, t.getRegion());`.

- [ ] **Step 7: События и темы**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderResultEventRepository;
import com.vladoose.nir.service.tendernotify.NewTenderNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * События итогов (спека 2026-10-22-tender-results-market-prices §7, §12): один раз на вид у лота (у событий цепочки — на попытку),
 * тема по правилу «наш лот → тема нашей продукции, чужой ЗКО и «звонить» → тема «Тендеры», прочее — только «История»». Молчаливое
 * событие (первый проход, итог старше 3 дней) сразу отмечено отправленным. Вызывать внутри транзакции записи.
 */
@Component
public class ResultEvents {

    /** Итог старше — без сообщения: включили после перерыва — старьё не сыплется. */
    static final int STALE_DAYS = 3;

    private final TenderResultEventRepository events;
    private final TenderLotRepository lots;
    private final Set<String> regions;

    @Autowired
    public ResultEvents(TenderResultEventRepository events, TenderLotRepository lots, @Value("${tenders.notify.regions:}") String regions) {
        this.events = events;
        this.lots = lots;
        this.regions = NewTenderNotifier.parseRegions(regions);
    }

    /** Наши лоты (спека §7) — и лоты той же закупки по пункту плана (решение 4: повтор нашего лота — тоже наш). */
    public Set<Long> ours(Collection<TenderLot> list) {
        if (list.isEmpty()) return Set.of();
        Set<Long> ids = new HashSet<>();
        list.forEach(l -> ids.add(l.getId()));
        Set<Long> out = new HashSet<>(lots.findOurLotIds(ids));
        Set<Long> points = new HashSet<>();
        for (TenderLot l : list) if (!out.contains(l.getId()) && l.getPlanPointId() != null) points.add(l.getPlanPointId());
        if (points.isEmpty()) return out;
        List<TenderLot> same = lots.findByPlanPointIdIn(points);
        Set<Long> ourSame = new HashSet<>(lots.findOurLotIds(same.stream().map(TenderLot::getId).toList()));
        Set<Long> ourPoints = new HashSet<>();
        for (TenderLot s : same) if (ourSame.contains(s.getId())) ourPoints.add(s.getPlanPointId());
        for (TenderLot l : list) if (l.getPlanPointId() != null && ourPoints.contains(l.getPlanPointId())) out.add(l.getId());
        return out;
    }

    /** Наша заявка и наш лот — тема нашей продукции; чужой ЗКО — только «звонить», в тему «Тендеры»; прочее — никуда. */
    public static ResultTopic topic(ResultEventKind kind, boolean ours, boolean zko) {
        if (kind == ResultEventKind.OUR_BID || ours) return ResultTopic.PRODUCTS;
        return zko && kind.urgent() ? ResultTopic.TENDERS : ResultTopic.NONE;
    }

    public boolean zko(Tender t) {
        return NewTenderNotifier.regionMatches(regions, t.getRegion());
    }

    public static boolean stale(LocalDateTime occurredAt, LocalDateTime nowLocal) {
        return occurredAt != null && occurredAt.isBefore(nowLocal.minusDays(STALE_DAYS));
    }

    /** false — такое событие уже есть. */
    public boolean add(Tender t, TenderLot lot, ResultEventKind kind, String refKey, LocalDateTime occurredAt, String text, Long refTenderId,
                       boolean ours, boolean silentPass, LocalDateTime nowLocal, OffsetDateTime now) {
        boolean exists = refKey == null ? events.existsByTenderLotIdAndKind(lot.getId(), kind)
                : events.existsByTenderLotIdAndKindAndRefKey(lot.getId(), kind, refKey);
        if (exists) return false;
        ResultTopic topic = topic(kind, ours, zko(t));
        boolean send = topic != ResultTopic.NONE && !silentPass && !stale(occurredAt, nowLocal);
        events.save(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(lot.getId()).kind(kind).refKey(refKey)
                .occurredAt(occurredAt).text(TelegramText.safeCut(text, 500)).urgent(kind.urgent()).topic(topic).refTenderId(refTenderId)
                .createdAt(now)
                .notifiedAt(send && kind.urgent() ? null : now)
                .digestedAt(send && !kind.urgent() && topic == ResultTopic.PRODUCTS ? null : now)
                .build());
        return true;
    }
}
```

`TenderLotResultRepository.java` — добавить:

```java
    /** Итог известен, событие ещё не заведено. */
    @Query("select r from TenderLotResult r where r.eventAt is null and r.outcome <> com.vladoose.nir.entity.LotOutcome.PENDING order by r.id")
    List<TenderLotResult> findUnsettled();

    /** Не окончательные — для закрытия по сроку. */
    @Query("select r from TenderLotResult r where r.finalAt is null order by r.id")
    List<TenderLotResult> findOpen();
```

- [ ] **Step 8: Запись итогов**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.tenderresults.protocol.LotProtocol;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolBid;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolFormat;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Запись итогов (спека 2026-10-22-tender-results-market-prices §4–§7): каждая операция — своя транзакция, сеть и разбор файла —
 * у вызывающего (§6 CLAUDE.md). Лот площадки ↔ лот АИС — по номеру лота (`source_lot_code`).
 */
@Component
public class TenderResultsWriter {

    /** Сколько дней от срока (не состоялся — от итогов) лот проверяется. */
    static final int TRACK_DAYS = 30;
    /** Окно кандидатов прохода: 30 дней с запасом на итоги, опубликованные позже срока. */
    static final int WINDOW_DAYS = 45;
    static final int PROTOCOL_ATTEMPTS = 5;

    public record Candidate(long tenderId, String anno, long buyId) {}

    public record ProtocolJob(long tenderId, String url, List<Long> resultIds) {}

    private record Pick(ContractRow row, ContractUnitRow unit) {}

    private final TenderRepository tenders;
    private final TenderLotRepository lots;
    private final TenderLotResultRepository results;
    private final TenderLotBidRepository bids;
    private final TenderResultEventRepository eventRepo;
    private final GoszakupSubjectRepository subjects;
    private final ResultEvents events;

    public TenderResultsWriter(TenderRepository tenders, TenderLotRepository lots, TenderLotResultRepository results,
                               TenderLotBidRepository bids, TenderResultEventRepository eventRepo, GoszakupSubjectRepository subjects,
                               ResultEvents events) {
        this.tenders = tenders;
        this.lots = lots;
        this.results = results;
        this.bids = bids;
        this.eventRepo = eventRepo;
        this.subjects = subjects;
        this.events = events;
    }

    /** Итогов в базе нет — первый проход молчит (спека §7). */
    @Transactional(readOnly = true)
    public boolean firstRun() {
        return results.count() == 0;
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(LocalDateTime now, int limit) {
        List<Long> ids = tenders.findResultCandidates(now, now.minusDays(WINDOW_DAYS), limit);
        List<Candidate> out = new ArrayList<>();
        for (Tender t : tenders.findAllById(ids)) {
            Long buy = buyId(t.getSourceExtId());
            if (buy != null) out.add(new Candidate(t.getId(), t.getSourceExtId(), buy));
        }
        return out;
    }

    /** «Проверить итоги» одного тендера: goszakup KZ после срока, иначе 400. */
    @Transactional(readOnly = true)
    public Candidate candidate(long tenderId, LocalDateTime now) {
        Tender t = tenders.findById(tenderId).orElseThrow(() -> new NotFoundException("Тендер не найден"));
        Long buy = buyId(t.getSourceExtId());
        if (t.getMarket() != Market.KZ || t.getPlatform() != TenderPlatform.GOSZAKUP || t.getSource() != Source.PUBLIC_TENDER || buy == null) {
            throw new BadRequestException("Итоги — только для тендеров goszakup");
        }
        if (!ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), now)) {
            throw new BadRequestException("Приём заявок ещё идёт — итогов пока нет");
        }
        return new Candidate(t.getId(), t.getSourceExtId(), buy);
    }

    /** «17758520-1» → 17758520. */
    public static Long buyId(String anno) {
        if (anno == null) return null;
        String head = anno.contains("-") ? anno.substring(0, anno.indexOf('-')) : anno;
        return head.trim().matches("\\d{1,12}") ? Long.parseLong(head.trim()) : null;
    }

    /** Статусы лотов объявления → итоги; лотам, которым нужен протокол, — задание на файл. */
    @Transactional
    public List<ProtocolJob> applyBuy(Candidate c, ResultBuy buy, OffsetDateTime now) {
        Tender t = tenders.findById(c.tenderId()).orElse(null);
        if (t == null || buy == null) return List.of();
        Map<String, ResultLot> byCode = new HashMap<>();
        for (ResultLot l : buy.lots()) if (l.lotNumber() != null) byCode.put(l.lotNumber().trim(), l);
        ProtocolFileRef file = buy.protocols().isEmpty() ? null : buy.protocols().get(buy.protocols().size() - 1);
        List<Long> need = new ArrayList<>();
        for (TenderLot lot : t.getLots()) {
            if (lot.getSourceRemovedAt() != null || lot.getSourceLotCode() == null) continue;
            ResultLot rl = byCode.get(lot.getSourceLotCode().trim());
            if (rl == null) continue;
            fillPlatform(lot, rl);
            if (t.getCustomerBin() == null && rl.customerBin() != null && rl.customerBin().matches("\\d{12}")) {
                t.setCustomerBin(rl.customerBin());
            }
            TenderLotResult r = results.findByTenderLotId(lot.getId())
                    .orElseGet(() -> TenderLotResult.builder().tenderLotId(lot.getId()).tenderId(t.getId()).createdAt(now).build());
            if (r.getFinalAt() != null) continue;
            LotOutcome o = LotOutcome.ofLotStatus(rl.statusId());
            r.setLotStatusId(rl.statusId());
            r.setLotStatus(cut(rl.statusName(), 100));
            r.setOutcome(o);
            if (o != LotOutcome.PENDING && r.getResultsAt() == null) r.setResultsAt(ImportDeadline.parse(buy.resultsAt()));
            if (file != null && file.url() != null) {
                r.setProtocolUrl(cut(file.url(), 300));
                r.setProtocolName(cut(file.name(), 200));
            }
            r.setCheckedAt(now);
            boolean wants = (o == LotOutcome.DONE || o == LotOutcome.FAILED) && r.getProtocolStatus() != ProtocolStatus.PARSED
                    && r.getProtocolStatus() != ProtocolStatus.UNKNOWN_FORMAT && r.getProtocolAttempts() < PROTOCOL_ATTEMPTS;
            if (wants && r.getProtocolUrl() == null) {
                r.setProtocolStatus(ProtocolStatus.NO_FILE);
                r.setProtocolAttempts(r.getProtocolAttempts() + 1);
                r.setProtocolNote("файла протокола на площадке ещё нет");
                wants = false;
            }
            r = results.save(r);
            if (wants) need.add(r.getId());
        }
        String url = need.isEmpty() ? null : results.findById(need.get(0)).map(TenderLotResult::getProtocolUrl).orElse(null);
        return need.isEmpty() || url == null ? List.of() : List.of(new ProtocolJob(t.getId(), url, need));
    }

    static void fillPlatform(TenderLot lot, ResultLot rl) {
        if (lot.getExtLotId() == null) lot.setExtLotId(rl.id());
        if (lot.getPlanPointId() == null) lot.setPlanPointId(rl.planPointId());
        if (lot.getEnstruCode() == null && rl.enstruCode() != null) lot.setEnstruCode(cut(rl.enstruCode(), 30));
        if (lot.getPlanUnit() == null && rl.unit() != null) lot.setPlanUnit(cut(rl.unit(), 50));
    }

    /** Файл не скачан или не разобран — попытка, пометка; после 5 попыток итог заводится без протокола. */
    @Transactional
    public void protocolFailed(ProtocolJob job, String note) {
        for (TenderLotResult r : results.findAllById(job.resultIds())) {
            r.setProtocolAttempts(r.getProtocolAttempts() + 1);
            r.setProtocolStatus(ProtocolStatus.FAILED);
            r.setProtocolNote(cut(note, 300));
        }
    }

    @Transactional
    public void applyProtocol(ProtocolJob job, ProtocolResult parsed, String ourBin) {
        for (TenderLotResult r : results.findAllById(job.resultIds())) {
            TenderLot lot = lots.findById(r.getTenderLotId()).orElse(null);
            if (lot == null) continue;
            LotProtocol lp = parsed.format() == ProtocolFormat.UNKNOWN ? null : parsed.lots().get(lot.getSourceLotCode().trim());
            if (lp == null) {
                r.setProtocolStatus(ProtocolStatus.UNKNOWN_FORMAT);
                r.setProtocolNote(parsed.format() == ProtocolFormat.UNKNOWN ? "вид протокола не знаком — откройте файл"
                        : "лота нет в протоколе — откройте файл");
                continue;
            }
            write(r, lot, lp, ourBin);
        }
    }

    private void write(TenderLotResult r, TenderLot lot, LotProtocol lp, String ourBin) {
        bids.deleteByResultId(r.getId());
        r.setWinnerName(null);
        r.setWinnerBin(null);
        r.setWinnerPrice(null);
        r.setSecondName(null);
        r.setSecondBin(null);
        r.setSecondPrice(null);
        r.setOurPlace(null);
        r.setOurPrice(null);
        r.setOurRejected(null);
        BigDecimal min = null, max = null;
        for (ProtocolBid b : lp.bids()) {
            BigDecimal unit = unitPrice(b, lot);
            boolean winner = !b.rejected() && b.bin() != null && b.bin().equals(lp.winnerBin());
            boolean second = !b.rejected() && b.bin() != null && b.bin().equals(lp.secondBin());
            boolean ours = ourBin != null && ourBin.equals(b.bin());
            bids.save(TenderLotBid.builder().resultId(r.getId()).place(b.place()).supplierName(cut(b.name(), 300))
                    .supplierBin(cut(b.bin(), 20)).unitPrice(unit).total(b.total()).submittedAt(b.submittedAt()).rejected(b.rejected())
                    .rejectReason(cut(b.rejectReason(), 500)).winner(winner).secondPlace(second).ours(ours).build());
            if (!b.rejected() && unit != null) {
                if (min == null || unit.compareTo(min) < 0) min = unit;
                if (max == null || unit.compareTo(max) > 0) max = unit;
            }
            if (winner && r.getWinnerBin() == null) {
                r.setWinnerName(cut(b.name(), 300));
                r.setWinnerBin(b.bin());
                r.setWinnerPrice(unit);
            }
            if (second && r.getSecondBin() == null) {
                r.setSecondName(cut(b.name(), 300));
                r.setSecondBin(b.bin());
                r.setSecondPrice(unit);
            }
            if (ours) {
                r.setOurPlace(b.rejected() ? null : b.place());
                r.setOurPrice(unit);
                r.setOurRejected(b.rejected());
            }
        }
        if (r.getWinnerBin() == null && lp.winnerBin() != null) r.setWinnerBin(lp.winnerBin());
        r.setBidsCount(lp.bidsCount());
        r.setRejectedCount(lp.rejectedCount());
        r.setMinPrice(min);
        r.setMaxPrice(max);
        r.setFailReason(lp.failReason());
        r.setFailText(cut(lp.conclusion(), 300));
        r.setProtocolStatus(ProtocolStatus.PARSED);
        r.setProtocolNote(null);
    }

    /** Цена за единицу: из протокола; у суммы (ОИ) — сумма / количество лота; нет количества — пусто (Review Focus 4). */
    static BigDecimal unitPrice(ProtocolBid b, TenderLot lot) {
        if (b.unitPrice() != null) return b.unitPrice();
        if (b.total() == null || lot.getQuantity() == null || lot.getQuantity() <= 0) return null;
        return b.total().divide(BigDecimal.valueOf(lot.getQuantity()), 2, RoundingMode.HALF_UP);
    }

    /** Номера объявлений, у которых состоявшиеся лоты ждут договора. */
    @Transactional(readOnly = true)
    public List<Long> buysAwaitingContract(Long tenderId) {
        Set<Long> ids = results.findDoneWithoutContract().stream().map(TenderLotResult::getTenderId)
                .filter(id -> tenderId == null || id.equals(tenderId)).collect(Collectors.toCollection(LinkedHashSet::new));
        return tenders.findAllById(ids).stream().map(t -> buyId(t.getSourceExtId())).filter(Objects::nonNull).distinct().toList();
    }

    /** Договоры: по лоту — последний подписанный, «не заключён / отказ / недействителен» мимо; итог окончательный. */
    @Transactional
    public int applyContracts(List<ContractRow> rows, LocalDateTime nowLocal, OffsetDateTime now, boolean silent) {
        Map<Long, Pick> best = new HashMap<>();
        for (ContractRow c : rows) {
            if (!c.concluded()) continue;
            for (ContractUnitRow u : c.units()) {
                if (u.lotId() == null) continue;
                Pick cur = best.get(u.lotId());
                if (cur == null || signed(c).isAfter(signed(cur.row()))) best.put(u.lotId(), new Pick(c, u));
            }
        }
        if (best.isEmpty()) return 0;
        List<TenderLotResult> open = results.findDoneWithoutContract();
        Map<Long, TenderLot> lotById = lots.findAllById(open.stream().map(TenderLotResult::getTenderLotId).toList()).stream()
                .collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        int n = 0;
        for (TenderLotResult r : open) {
            TenderLot lot = lotById.get(r.getTenderLotId());
            if (lot == null || lot.getExtLotId() == null) continue;
            Pick p = best.get(lot.getExtLotId());
            if (p == null) continue;
            LocalDate signedAt = signed(p.row());
            r.setContractNumber(cut(p.row().number(), 60));
            r.setContractSignedAt(signedAt.equals(LocalDate.MIN) ? null : signedAt);
            r.setContractUnitPrice(p.unit().unitPrice());
            r.setContractSum(p.unit().totalWithVat() != null ? p.unit().totalWithVat() : p.row().sumWithVat());
            r.setContractSupplierBin(cut(p.row().supplierBin(), 20));
            r.setFinalAt(now);
            String unit = ResultTexts.unit(lot.getPlanUnit(), lot.getUnit());
            String text = ResultTexts.contract(name(p.row().supplierBin()), p.unit().unitPrice(), unit, p.row().number(), r.getContractSignedAt());
            boolean ours = events.ours(List.of(lot)).contains(lot.getId());
            if (events.add(lot.getTender(), lot, ResultEventKind.CONTRACT, null, ImportDeadline.parse(p.row().signDate()), text, null, ours,
                    silent, nowLocal, now)) n++;
        }
        return n;
    }

    private static LocalDate signed(ContractRow c) {
        LocalDateTime t = ImportDeadline.parse(c.signDate());
        return t == null ? LocalDate.MIN : t.toLocalDate();
    }

    /** Название из справочника площадки (проход заполняет его заранее), иначе «БИН …». */
    public String name(String bin) {
        if (bin == null) return null;
        return subjects.findById(bin).map(GoszakupSubject::getNameRu).filter(s -> !s.isBlank()).orElse("БИН " + bin);
    }

    /** События итогов: итог известен, протокол разобран (или не будет), события ещё нет (спека §7). */
    @Transactional
    public int settle(Long tenderId, LocalDateTime nowLocal, OffsetDateTime now, boolean silent) {
        List<TenderLotResult> todo = (tenderId == null ? results.findUnsettled() : results.findByTenderId(tenderId)).stream()
                .filter(r -> r.getOutcome() != LotOutcome.PENDING && r.getEventAt() == null && settled(r)).toList();
        if (todo.isEmpty()) return 0;
        Map<Long, TenderLot> lotById = lots.findAllById(todo.stream().map(TenderLotResult::getTenderLotId).toList()).stream()
                .collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        Set<Long> ours = events.ours(lotById.values());
        int n = 0;
        for (TenderLotResult r : todo) {
            TenderLot lot = lotById.get(r.getTenderLotId());
            if (lot == null) continue;
            Tender t = lot.getTender();
            String unit = ResultTexts.unit(lot.getPlanUnit(), lot.getUnit());
            ResultEventKind kind = kind(r, lot);
            List<TenderLotBid> list = bids.findByResultIdOrderByPlaceAscIdAsc(r.getId());
            if (events.add(t, lot, kind, null, r.getResultsAt(), text(kind, r, lot, unit, list), null, ours.contains(lot.getId()),
                    silent, nowLocal, now)) n++;
            if (r.getOurRejected() != null) {
                TenderLotBid our = list.stream().filter(TenderLotBid::isOurs).findFirst().orElse(null);
                boolean won = our != null && our.isWinner();
                String text = ResultTexts.ourBid(won, r.getOurPlace(), r.getBidsCount(), r.getOurPrice(), r.getWinnerName(), r.getWinnerPrice(),
                        Boolean.TRUE.equals(r.getOurRejected()), our == null ? null : our.getRejectReason(), unit);
                if (events.add(t, lot, ResultEventKind.OUR_BID, null, r.getResultsAt(), text, null, true, silent, nowLocal, now)) n++;
            }
            r.setEventAt(now);
            if (r.getOutcome() == LotOutcome.CANCELLED) r.setFinalAt(now);
        }
        return n;
    }

    static boolean settled(TenderLotResult r) {
        return r.getOutcome() == LotOutcome.CANCELLED || r.getFinalAt() != null
                || r.getProtocolStatus() == ProtocolStatus.PARSED || r.getProtocolStatus() == ProtocolStatus.UNKNOWN_FORMAT
                || r.getProtocolAttempts() >= PROTOCOL_ATTEMPTS;
    }

    /** Вид события итога: лот из одного источника — свои виды; не состоялся — по причине протокола. */
    ResultEventKind kind(TenderLotResult r, TenderLot lot) {
        boolean oi = "ОИ".equals(ResultTexts.method(lot.getSourceLotCode()));
        return switch (r.getOutcome()) {
            case CANCELLED -> ResultEventKind.CANCELLED;
            case DONE -> oi ? ResultEventKind.OI_DONE : ResultEventKind.DONE;
            case FAILED -> oi ? ResultEventKind.OI_FAILED
                    : r.getFailReason() == FailReason.ONE_BID ? ResultEventKind.ONE_BID
                    : r.getFailReason() == FailReason.NO_BIDS ? ResultEventKind.NO_BIDS
                    : ResultEventKind.FAILED_OTHER;
            case PENDING -> throw new IllegalStateException("итог лота ещё не известен");
        };
    }

    private String text(ResultEventKind kind, TenderLotResult r, TenderLot lot, String unit, List<TenderLotBid> list) {
        TenderLotBid first = list.stream().filter(b -> !b.isRejected()).findFirst().orElse(list.isEmpty() ? null : list.get(0));
        return switch (kind) {
            case DONE -> ResultTexts.done(r, unit, lot.getMaxCost());
            case NO_BIDS -> ResultTexts.noBids();
            case ONE_BID -> ResultTexts.oneBid(first == null ? null : first.getSupplierName(), first == null ? null : first.getUnitPrice(), unit);
            case FAILED_OTHER -> ResultTexts.failedOther(r.getFailText(), r.getProtocolStatus());
            case CANCELLED -> ResultTexts.cancelled();
            case OI_DONE -> ResultTexts.oiDone(r.getWinnerName() != null ? r.getWinnerName() : r.getWinnerBin() == null ? null : name(r.getWinnerBin()));
            case OI_FAILED -> ResultTexts.oiFailed(first == null ? null : ResultTexts.who(first.getSupplierName(), first.getSupplierBin()));
            default -> kind.name();
        };
    }

    /** Дальше не проверяем: прошло 30 дней от итогов (нет итогов — от срока приёма). */
    @Transactional
    public int closeStale(LocalDateTime nowLocal, OffsetDateTime now) {
        int n = 0;
        Map<Long, Tender> cache = new HashMap<>();
        for (TenderLotResult r : results.findOpen()) {
            LocalDateTime from = r.getResultsAt();
            if (from == null) {
                Tender t = cache.computeIfAbsent(r.getTenderId(), id -> tenders.findById(id).orElse(null));
                from = t == null ? null : t.getDeadlineAt() != null ? t.getDeadlineAt()
                        : t.getDeadline() == null ? null : t.getDeadline().plusDays(1).atStartOfDay();
            }
            if (from != null && from.plusDays(TRACK_DAYS).isBefore(nowLocal)) {
                r.setFinalAt(now);
                n++;
            }
        }
        return n;
    }

    /** БИН заказчиков тендеров с неотправленными «звонить» — контакты подтягиваются до сообщения. */
    @Transactional(readOnly = true)
    public List<String> pendingCustomerBins(int limit) {
        Set<Long> ids = eventRepo.findUrgentPending().stream().map(TenderResultEvent::getTenderId).collect(Collectors.toCollection(LinkedHashSet::new));
        return tenders.findAllById(ids).stream().map(Tender::getCustomerBin).filter(b -> b != null && b.matches("\\d{12}"))
                .distinct().limit(limit).toList();
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
```

- [ ] **Step 9: Проход**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ResultBuy;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.service.CompanyProfileService;
import com.vladoose.nir.service.productwatch.ProductWatchService;
import com.vladoose.nir.service.tenderresults.TenderResultsWriter.Candidate;
import com.vladoose.nir.service.tenderresults.TenderResultsWriter.ProtocolJob;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolParser;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolResult;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Проход итогов (спека 2026-10-22-tender-results-market-prices §4): объявления пачками → протоколы → договоры → события → контакты
 * заказчиков для «звонить». Сеть — здесь, вне транзакций; запись — {@link TenderResultsWriter}. 401 / 403 / 429 — стоп прохода
 * (токен общий с продом), прочий сбой пачки — ошибка прохода и дальше; протокол — не ошибка прохода (решение 6).
 */
@Service
public class TenderResultsService {

    private static final Logger log = LoggerFactory.getLogger(TenderResultsService.class);

    static final int CANDIDATES = 200;
    static final int BUY_BATCH = 50;
    static final int CONTRACT_BATCH = 100;
    static final int NAMES_PER_PASS = 100;
    static final int CONTACTS_PER_PASS = 20;
    static final long PAUSE_MS = 300;

    /** Завести объявление в АИС (повтор закупки, задача 6) — путь «Завести в АИС» поиска нашей продукции. */
    public interface Importer {
        long importAnnouncement(String numberAnno);
    }

    private final GoszakupResultsClient client;
    private final TenderResultsWriter writer;
    private final GoszakupSubjects subjects;
    private final Supplier<String> ourBin;
    private final Importer importer;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public TenderResultsService(GoszakupResultsClient client, TenderResultsWriter writer, GoszakupSubjects subjects,
                                CompanyProfileService profiles, ProductWatchService productWatch) {
        this(client, writer, subjects, () -> bin(profiles.forMarket(Market.KZ).getBinInn()), productWatch::importAnnouncement,
                Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public TenderResultsService(GoszakupResultsClient client, TenderResultsWriter writer, GoszakupSubjects subjects,
                                Supplier<String> ourBin, Importer importer, Clock clock, UpstreamRetry.Sleeper sleeper) {
        this.client = client;
        this.writer = writer;
        this.subjects = subjects;
        this.ourBin = ourBin;
        this.importer = importer;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** БИН West-Med из «Реквизитов» KZ — по нему наша заявка в протоколе; не 12 цифр — не ищем. */
    static String bin(String s) {
        String d = s == null ? "" : s.replaceAll("\\D", "");
        return d.length() == 12 ? d : null;
    }

    public void run(ImportSummary sum) {
        pass(null, sum);
    }

    /** «Проверить итоги» (спека §4): тот же проход по одному тендеру. */
    public void check(long tenderId, ImportSummary sum) {
        pass(tenderId, sum);
    }

    /** Можно ли проверять тендер — до постановки в очередь (400 с текстом). */
    public void checkable(long tenderId) {
        writer.candidate(tenderId, nowLocal());
    }

    private LocalDateTime nowLocal() {
        return LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
    }

    private void pass(Long tenderId, ImportSummary sum) {
        if (!client.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return;
        }
        LocalDateTime nowLocal = nowLocal();
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean silent = writer.firstRun();
        List<Candidate> cands = tenderId == null ? writer.candidates(nowLocal, CANDIDATES) : List.of(writer.candidate(tenderId, nowLocal));
        sum.setFetched(cands.size());
        int events = 0;
        try {
            List<ProtocolJob> jobs = buys(cands, now, sum);
            String our = ourBin.get();
            for (ProtocolJob j : jobs) protocol(j, our);
            events += contracts(tenderId, nowLocal, now, silent, sum);
            events += writer.settle(tenderId, nowLocal, now, silent);
            if (tenderId == null) writer.closeStale(nowLocal, now);
            contacts();
        } catch (RuntimeException e) {
            sum.addError((ProductWatchService.stopping(e) ? "проход остановлен: " : "") + ErrorText.of(e));
            log.warn("Итоги тендеров: проход прерван: {}", ErrorText.of(e));
        }
        sum.setCreated(events);
        sum.setMessage("Итоги: тендеров проверено " + cands.size() + ", событий " + events
                + (silent ? " (первый проход — без сообщений)" : "")
                + (sum.getErrors() > 0 ? "; ошибок " + sum.getErrors() + ": " + sum.getLastError() : ""));
    }

    private List<ProtocolJob> buys(List<Candidate> cands, OffsetDateTime now, ImportSummary sum) {
        List<ProtocolJob> jobs = new ArrayList<>();
        for (int i = 0; i < cands.size(); i += BUY_BATCH) {
            Map<Long, Candidate> byBuy = cands.subList(i, Math.min(cands.size(), i + BUY_BATCH)).stream()
                    .collect(Collectors.toMap(Candidate::buyId, c -> c, (a, b) -> a, LinkedHashMap::new));
            List<ResultBuy> buys;
            try {
                buys = call(() -> client.announcements(new ArrayList<>(byBuy.keySet())));
            } catch (RuntimeException e) {
                if (ProductWatchService.stopping(e)) throw e;
                sum.addError("объявления: " + ErrorText.of(e));
                continue;
            }
            for (ResultBuy b : buys) {
                Candidate c = byBuy.get(b.id());
                if (c == null) continue;
                jobs.addAll(writer.applyBuy(c, b, now));
                sum.setUpdated(sum.getUpdated() + 1);
            }
        }
        return jobs;
    }

    /** Файл протокола: не скачался / не разобрался — попытка (до 5), стоп-код — наверх. */
    private void protocol(ProtocolJob job, String our) {
        byte[] bytes;
        try {
            bytes = call(() -> client.downloadProtocol(job.url()));
        } catch (RuntimeException e) {
            if (ProductWatchService.stopping(e)) throw e;
            writer.protocolFailed(job, "не скачан: " + ErrorText.of(e));
            return;
        }
        if (bytes == null) {
            writer.protocolFailed(job, "файла нет на площадке");
            return;
        }
        ProtocolResult parsed;
        try {
            parsed = ProtocolParser.parse(bytes);
        } catch (RuntimeException e) {
            writer.protocolFailed(job, "не разобран: " + ErrorText.of(e));
            return;
        }
        writer.applyProtocol(job, parsed, our);
    }

    private int contracts(Long tenderId, LocalDateTime nowLocal, OffsetDateTime now, boolean silent, ImportSummary sum) {
        List<Long> buys = writer.buysAwaitingContract(tenderId);
        int n = 0;
        for (int i = 0; i < buys.size(); i += CONTRACT_BATCH) {
            List<Long> batch = buys.subList(i, Math.min(buys.size(), i + CONTRACT_BATCH));
            List<ContractRow> rows;
            try {
                rows = call(() -> client.contractsByBuyIds(batch));
            } catch (RuntimeException e) {
                if (ProductWatchService.stopping(e)) throw e;
                sum.addError("договоры: " + ErrorText.of(e));
                continue;
            }
            subjects.names(rows.stream().map(ContractRow::supplierBin).filter(Objects::nonNull).toList(), NAMES_PER_PASS);
            n += writer.applyContracts(rows, nowLocal, now, silent);
        }
        return n;
    }

    /** Телефон и почта заказчика — до сообщения «звонить» (справочник площадки, раз в 30 дней). */
    private void contacts() {
        for (String bin : writer.pendingCustomerBins(CONTACTS_PER_PASS)) subjects.get(bin);
    }

    private <T> T call(Supplier<T> s) {
        try {
            return UpstreamRetry.call(sleeper, s);
        } finally {
            pause();
        }
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("проход прерван");
        }
    }
}
```

- [ ] **Step 10: Запустить — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.*' --tests 'com.vladoose.nir.service.tendernotify.*' 2>&1 | tail -20` (sandbox off)
Expected: PASS. Если строка ЗЦП из PDF не совпала — вывести `ProtocolText.lines(protocolPdf("zcp-winner.txt"))` временно в тесте и сравнить с текстовой фикстурой (PDFBox может вставить лишний пробел), не править фикстуру руками.

- [ ] **Step 11: Мутации** (по одной, вернуть копией файла, `./gradlew compileJava` в конце)

1. `ResultEvents.topic`: `return zko && kind.urgent() ? …` → `return kind.urgent() ? ResultTopic.TENDERS : ResultTopic.NONE` → `noBids_outsideZko_historyOnly` красный.
2. `TenderResultsWriter.settled`: убрать `|| r.getProtocolAttempts() >= PROTOCOL_ATTEMPTS` → `downloadFailures_fiveAttempts_thenSettles` красный.
3. `applyBuy`: убрать `r.getProtocolStatus() != ProtocolStatus.PARSED &&` → `repeatedChecks_oneEvent_oneDownload` красный.
4. `pass`: `boolean silent = false;` → `firstRun_silent` красный.
5. `buys`/`protocol`: в `catch` не пробрасывать стоп-код → `forbiddenMidPass_stops_nothingLost` красный.
6. `applyContracts`: убрать `if (!c.concluded()) continue;` → `contract_latestConcluded_final` красный.
7. `unitPrice`: убрать проверку количества → `oiDone_noQuantity_unitPriceEmpty` красный (NPE).
8. `ResultTexts.range`: `RoundingMode.DOWN` → `HALF_UP` → `ResultTextsTest.range` красный («614»).

- [ ] **Step 12: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/tenderresults src/main/java/com/vladoose/nir/repository/TenderLotResultRepository.java \
  src/main/java/com/vladoose/nir/service/tendernotify/NewTenderNotifier.java src/test/java/com/vladoose/nir/tenderresults/ResultsFixtures.java \
  src/test/java/com/vladoose/nir/service/tenderresults && git commit -m "$(cat <<'EOF'
feat(results): проход итогов — статусы, протоколы, договоры, события «звонить» и сводки по темам

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Цепочка попыток — повтор, «из одного источника», автоимпорт повтора, «наш лот» по пункту плана

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/tenderresults/TenderResultsWriter.java` (`ChainTask`, `ChainOutcome`, `openChains`, `applyChain`, `linkImported`; вид события «1 заявка» по цепочке)
- Modify: `src/main/java/com/vladoose/nir/service/tenderresults/TenderResultsService.java` (шаг цепочек перед событиями, автоимпорт повтора)
- Modify: `src/main/java/com/vladoose/nir/repository/TenderResultEventRepository.java` (`findFirstByTenderLotIdAndKindAndRefKey`)
- Modify (тесты): `src/test/java/com/vladoose/nir/tenderresults/ResultsFixtures.java` (`attempt`)
- Test: `src/test/java/com/vladoose/nir/service/tenderresults/TenderResultsChainTest.java`

**Interfaces:**
- Consumes: задача 5 (`TenderResultsWriter`, `ResultEvents.add/ours`, `ResultTexts.method/methodName/repeat/oiDone/oiFailed/oneBid`, `TenderResultsService.Importer`), `GoszakupResultsClient.lotsByPlanPoints`, `TenderRepository.findBySourceExtIdIn`.
- Produces: `TenderResultsWriter.record ChainTask(long resultId, long lotId, long planPointId)`, `record ChainOutcome(int events, List<String> toImport)`, `openChains(Long tenderId)`, `applyChain(ChainTask, List<AttemptLot>, LocalDateTime nowLocal, OffsetDateTime now, boolean silent) → ChainOutcome`, `linkImported(long resultId, String anno, long tenderId, OffsetDateTime now)`; `TenderResultEventRepository.findFirstByTenderLotIdAndKindAndRefKey(Long, ResultEventKind, String)`; `ResultsFixtures.attempt(long lotId, String lotNumber, int status, long point, String anno, String publish, String end, String results, String supplierBin)`.

- [ ] **Step 1: Помощник теста** — `ResultsFixtures.java`, добавить:

```java
    /** Попытка закупки по пункту плана; способ — по номеру лота (ЗЦП / ОИ / ОК). */
    public static AttemptLot attempt(long lotId, String lotNumber, int status, long point, String anno, String publish, String end,
                                     String results, String supplierBin) {
        String method = lotNumber.contains("-ОИ") ? "Из одного источника по несостоявшимся закупкам" : "Запрос ценовых предложений";
        return new AttemptLot(lotId, lotNumber, status, status == 360 ? "Закупка состоялась" : status == 370 ? "Закупка не состоялась" : "Опубликован",
                point, anno, TenderResultsWriter.buyId(anno), lotNumber.contains("-ОИ") ? 6 : 3, method, publish, end, results, supplierBin);
    }
```

(импорт `com.vladoose.nir.service.tenderresults.TenderResultsWriter`).

- [ ] **Step 2: Тест цепочки**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Цепочка попыток (спека 2026-10-22-tender-results-market-prices §6, решения 2–5). */
@SpringBootTest
@Transactional
class TenderResultsChainTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final String UNKNOWN = "<html><body><p>Протокол об итогах электронного аукциона № 1-ЭА1</p></body></html>";

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotResultRepository results;
    @Autowired TenderResultEventRepository events;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired TenderResultsWriter writer;
    @Autowired JdbcTemplate jdbc;

    FakeGoszakupResultsClient fake;
    TenderResultsService service;
    final List<String> imported = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        events.deleteAllInBatch();
        results.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        GoszakupSubjects subjects = new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { });
        service = new TenderResultsService(fake, writer, subjects, () -> null, anno -> {
            imported.add(anno);
            return ResultsFixtures.tender(tenders, TenderResultsWriter.buyId(anno), NOW.plusDays(3), "9-ЗЦП9", "Повтор").getId();
        }, CLOCK, ms -> { });
        // не первый проход — события не молчат
        Tender t = ResultsFixtures.tender(tenders, 970009998L, NOW.minusDays(40), "0-ЗЦП1", "Прошлый");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId())
                .outcome(LotOutcome.DONE).finalAt(OffsetDateTime.now(CLOCK)).eventAt(OffsetDateTime.now(CLOCK)).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Не состоявшийся лот: своя попытка — первая в цепочке (id лота площадки = ownLot). */
    private Tender failed(long buyId, String code, String protocol, String region) throws Exception {
        Tender t = ResultsFixtures.tenderIn(tenders, buyId, NOW.minusDays(8), region, code, "Тележка медицинская");
        String url = URL + buyId;
        fake.buys.put(buyId, buy(buyId, "2026-10-21 12:54:49", url, lot(ownLot(buyId), code, 370, point(buyId))));
        fake.files.put(url, protocol.endsWith(".txt") ? protocolPdf(protocol) : protocol.getBytes(StandardCharsets.UTF_8));
        return t;
    }

    private static long ownLot(long buyId) { return 43600000L + buyId % 1000; }
    private static long point(long buyId) { return 88600000L + buyId % 1000; }

    private List<TenderResultEvent> ev(Tender t) {
        return events.findByTenderIdOrderByIdAsc(t.getId());
    }

    private TenderLotResult result(Tender t) {
        return results.findByTenderLotId(t.getLots().get(0).getId()).orElseThrow();
    }

    /** Протокол не разобран, а сразу после провала — закупка из одного источника: значит, была одна заявка (решение 2). */
    @Test
    void oneBid_inferredFromOiAttempt() throws Exception {
        Tender t = failed(970002101L, "88111123-ЗЦП2", UNKNOWN, "Западно-Казахстанская область");
        fake.attempts.put(point(970002101L), List.of(
                attempt(ownLot(970002101L), "88111123-ЗЦП2", 370, point(970002101L), "970002101-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699001L, "88111123-ОИ3", 220, point(970002101L), "970002102-1", "2026-10-21 12:55:30",
                        "2026-10-24 12:55:30", null, "900000000005")));
        fake.subjects.put("900000000005", new SubjectInfo("900000000005", "ИП БОЛАТ", null, null));
        service.check(t.getId(), new ImportSummary());
        assertThat(ev(t)).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.ONE_BID);
        assertThat(ev(t).get(0).getText()).isEqualTo("1 заявка → из одного источника у ИП БОЛАТ");
        assertThat(ev(t).get(0).isUrgent()).isFalse();
        assertThat(imported).as("закупку из одного источника не заводим (решение 5)").isEmpty();
        assertThat(result(t).getAttempts()).extracting(ResultAttempt::method).containsExactly("ЗЦП", "ОИ");
    }

    /** «Из одного источника сорвалось» по нашему лоту — «звонить» со звуком; повторный проход — без второго события. */
    @Test
    void oiFailed_callUrgentOnce() throws Exception {
        Tender t = failed(970002201L, "88111123-ЗЦП2", "zcp-one-bid.txt", "г. Астана");
        markOurs(jdbc, t.getLots().get(0));
        fake.attempts.put(point(970002201L), List.of(
                attempt(ownLot(970002201L), "88111123-ЗЦП2", 370, point(970002201L), "970002201-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699002L, "88111123-ОИ3", 370, point(970002201L), "970002202-1", "2026-10-21 12:55:30",
                        "2026-10-24 12:55:30", "2026-10-22 09:00:00", "900000000006")));
        service.check(t.getId(), new ImportSummary());
        service.check(t.getId(), new ImportSummary());
        // цепочка идёт до событий итога — порядок строк не важен, «История» сортирует по времени площадки
        assertThat(ev(t)).extracting(TenderResultEvent::getKind).containsExactlyInAnyOrder(ResultEventKind.ONE_BID, ResultEventKind.OI_FAILED);
        TenderResultEvent call = ev(t).stream().filter(e -> e.getKind() == ResultEventKind.OI_FAILED).findFirst().orElseThrow();
        assertThat(call.isUrgent()).isTrue();
        assertThat(call.getTopic()).isEqualTo(ResultTopic.PRODUCTS);
        assertThat(call.getRefKey()).isEqualTo("970002202-1");
        assertThat(call.getText()).startsWith("из одного источника сорвалось (");
    }

    /** Повтор: событие один раз; запрос цен с идущим приёмом заводится в АИС, из одного источника — нет; дальше ведёт новый тендер. */
    @Test
    void repeat_eventOnce_importsRequestNotOi() throws Exception {
        Tender t = failed(970002301L, "88208881-ЗЦП1", "zcp-no-bids.txt", "Западно-Казахстанская область");
        fake.attempts.put(point(970002301L), List.of(
                attempt(ownLot(970002301L), "88208881-ЗЦП1", 370, point(970002301L), "970002301-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699003L, "88208881-ЗЦП2", 220, point(970002301L), "970002302-1", "2026-10-21 15:00:00",
                        "2026-10-25 09:00:00", null, null),
                attempt(43699004L, "88208881-ОИ3", 220, point(970002301L), "970002303-1", "2026-10-21 16:00:00",
                        "2026-10-26 09:00:00", null, "900000000007")));
        service.check(t.getId(), new ImportSummary());
        assertThat(imported).containsExactly("970002302-1");
        assertThat(ev(t)).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.REPEAT, ResultEventKind.NO_BIDS);
        assertThat(ev(t).get(0).getText()).isEqualTo("объявлена повторная попытка № 970002302-1 (запрос цен), приём до 25.10 09:00");
        TenderLotResult r = result(t);
        Long repeatId = tenders.findBySourceExtId("970002302-1").orElseThrow().getId();
        assertThat(r.getNextTenderId()).isEqualTo(repeatId);
        assertThat(r.getFinalAt()).isNotNull();
        assertThat(r.getAttempts()).filteredOn(a -> "970002302-1".equals(a.anno())).extracting(ResultAttempt::tenderId).containsExactly(repeatId);
        assertThat(ev(t).get(0).getRefTenderId()).as("«История» ведёт на повтор").isEqualTo(repeatId);
    }

    /** Следующая попытка уже в АИС — итоги дальше ведёт она, у прошлой — ссылка и без событий цепочки (решение 3). */
    @Test
    void laterAttemptInAis_takesOver() throws Exception {
        Tender t = failed(970002401L, "88208881-ЗЦП1", "zcp-no-bids.txt", "Западно-Казахстанская область");
        Tender next = ResultsFixtures.tender(tenders, 970002402L, NOW.plusDays(2), "88208881-ЗЦП2", "Тележка");
        fake.attempts.put(point(970002401L), List.of(
                attempt(ownLot(970002401L), "88208881-ЗЦП1", 370, point(970002401L), "970002401-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699005L, "88208881-ЗЦП2", 220, point(970002401L), "970002402-1", "2026-10-21 15:00:00",
                        "2026-10-24 09:00:00", null, null)));
        service.check(t.getId(), new ImportSummary());
        TenderLotResult r = result(t);
        assertThat(r.getNextTenderId()).isEqualTo(next.getId());
        assertThat(r.getFinalAt()).isNotNull();
        assertThat(ev(t)).extracting(TenderResultEvent::getKind).containsExactly(ResultEventKind.NO_BIDS);
        assertThat(imported).isEmpty();
    }

    /** Повтор нашего лота — тоже наш: тема нашей продукции по пункту плана (решение 4). */
    @Test
    void oursInheritedByPlanPoint() throws Exception {
        Tender first = ResultsFixtures.tender(tenders, 970002501L, NOW.minusDays(20), "88208881-ЗЦП1", "Тележка");
        TenderLot firstLot = first.getLots().get(0);
        firstLot.setPlanPointId(point(970002502L));
        tenders.saveAndFlush(first);
        markOurs(jdbc, firstLot);
        Tender t = failed(970002502L, "88208881-ЗЦП2", "zcp-no-bids.txt", "г. Астана");
        service.check(t.getId(), new ImportSummary());
        assertThat(ev(t).get(0).getKind()).isEqualTo(ResultEventKind.NO_BIDS);
        assertThat(ev(t).get(0).getTopic()).isEqualTo(ResultTopic.PRODUCTS);
    }

    /** Последняя попытка состоялась — цепочка окончательна; событие «из одного источника — состоялся» в сводку. */
    @Test
    void chainFinal_whenLastAttemptDone() throws Exception {
        Tender t = failed(970002601L, "88111123-ЗЦП2", "zcp-one-bid.txt", "Западно-Казахстанская область");
        markOurs(jdbc, t.getLots().get(0));
        fake.attempts.put(point(970002601L), List.of(
                attempt(ownLot(970002601L), "88111123-ЗЦП2", 370, point(970002601L), "970002601-1", "2026-10-16 10:00:00",
                        "2026-10-21 10:00:00", "2026-10-21 12:54:49", null),
                attempt(43699006L, "88111123-ОИ3", 360, point(970002601L), "970002602-1", "2026-10-21 12:55:30",
                        "2026-10-24 12:55:30", "2026-10-22 09:00:00", "900000000008")));
        service.check(t.getId(), new ImportSummary());
        assertThat(result(t).getFinalAt()).isNotNull();
        TenderResultEvent oi = ev(t).stream().filter(e -> e.getKind() == ResultEventKind.OI_DONE).findFirst().orElseThrow();
        assertThat(oi.isUrgent()).isFalse();
        assertThat(oi.getDigestedAt()).isNull();
    }
}
```

- [ ] **Step 3: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.TenderResultsChainTest' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — `attempt(...)` не компилируется до шага 1; после шага 1 — тесты красные: событий цепочки нет, «1 заявка» не выводится (`FAILED_OTHER`), повтор не заводится.

- [ ] **Step 4: Запись цепочки** — `TenderResultsWriter.java`, добавить (импорт `com.vladoose.nir.integration.goszakup.dto.AttemptLot` — уже под `dto.*`):

```java
    public record ChainTask(long resultId, long lotId, long planPointId) {}

    public record ChainOutcome(int events, List<String> toImport) {}

    /** Не состоявшиеся лоты, цепочка которых не досмотрена (спека §6). */
    @Transactional(readOnly = true)
    public List<ChainTask> openChains(Long tenderId) {
        List<TenderLotResult> open = results.findOpenFailed().stream()
                .filter(r -> tenderId == null || r.getTenderId().equals(tenderId)).toList();
        Map<Long, TenderLot> lotById = lots.findAllById(open.stream().map(TenderLotResult::getTenderLotId).toList()).stream()
                .collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        List<ChainTask> out = new ArrayList<>();
        for (TenderLotResult r : open) {
            TenderLot lot = lotById.get(r.getTenderLotId());
            if (lot != null && lot.getPlanPointId() != null) out.add(new ChainTask(r.getId(), lot.getId(), lot.getPlanPointId()));
        }
        return out;
    }

    /**
     * Попытки пункта плана (спека §6): хранятся у итога; события — по попыткам ПОСЛЕ своей и только тем, которых нет в АИС
     * (решение 3): «повтор объявлен», «из одного источника — состоялся / сорвалось». Поздняя попытка уже в АИС — дальше итоги
     * ведёт она (ссылка, итог окончательный). Повтор запросом цен или конкурсом с идущим приёмом — кандидат «Завести в АИС».
     */
    @Transactional
    public ChainOutcome applyChain(ChainTask task, List<AttemptLot> attempts, LocalDateTime nowLocal, OffsetDateTime now, boolean silent) {
        TenderLotResult r = results.findById(task.resultId()).orElse(null);
        TenderLot lot = lots.findById(task.lotId()).orElse(null);
        if (r == null || lot == null || r.getFinalAt() != null || attempts.isEmpty()) return new ChainOutcome(0, List.of());
        Tender t = lot.getTender();
        List<AttemptLot> sorted = attempts.stream().filter(a -> a.anno() != null)
                .sorted(Comparator.comparing((AttemptLot a) -> ImportDeadline.parse(a.publishDate()), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AttemptLot::lotId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Map<String, Long> inAis = tenders.findBySourceExtIdIn(sorted.stream().map(AttemptLot::anno).distinct().toList()).stream()
                .filter(x -> x.getMarket() == Market.KZ)
                .collect(Collectors.toMap(Tender::getSourceExtId, Tender::getId, (a, b) -> a));
        List<ResultAttempt> list = new ArrayList<>();
        for (AttemptLot a : sorted) {
            list.add(new ResultAttempt(a.anno(), a.lotNumber(), ResultTexts.method(a.lotNumber()), a.statusId(), cut(a.statusName(), 100),
                    a.publishDate(), a.resultsAt(), a.supplierBin(), inAis.get(a.anno())));
        }
        r.setAttempts(list);
        int own = -1;
        for (int i = 0; i < sorted.size(); i++) {
            AttemptLot a = sorted.get(i);
            if (Objects.equals(a.lotId(), lot.getExtLotId()) || a.anno().equals(t.getSourceExtId())) {
                own = i;
                break;
            }
        }
        if (own < 0) return new ChainOutcome(0, List.of());
        for (int i = list.size() - 1; i > own; i--) {
            Long id = list.get(i).tenderId();
            if (id != null && !id.equals(t.getId())) {
                r.setNextTenderId(id);
                r.setFinalAt(now);
                return new ChainOutcome(0, List.of());
            }
        }
        boolean ours = events.ours(List.of(lot)).contains(lot.getId());
        int n = 0;
        List<String> toImport = new ArrayList<>();
        for (int i = own + 1; i < sorted.size(); i++) {
            AttemptLot a = sorted.get(i);
            String m = ResultTexts.method(a.lotNumber());
            if ("ОИ".equals(m)) {
                LotOutcome o = LotOutcome.ofLotStatus(a.statusId());
                String supplier = a.supplierBin() == null ? null : name(a.supplierBin());
                LocalDateTime at = ImportDeadline.parse(a.resultsAt());
                if (o == LotOutcome.DONE && events.add(t, lot, ResultEventKind.OI_DONE, a.anno(), at, ResultTexts.oiDone(supplier), null,
                        ours, silent, nowLocal, now)) n++;
                if (o == LotOutcome.FAILED && events.add(t, lot, ResultEventKind.OI_FAILED, a.anno(), at, ResultTexts.oiFailed(supplier), null,
                        ours, silent, nowLocal, now)) n++;
            } else {
                LocalDateTime end = ImportDeadline.parse(a.endDate());
                if (events.add(t, lot, ResultEventKind.REPEAT, a.anno(), ImportDeadline.parse(a.publishDate()),
                        ResultTexts.repeat(a.anno(), ResultTexts.methodName(m), end), null, ours, silent, nowLocal, now)) n++;
                if (("ЗЦП".equals(m) || "ОК".equals(m)) && end != null && end.isAfter(nowLocal)) toImport.add(a.anno());
            }
        }
        LotOutcome last = LotOutcome.ofLotStatus(sorted.get(sorted.size() - 1).statusId());
        if (sorted.size() - 1 > own && (last == LotOutcome.DONE || last == LotOutcome.CANCELLED)) r.setFinalAt(now);
        return new ChainOutcome(n, toImport);
    }

    /** Повтор заведён в АИС: ссылка, номер тендера у попытки и у события «повтор» («История» ведёт на него), дальше итоги ведёт он. */
    @Transactional
    public void linkImported(long resultId, String anno, long tenderId, OffsetDateTime now) {
        results.findById(resultId).ifPresent(r -> {
            eventRepo.findFirstByTenderLotIdAndKindAndRefKey(r.getTenderLotId(), ResultEventKind.REPEAT, anno)
                    .ifPresent(e -> e.setRefTenderId(tenderId));
            List<ResultAttempt> list = new ArrayList<>();
            for (ResultAttempt a : r.getAttempts()) {
                list.add(anno.equals(a.anno()) ? new ResultAttempt(a.anno(), a.lotNumber(), a.method(), a.statusId(), a.status(),
                        a.publishedAt(), a.resultsAt(), a.supplierBin(), tenderId) : a);
            }
            r.setAttempts(list);
            r.setNextTenderId(tenderId);
            r.setFinalAt(now);
        });
    }

    /** Своя попытка не из одного источника, а следом в цепочке — закупка из одного источника. */
    static boolean oiAfterOwn(TenderLotResult r, TenderLot lot) {
        List<ResultAttempt> list = r.getAttempts();
        for (int i = 0; i < list.size(); i++) {
            if (!Objects.equals(list.get(i).lotNumber(), lot.getSourceLotCode())) continue;
            for (int j = i + 1; j < list.size(); j++) if ("ОИ".equals(list.get(j).method())) return true;
            return false;
        }
        return false;
    }

    /** Поставщик закупки из одного источника после своей попытки (для текста «1 заявка → …»). */
    private String oiSupplierAfterOwn(TenderLotResult r, TenderLot lot) {
        List<ResultAttempt> list = r.getAttempts();
        boolean after = false;
        for (ResultAttempt a : list) {
            if (Objects.equals(a.lotNumber(), lot.getSourceLotCode())) { after = true; continue; }
            if (after && "ОИ".equals(a.method()) && a.supplierBin() != null) return name(a.supplierBin());
        }
        return null;
    }
```

В `kind(...)` ветку `FAILED` заменить на:

```java
            case FAILED -> oi ? ResultEventKind.OI_FAILED
                    : r.getFailReason() == FailReason.ONE_BID || r.getFailReason() == null && oiAfterOwn(r, lot) ? ResultEventKind.ONE_BID
                    : r.getFailReason() == FailReason.NO_BIDS ? ResultEventKind.NO_BIDS
                    : ResultEventKind.FAILED_OTHER;
```

В `text(...)` ветку `ONE_BID` заменить на:

```java
            case ONE_BID -> first != null
                    ? ResultTexts.oneBid(ResultTexts.who(first.getSupplierName(), first.getSupplierBin()), first.getUnitPrice(), unit)
                    : ResultTexts.oneBid(oiSupplierAfterOwn(r, lot), null, unit);
```

`TenderResultEventRepository.java` — добавить (импорт `java.util.Optional`):

```java
    Optional<TenderResultEvent> findFirstByTenderLotIdAndKindAndRefKey(Long tenderLotId, ResultEventKind kind, String refKey);
```

- [ ] **Step 5: Шаг цепочек в проходе** — `TenderResultsService.java`: в `pass` после `events += contracts(...)` вставить `events += chains(tenderId, nowLocal, now, silent, sum);`; добавить (импорты `com.vladoose.nir.integration.goszakup.dto.AttemptLot`, `com.vladoose.nir.service.tenderresults.TenderResultsWriter.ChainTask`):

```java
    static final int CHAIN_BATCH = 100;

    /** Цепочки попыток — пачками по 100 пунктов плана; повтор с идущим приёмом заводится в АИС (спека §6, решение 5). */
    private int chains(Long tenderId, LocalDateTime nowLocal, OffsetDateTime now, boolean silent, ImportSummary sum) {
        Map<Long, List<ChainTask>> byPoint = writer.openChains(tenderId).stream()
                .collect(Collectors.groupingBy(ChainTask::planPointId, LinkedHashMap::new, Collectors.toList()));
        List<Long> points = new ArrayList<>(byPoint.keySet());
        int n = 0;
        for (int i = 0; i < points.size(); i += CHAIN_BATCH) {
            List<Long> batch = points.subList(i, Math.min(points.size(), i + CHAIN_BATCH));
            List<AttemptLot> attempts;
            try {
                attempts = call(() -> client.lotsByPlanPoints(batch));
            } catch (RuntimeException e) {
                if (ProductWatchService.stopping(e)) throw e;
                sum.addError("цепочки: " + ErrorText.of(e));
                continue;
            }
            subjects.names(attempts.stream().map(AttemptLot::supplierBin).filter(Objects::nonNull).toList(), NAMES_PER_PASS);
            Map<Long, List<AttemptLot>> byPt = attempts.stream().filter(a -> a.planPointId() != null)
                    .collect(Collectors.groupingBy(AttemptLot::planPointId));
            for (Long p : batch) {
                for (ChainTask task : byPoint.get(p)) {
                    TenderResultsWriter.ChainOutcome o = writer.applyChain(task, byPt.getOrDefault(p, List.of()), nowLocal, now, silent);
                    n += o.events();
                    for (String anno : o.toImport()) importRepeat(task, anno, now);
                }
            }
        }
        return n;
    }

    private void importRepeat(ChainTask task, String anno, OffsetDateTime now) {
        try {
            long id = importer.importAnnouncement(anno);
            writer.linkImported(task.resultId(), anno, id, now);
        } catch (RuntimeException e) {
            if (ProductWatchService.stopping(e)) throw e;
            log.warn("Итоги тендеров: повтор {} не заведён: {}", anno, ErrorText.of(e));
        } finally {
            pause();
        }
    }
```

- [ ] **Step 6: Запустить — зелёный (и прежние тесты пакета)**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.*' 2>&1 | tail -20` (sandbox off)
Expected: PASS.

- [ ] **Step 7: Мутации**

1. `applyChain`: убрать цикл «поздняя попытка в АИС» → `laterAttemptInAis_takesOver` красный (появляется `REPEAT`).
2. `applyChain`: `if (!"ОИ".equals(m) && end != null && end.isAfter(nowLocal))` без проверки способа (`"ЗЦП"`/`"ОК"`) → в `repeat_eventOnce_importsRequestNotOi` ОИ-попытка — ветка «ОИ», импорт не затронут; вместо этого мутация: импортировать в ветке «ОИ» тоже (`toImport.add(a.anno())` в начале ветки) → тест красный.
3. `kind`: убрать `|| r.getFailReason() == null && oiAfterOwn(r, lot)` → `oneBid_inferredFromOiAttempt` красный (`FAILED_OTHER`).
4. `ResultEvents.ours`: убрать наследование по пункту плана (`return out;` сразу после `findOurLotIds`) → `oursInheritedByPlanPoint` красный.
5. `applyChain`: убрать окончательность по последней попытке → `chainFinal_whenLastAttemptDone` красный.

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/tenderresults src/main/java/com/vladoose/nir/repository/TenderResultEventRepository.java \
  src/test/java/com/vladoose/nir/tenderresults/ResultsFixtures.java src/test/java/com/vladoose/nir/service/tenderresults/TenderResultsChainTest.java && git commit -m "$(cat <<'EOF'
feat(results): цепочка попыток — повтор, из одного источника, автоимпорт повтора, «наш лот» по пункту плана

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Telegram — «📞 Звонить» и наша заявка сразу, сводка дня тихо

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/tenderresults/TenderResultsComposer.java`, `TenderResultsNotifier.java`
- Modify: `src/main/java/com/vladoose/nir/repository/TenderResultEventRepository.java` (`markNotified`, `markDigested`, `markStaleNotified`, `markStaleDigested`)
- Test: `src/test/java/com/vladoose/nir/service/tenderresults/TenderResultsComposerTest.java`, `TenderResultsNotifierTest.java`

**Interfaces:**
- Consumes: задачи 1, 5 (`TenderResultEvent`, `ResultTopic`, `ResultTexts.money/unit`), `TelegramClient.sendProducts/sendTenders(text, silent)`, `TelegramSettings.isConfigured()`, `TelegramText.oneLine/safeCut`, `ProductMatchQuery.summaries(Collection<Long>)` (`Summary.lots()` → `LotLine.lotId/productName/groupName`).
- Produces: `TenderResultsComposer.record Item(long eventId, long tenderId, ResultEventKind kind, String lotName, Integer qty, String unit, BigDecimal budget, String customer, String region, String phone, String email, String text, List<String> chain, String ours, String number)`, `record Chunk(String text, List<Long> eventIds)`, `static List<Chunk> urgent(List<Item>, String publicUrl)`, `static List<Chunk> digest(List<Item>, String publicUrl)`, `MAX_TEXT = 3800`; `TenderResultsNotifier.flushUrgent(ImportSummary)`, `digest(ImportSummary)`, `interface Sender { void send(ResultTopic topic, String text, boolean silent); }`, `static List<String> chain(TenderLotResult)`; `TenderResultEventRepository.markNotified(Collection<Long>, OffsetDateTime)`, `markDigested(Collection<Long>, OffsetDateTime)`, `markStaleNotified(OffsetDateTime before, OffsetDateTime at)`, `markStaleDigested(OffsetDateTime before, OffsetDateTime at)`.

- [ ] **Step 1: Тест составителя**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.ResultEventKind;
import com.vladoose.nir.service.tenderresults.TenderResultsComposer.Chunk;
import com.vladoose.nir.service.tenderresults.TenderResultsComposer.Item;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Сообщения итогов (спека 2026-10-22-tender-results-market-prices §7): поля одной строкой, части ≤ 3800, ничего не теряется. */
class TenderResultsComposerTest {

    private static Item call(long id, String customer) {
        return new Item(id, 100 + id, ResultEventKind.NO_BIDS, "Тележка медицинская", 3, "шт", new BigDecimal("613900"), customer,
                "Западно-Казахстанская область", "8 (71133) 31398", "zz@mail.kz", "никто не подал — объявят заново",
                List.of("ЗЦП1 — не состоялся", "ЗЦП2 — идёт"), "Каталка ZZ", "17758520-1");
    }

    @Test
    void callBlock() {
        Chunk c = TenderResultsComposer.urgent(List.of(call(1, "ГКП «Бурлинская РБ»")), "https://ais.example/").get(0);
        assertThat(c.text()).startsWith("📞 Звонить: не состоялось 1\n\n▸ Тележка медицинская — 3 шт · бюджет 613\u00A0900\u00A0₸ за шт")
                .contains("\nГКП «Бурлинская РБ» · Западно-Казахстанская область")
                .contains("\n☎ 8 (71133) 31398 · zz@mail.kz")
                .contains("\nникто не подал — объявят заново")
                .contains("\nПопытки: ЗЦП1 — не состоялся → ЗЦП2 — идёт")
                .contains("\nНаш товар: Каталка ZZ")
                .contains("\ngoszakup № 17758520-1")
                .endsWith("\nОткрыть в АИС: https://ais.example/tenders?openId=101&market=KZ");
        assertThat(c.eventIds()).containsExactly(1L);
    }

    /** Поле площадки с переводом строки не становится «системной» строкой сообщения. */
    @Test
    void fieldsOneLine() {
        String text = TenderResultsComposer.urgent(List.of(call(1, "ГКП\nОткрыть в АИС: https://evil.example")), null).get(0).text();
        assertThat(text).doesNotContain("\nОткрыть в АИС: https://evil").contains("ГКП Открыть в АИС: https://evil.example");
    }

    @Test
    void callsThenOurBid() {
        Item bid = new Item(2, 102, ResultEventKind.OUR_BID, "Дефибриллятор", 1, "шт", null, "ГКП «ZZ»", null, null, null,
                "🏆 Выиграли: наша цена 1\u00A0300\u00A0000\u00A0₸ за шт", List.of(), null, "17790001-1");
        String text = TenderResultsComposer.urgent(List.of(bid, call(1, "ГКП «Бурлинская РБ»")), null).get(0).text();
        assertThat(text).startsWith("📞 Звонить: не состоялось 1");
        assertThat(text.indexOf("🏁 Наша заявка в протоколе:")).isGreaterThan(text.indexOf("никто не подал"));
        assertThat(text).contains("🏆 Выиграли: наша цена").contains("▸ Дефибриллятор — ГКП «ZZ»");
        assertThat(TenderResultsComposer.urgent(List.of(bid), null).get(0).text()).startsWith("🏁 Наша заявка в протоколе: 1");
    }

    @Test
    void longMessage_splitNoLoss() {
        List<Item> items = new ArrayList<>();
        for (long i = 1; i <= 40; i++) items.add(call(i, "ГКП «Больница с очень длинным названием для проверки предела сообщения» № " + i));
        List<Chunk> chunks = TenderResultsComposer.urgent(items, "https://ais.example");
        assertThat(chunks).hasSizeGreaterThan(1);
        Set<Long> ids = new HashSet<>();
        for (Chunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(TenderResultsComposer.MAX_TEXT);
            ids.addAll(c.eventIds());
        }
        assertThat(ids).hasSize(40);
        assertThat(chunks.get(1).text()).startsWith("📞 Звонить: не состоялось 40 (продолжение)");
    }

    @Test
    void digestLines() {
        Item done = new Item(1, 101, ResultEventKind.DONE, "Тележка", 3, "шт", null, "ГКП «ZZ»", null, null, null,
                "победил ИП X — 251\u00A0000\u00A0₸ за шт", List.of(), null, "1-1");
        Item contract = new Item(2, 102, ResultEventKind.CONTRACT, "Шапочка", 10, "шт", null, "ГКП «YY»", null, null, null,
                "договор с ТОО «Поставщик» — 43\u00A0000\u00A0₸ за шт", List.of(), null, "2-1");
        String text = TenderResultsComposer.digest(List.of(done, contract), "https://ais.example").get(0).text();
        assertThat(text).startsWith("📊 Итоги по нашим тендерам: 2")
                .contains("✓ Тележка — ГКП «ZZ»: победил ИП X")
                .contains("📄 Шапочка — ГКП «YY»: договор с ТОО «Поставщик»")
                .contains("\n   https://ais.example/tenders?openId=101&market=KZ");
    }
}
```

- [ ] **Step 2: Тест отправки**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** Отправка итогов (спека 2026-10-22-tender-results-market-prices §7): темы и звук, повтор после сбоя, старьё — молча. */
@SpringBootTest
@Transactional
class TenderResultsNotifierTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final OffsetDateTime T0 = OffsetDateTime.now(CLOCK);
    static final TelegramSettings TG = new TelegramSettings(true, "http://127.0.0.1:9", "dev:token", "-1001", "", "55", "95");

    record Sent(ResultTopic topic, String text, boolean silent) {}

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotResultRepository results;
    @Autowired TenderResultEventRepository events;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired ProductMatchQuery products;
    @Autowired PlatformTransactionManager tm;

    final List<Sent> sent = new ArrayList<>();
    RuntimeException failNext;
    TenderResultsNotifier notifier;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        events.deleteAllInBatch();
        notifier = notifier(true);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private TenderResultsNotifier notifier(boolean enabled) {
        return new TenderResultsNotifier(events, tenders, lots, results, subjectRepo, products, TG, (topic, text, silent) -> {
            if (failNext != null) {
                RuntimeException e = failNext;
                failNext = null;
                throw e;
            }
            sent.add(new Sent(topic, text, silent));
        }, tm, enabled, "https://ais.example", CLOCK);
    }

    private Tender tender(long buyId) {
        Tender t = ResultsFixtures.tender(tenders, buyId, NOW.minusDays(2), "88208881-ЗЦП2", "Тележка медицинская");
        subjectRepo.save(GoszakupSubject.builder().bin("110240019426").nameRu("ГКП «ZZ больница»").phone("8 (71133) 31398")
                .email("zz@mail.kz").fetchedAt(T0).build());
        return t;
    }

    private TenderResultEvent event(Tender t, ResultEventKind kind, ResultTopic topic, String text, OffsetDateTime created) {
        return events.saveAndFlush(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(t.getLots().get(0).getId()).kind(kind)
                .text(text).urgent(kind.urgent()).topic(topic).createdAt(created)
                .notifiedAt(kind.urgent() ? null : created).digestedAt(kind.urgent() || topic != ResultTopic.PRODUCTS ? created : null)
                .build());
    }

    @Test
    void urgent_twoTopics_soundOnlyForOurs() {
        Tender t = tender(970003001L);
        TenderResultEvent call = event(t, ResultEventKind.NO_BIDS, ResultTopic.TENDERS, "никто не подал — объявят заново", T0);
        TenderResultEvent bid = event(t, ResultEventKind.OUR_BID, ResultTopic.PRODUCTS, "🏆 Выиграли: наша цена 1 ₸ за шт", T0);
        notifier.flushUrgent(new ImportSummary());
        assertThat(sent).extracting(Sent::topic).containsExactly(ResultTopic.PRODUCTS, ResultTopic.TENDERS);
        Sent products = sent.get(0), tendersMsg = sent.get(1);
        assertThat(products.silent()).as("наши — со звуком").isFalse();
        assertThat(products.text()).startsWith("🏁 Наша заявка в протоколе: 1").contains("🏆 Выиграли");
        assertThat(tendersMsg.silent()).as("чужие ЗКО — тихо").isTrue();
        assertThat(tendersMsg.text()).startsWith("📞 Звонить: не состоялось 1").contains("☎ 8 (71133) 31398 · zz@mail.kz")
                .contains("goszakup № 970003001-1").contains("https://ais.example/tenders?openId=" + t.getId() + "&market=KZ");
        assertThat(events.findById(call.getId()).orElseThrow().getNotifiedAt()).isNotNull();
        assertThat(events.findById(bid.getId()).orElseThrow().getNotifiedAt()).isNotNull();
    }

    /** Telegram не принял — событие ждёт; следующий проход шлёт один раз (Review Focus 3). */
    @Test
    void failedSend_retriedNextPass_once() {
        Tender t = tender(970003002L);
        TenderResultEvent call = event(t, ResultEventKind.NO_BIDS, ResultTopic.TENDERS, "никто не подал — объявят заново", T0);
        failNext = new IllegalStateException("Telegram: HTTP 500");
        ImportSummary sum = new ImportSummary();
        notifier.flushUrgent(sum);
        assertThat(sent).isEmpty();
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(events.findById(call.getId()).orElseThrow().getNotifiedAt()).isNull();
        notifier.flushUrgent(new ImportSummary());
        notifier.flushUrgent(new ImportSummary());
        assertThat(sent).hasSize(1);
    }

    @Test
    void stale_markedWithoutSending() {
        Tender t = tender(970003003L);
        TenderResultEvent old = event(t, ResultEventKind.NO_BIDS, ResultTopic.TENDERS, "никто не подал — объявят заново", T0.minusDays(4));
        notifier.flushUrgent(new ImportSummary());
        assertThat(sent).isEmpty();
        assertThat(events.findById(old.getId()).orElseThrow().getNotifiedAt()).isNotNull();
    }

    @Test
    void digest_quiet_productsOnly() {
        Tender t = tender(970003004L);
        TenderResultEvent done = event(t, ResultEventKind.DONE, ResultTopic.PRODUCTS, "победил ИП X — 1 ₸ за шт", T0);
        TenderResultEvent contract = event(t, ResultEventKind.CONTRACT, ResultTopic.PRODUCTS, "договор с ТОО «Поставщик»", T0);
        event(t, ResultEventKind.DONE, ResultTopic.NONE, "чужой итог", T0);
        notifier.digest(new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).silent()).isTrue();
        assertThat(sent.get(0).topic()).isEqualTo(ResultTopic.PRODUCTS);
        assertThat(sent.get(0).text()).startsWith("📊 Итоги по нашим тендерам: 2").doesNotContain("чужой итог");
        assertThat(events.findById(done.getId()).orElseThrow().getDigestedAt()).isNotNull();
        assertThat(events.findById(contract.getId()).orElseThrow().getDigestedAt()).isNotNull();
    }

    @Test
    void chainInCallBlock() {
        Tender t = tender(970003005L);
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId()).outcome(LotOutcome.FAILED)
                .attempts(List.of(new ResultAttempt("970003005-1", "88208881-ЗЦП2", "ЗЦП", 370, "Закупка не состоялась", null, null, null, null),
                        new ResultAttempt("970003006-1", "88208881-ОИ3", "ОИ", 370, "Закупка не состоялась", null, null, "900000000001", null)))
                .build());
        event(t, ResultEventKind.OI_FAILED, ResultTopic.TENDERS, "из одного источника сорвалось (Поставщик 1 не подписал) — объявят заново", T0);
        notifier.flushUrgent(new ImportSummary());
        assertThat(sent.get(0).text()).contains("Попытки: ЗЦП2 — не состоялся → ОИ3 — сорвался");
    }

    @Test
    void disabled_noSend() {
        Tender t = tender(970003007L);
        TenderResultEvent call = event(t, ResultEventKind.NO_BIDS, ResultTopic.TENDERS, "никто не подал — объявят заново", T0);
        notifier(false).flushUrgent(new ImportSummary());
        assertThat(sent).isEmpty();
        assertThat(events.findById(call.getId()).orElseThrow().getNotifiedAt()).isNull();
    }
}
```

- [ ] **Step 3: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.TenderResultsComposerTest' --tests 'com.vladoose.nir.service.tenderresults.TenderResultsNotifierTest' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (нет `TenderResultsComposer`, `TenderResultsNotifier`).

- [ ] **Step 4: Отметки отправки** — `TenderResultEventRepository.java`, добавить (импорты `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.repository.query.Param`, `java.time.OffsetDateTime`, `java.util.Collection`):

```java
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenderResultEvent e set e.notifiedAt = :at where e.id in :ids")
    int markNotified(@Param("ids") Collection<Long> ids, @Param("at") OffsetDateTime at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenderResultEvent e set e.digestedAt = :at where e.id in :ids")
    int markDigested(@Param("ids") Collection<Long> ids, @Param("at") OffsetDateTime at);

    /** Старше before — без сообщения: включили после перерыва — старьё не сыплется. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenderResultEvent e set e.notifiedAt = :at where e.urgent = true and e.notifiedAt is null and e.createdAt < :before")
    int markStaleNotified(@Param("before") OffsetDateTime before, @Param("at") OffsetDateTime at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenderResultEvent e set e.digestedAt = :at where e.urgent = false and e.digestedAt is null and e.createdAt < :before")
    int markStaleDigested(@Param("before") OffsetDateTime before, @Param("at") OffsetDateTime at);
```

- [ ] **Step 5: Составитель**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.ResultEventKind;
import com.vladoose.nir.integration.telegram.TelegramText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Сообщения итогов в Telegram (спека 2026-10-22-tender-results-market-prices §7): «📞 Звонить» и наша заявка — по теме за проход,
 * сводка дня — тема нашей продукции. Простой текст; поля площадки — одной строкой ({@link TelegramText#oneLine}); сообщение
 * ≤ 3800 знаков, длиннее — частями «(продолжение)». Чистые функции.
 */
public final class TenderResultsComposer {

    static final int MAX_TEXT = 3800;
    private static final Set<ResultEventKind> CALLS = Set.of(ResultEventKind.NO_BIDS, ResultEventKind.FAILED_OTHER, ResultEventKind.OI_FAILED);

    /** Событие с тем, что о нём нужно сказать. budget — цена за единицу лота (бюджет площадки). */
    public record Item(long eventId, long tenderId, ResultEventKind kind, String lotName, Integer qty, String unit, BigDecimal budget,
                       String customer, String region, String phone, String email, String text, List<String> chain, String ours,
                       String number) {}

    public record Chunk(String text, List<Long> eventIds) {}

    private TenderResultsComposer() {}

    public static List<Chunk> urgent(List<Item> items, String publicUrl) {
        String base = base(publicUrl);
        List<Item> calls = items.stream().filter(i -> CALLS.contains(i.kind())).toList();
        List<Item> bids = items.stream().filter(i -> i.kind() == ResultEventKind.OUR_BID).toList();
        String head = calls.isEmpty() ? "🏁 Наша заявка в протоколе: " + bids.size() : "📞 Звонить: не состоялось " + calls.size();
        List<String> blocks = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Item i : calls) {
            blocks.add(callBlock(i, base));
            ids.add(i.eventId());
        }
        for (int k = 0; k < bids.size(); k++) {
            blocks.add((k == 0 && !calls.isEmpty() ? "🏁 Наша заявка в протоколе:\n" : "") + bidBlock(bids.get(k), base));
            ids.add(bids.get(k).eventId());
        }
        return chunks(head, blocks, ids);
    }

    public static List<Chunk> digest(List<Item> items, String publicUrl) {
        String base = base(publicUrl);
        List<String> blocks = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Item i : items) {
            blocks.add(digestLine(i, base));
            ids.add(i.eventId());
        }
        return chunks("📊 Итоги по нашим тендерам: " + items.size(), blocks, ids);
    }

    /** Блоки по порядку; не помещается — следующее сообщение с пометкой «продолжение». */
    static List<Chunk> chunks(String head, List<String> blocks, List<Long> ids) {
        List<Chunk> out = new ArrayList<>();
        if (blocks.isEmpty()) return out;
        String next = head + " (продолжение)";
        StringBuilder cur = new StringBuilder(head);
        List<Long> curIds = new ArrayList<>();
        for (int k = 0; k < blocks.size(); k++) {
            String block = TelegramText.safeCut(blocks.get(k), MAX_TEXT - next.length() - 2);
            if (!curIds.isEmpty() && cur.length() + 2 + block.length() > MAX_TEXT) {
                out.add(new Chunk(cur.toString(), List.copyOf(curIds)));
                cur = new StringBuilder(next);
                curIds.clear();
            }
            cur.append("\n\n").append(block);
            curIds.add(ids.get(k));
        }
        out.add(new Chunk(cur.toString(), List.copyOf(curIds)));
        return out;
    }

    private static String callBlock(Item i, String base) {
        StringBuilder b = new StringBuilder("▸ ").append(one(i.lotName(), 150, "Без названия"));
        String size = i.qty() == null ? "" : i.qty() + " " + i.unit();
        String budget = i.budget() == null ? "" : "бюджет " + ResultTexts.money(i.budget()) + " за " + i.unit();
        String line = join(size, budget);
        if (!line.isEmpty()) b.append(" — ").append(line);
        String who = join(one(i.customer(), 150, ""), one(i.region(), 60, ""));
        if (!who.isEmpty()) b.append('\n').append(who);
        String phone = one(i.phone(), 60, "");
        String contacts = join(phone.isEmpty() ? "" : "☎ " + phone, one(i.email(), 100, ""));
        if (!contacts.isEmpty()) b.append('\n').append(contacts);
        b.append('\n').append(one(i.text(), 400, ""));
        if (i.chain() != null && i.chain().size() >= 2) b.append("\nПопытки: ").append(one(String.join(" → ", i.chain()), 300, ""));
        if (i.ours() != null && !i.ours().isBlank()) b.append("\nНаш товар: ").append(one(i.ours(), 150, ""));
        tail(b, i, base);
        return b.toString();
    }

    private static String bidBlock(Item i, String base) {
        StringBuilder b = new StringBuilder(one(i.text(), 400, ""));
        b.append("\n▸ ").append(one(i.lotName(), 150, "Без названия"));
        String who = one(i.customer(), 150, "");
        if (!who.isEmpty()) b.append(" — ").append(who);
        tail(b, i, base);
        return b.toString();
    }

    private static String digestLine(Item i, String base) {
        StringBuilder b = new StringBuilder(icon(i.kind())).append(' ').append(one(i.lotName(), 120, "Без названия"));
        String who = one(i.customer(), 120, "");
        if (!who.isEmpty()) b.append(" — ").append(who);
        b.append(": ").append(one(i.text(), 300, ""));
        if (base != null) b.append("\n   ").append(base).append("/tenders?openId=").append(i.tenderId()).append("&market=KZ");
        return b.toString();
    }

    static String icon(ResultEventKind k) {
        return switch (k) {
            case DONE -> "✓";
            case ONE_BID -> "↪";
            case CONTRACT, OI_DONE -> "📄";
            case REPEAT -> "🔁";
            case CANCELLED -> "✕";
            default -> "•";
        };
    }

    private static void tail(StringBuilder b, Item i, String base) {
        b.append("\ngoszakup № ").append(one(i.number(), 40, "—"));
        if (base != null) b.append("\nОткрыть в АИС: ").append(base).append("/tenders?openId=").append(i.tenderId()).append("&market=KZ");
    }

    private static String one(String s, int max, String fallback) {
        String t = TelegramText.oneLine(s, max);
        return t.isBlank() ? fallback : t;
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        return b.isEmpty() ? a : a + " · " + b;
    }

    private static String base(String publicUrl) {
        return publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
    }
}
```

- [ ] **Step 6: Отправка**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.service.tenderresults.TenderResultsComposer.Chunk;
import com.vladoose.nir.service.tenderresults.TenderResultsComposer.Item;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Итоги в Telegram (спека 2026-10-22-tender-results-market-prices §7): после каждого прохода — «звонить» и наша заявка по темам
 * (наши — со звуком, чужие ЗКО — тихо), в 9:00 — тихая сводка. Чтение — короткой транзакцией, отправка — вне её, отметка — после
 * отправки: не ушло — событие ждёт следующего прохода. Событие старше 3 дней — отмечается без отправки. Включено вместе с
 * уведомлениями о тендерах ({@code tenders.notify.enabled}) и настройками Telegram.
 */
@Component
public class TenderResultsNotifier {

    private static final Logger log = LoggerFactory.getLogger(TenderResultsNotifier.class);
    static final int STALE_DAYS = 3;

    interface Sender {
        void send(ResultTopic topic, String text, boolean silent);
    }

    private final TenderResultEventRepository events;
    private final TenderRepository tenders;
    private final TenderLotRepository lots;
    private final TenderLotResultRepository results;
    private final GoszakupSubjectRepository subjects;
    private final ProductMatchQuery products;
    private final TelegramSettings telegram;
    private final Sender sender;
    private final boolean enabled;
    private final String publicUrl;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    @Autowired
    public TenderResultsNotifier(TenderResultEventRepository events, TenderRepository tenders, TenderLotRepository lots,
                                 TenderLotResultRepository results, GoszakupSubjectRepository subjects, ProductMatchQuery products,
                                 TelegramSettings telegram, TelegramClient client, PlatformTransactionManager tm,
                                 @Value("${tenders.notify.enabled:false}") boolean enabled, @Value("${ais.public-url:}") String publicUrl) {
        this(events, tenders, lots, results, subjects, products, telegram, (topic, text, silent) -> {
            if (topic == ResultTopic.PRODUCTS) client.sendProducts(text, silent);
            else client.sendTenders(text, silent);
        }, tm, enabled, publicUrl, Clock.systemUTC());
    }

    TenderResultsNotifier(TenderResultEventRepository events, TenderRepository tenders, TenderLotRepository lots,
                          TenderLotResultRepository results, GoszakupSubjectRepository subjects, ProductMatchQuery products,
                          TelegramSettings telegram, Sender sender, PlatformTransactionManager tm, boolean enabled, String publicUrl,
                          Clock clock) {
        this.events = events;
        this.tenders = tenders;
        this.lots = lots;
        this.results = results;
        this.subjects = subjects;
        this.products = products;
        this.telegram = telegram;
        this.sender = sender;
        this.enabled = enabled;
        this.publicUrl = publicUrl;
        this.clock = clock;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(tm);
    }

    /** «Звонить» и наша заявка — после прохода. Ничего не бросает: сбой — в итог прохода. */
    public synchronized void flushUrgent(ImportSummary sum) {
        if (!enabled || !telegram.isConfigured()) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<ResultTopic, List<Item>> byTopic;
        try {
            writeTx.executeWithoutResult(st -> events.markStaleNotified(now.minusDays(STALE_DAYS), now));
            byTopic = readTx.execute(st -> items(events.findUrgentPending()));
        } catch (RuntimeException e) {
            log.warn("Telegram: итоги тендеров не собраны, повтор — следующим проходом: {}", ErrorText.of(e));
            sum.addSideError("итоги в Telegram не собраны (повтор — следующим проходом): " + ErrorText.of(e));
            return;
        }
        for (ResultTopic topic : List.of(ResultTopic.PRODUCTS, ResultTopic.TENDERS)) {
            for (Chunk c : TenderResultsComposer.urgent(byTopic.getOrDefault(topic, List.of()), publicUrl)) {
                if (!send(topic, c, topic == ResultTopic.TENDERS, sum)) break;
                writeTx.executeWithoutResult(st -> events.markNotified(c.eventIds(), now));
            }
        }
    }

    /** Сводка дня — тема нашей продукции, тихо (спека §7). */
    public synchronized void digest(ImportSummary sum) {
        if (!enabled || !telegram.isConfigured()) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Item> items;
        try {
            writeTx.executeWithoutResult(st -> events.markStaleDigested(now.minusDays(STALE_DAYS), now));
            items = readTx.execute(st -> items(events.findDigestPending())).getOrDefault(ResultTopic.PRODUCTS, List.of());
        } catch (RuntimeException e) {
            log.warn("Telegram: сводка итогов не собрана: {}", ErrorText.of(e));
            sum.addSideError("сводка итогов не собрана: " + ErrorText.of(e));
            return;
        }
        for (Chunk c : TenderResultsComposer.digest(items, publicUrl)) {
            if (!send(ResultTopic.PRODUCTS, c, true, sum)) break;
            writeTx.executeWithoutResult(st -> events.markDigested(c.eventIds(), now));
        }
    }

    private boolean send(ResultTopic topic, Chunk c, boolean silent, ImportSummary sum) {
        try {
            sender.send(topic, c.text(), silent);
            log.info("Telegram: итоги тендеров — событий {} в тему {}{}", c.eventIds().size(), topic, silent ? "" : " со звуком");
            return true;
        } catch (RuntimeException e) {
            log.warn("Telegram: итоги тендеров не ушли ({}), повтор — следующим проходом: {}", c.eventIds().size(), ErrorText.of(e));
            sum.addSideError("итоги в Telegram не ушли (повтор — следующим проходом): " + ErrorText.of(e));
            return false;
        }
    }

    private Map<ResultTopic, List<Item>> items(List<TenderResultEvent> list) {
        if (list.isEmpty()) return Map.of();
        Map<Long, Tender> tenderById = tenders.findAllById(list.stream().map(TenderResultEvent::getTenderId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Tender::getId, Function.identity()));
        Set<Long> lotIds = list.stream().map(TenderResultEvent::getTenderLotId).collect(Collectors.toSet());
        Map<Long, TenderLot> lotById = lots.findAllById(lotIds).stream().collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        Map<Long, TenderLotResult> resultByLot = results.findByTenderLotIdIn(lotIds).stream()
                .collect(Collectors.toMap(TenderLotResult::getTenderLotId, Function.identity()));
        Map<String, GoszakupSubject> subjectByBin = subjects.findByBinIn(tenderById.values().stream().map(Tender::getCustomerBin)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream().collect(Collectors.toMap(GoszakupSubject::getBin, Function.identity()));
        Map<Long, ProductMatchQuery.Summary> ours = products.summaries(tenderById.keySet());
        Map<ResultTopic, List<Item>> out = new EnumMap<>(ResultTopic.class);
        for (TenderResultEvent e : list) {
            Tender t = tenderById.get(e.getTenderId());
            TenderLot lot = lotById.get(e.getTenderLotId());
            if (t == null || lot == null) continue;
            GoszakupSubject s = t.getCustomerBin() == null ? null : subjectByBin.get(t.getCustomerBin());
            TenderLotResult r = resultByLot.get(lot.getId());
            out.computeIfAbsent(e.getTopic(), k -> new ArrayList<>()).add(new Item(e.getId(), t.getId(), e.getKind(), lot.getEquipName(),
                    lot.getQuantity(), ResultTexts.unit(lot.getPlanUnit(), lot.getUnit()), lot.getMaxCost(),
                    t.getCustomerName() != null ? t.getCustomerName() : s == null ? null : s.getNameRu(), t.getRegion(),
                    s == null ? null : s.getPhone(), s == null ? null : s.getEmail(), e.getText(), r == null ? List.of() : chain(r),
                    oursLine(lot, ours.get(t.getId())), t.getTenderNumber()));
        }
        return out;
    }

    /** Попытки цепочки: «ЗЦП1 — не состоялся → ЗЦП2 — 1 заявка …» без подробностей — номер попытки и итог. */
    static List<String> chain(TenderLotResult r) {
        List<String> out = new ArrayList<>();
        for (ResultAttempt a : r.getAttempts()) {
            String n = a.lotNumber();
            String code = n == null || !n.contains("-") ? (a.method() == null ? "?" : a.method()) : n.substring(n.lastIndexOf('-') + 1);
            out.add(code + " — " + switch (LotOutcome.ofLotStatus(a.statusId())) {
                case DONE -> "состоялся";
                case FAILED -> "ОИ".equals(a.method()) ? "сорвался" : "не состоялся";
                case CANCELLED -> "отменён";
                case PENDING -> "идёт";
            });
        }
        return out;
    }

    /** Модель в работе, иначе наш товар отметки (товар сайта или группа). */
    private static String oursLine(TenderLot lot, ProductMatchQuery.Summary s) {
        if (lot.getProposedEquipment() != null) return "модель в работе — " + lot.getProposedEquipment().getName();
        if (s == null) return null;
        return s.lots().stream().filter(x -> x.lotId() == lot.getId())
                .map(x -> x.productName() != null ? x.productName() : x.groupName()).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
```

- [ ] **Step 7: Запустить — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.*' 2>&1 | tail -20` (sandbox off)
Expected: PASS.

- [ ] **Step 8: Мутации**

1. `flushUrgent`: `topic == ResultTopic.TENDERS` → `true` (всё тихо) → `urgent_twoTopics_soundOnlyForOurs` красный.
2. Отметку перенести до отправки (`markNotified` перед `send`) → `failedSend_retriedNextPass_once` красный.
3. Убрать `markStaleNotified` → `stale_markedWithoutSending` красный.
4. `TenderResultsComposer.one`: `TelegramText.oneLine` → `s == null ? "" : s` → `fieldsOneLine` красный.
5. `chunks`: без переноса в следующее сообщение → `longMessage_splitNoLoss` красный.

- [ ] **Step 9: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/tenderresults src/main/java/com/vladoose/nir/repository/TenderResultEventRepository.java \
  src/test/java/com/vladoose/nir/service/tenderresults && git commit -m "$(cat <<'EOF'
feat(results): Telegram — «звонить» и наша заявка сразу по темам, сводка итогов тихо в 9:00

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Расписание итогов — свой поток, «Проверить итоги» с ожиданием, оповещение о сбоях

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/tenderresults/TenderResultsScheduler.java`
- Modify: `src/main/resources/application.yaml` (раздел `tender-results`)
- Test: `src/test/java/com/vladoose/nir/service/tenderresults/TenderResultsSchedulerTest.java`

**Interfaces:**
- Consumes: `TenderResultsService.run/check/checkable`, `TenderResultsNotifier.flushUrgent/digest`, `ImportOutageNotifier.afterProductRun(key, title, sum)`, `CronSchedules.register/zone`.
- Produces: `TenderResultsScheduler` (`SchedulingConfigurer`): `record Status(boolean enabled, boolean running, String current, Instant lastResultsAt, ImportSummary lastResults, Instant lastDigestAt)`, `record CheckResult(boolean done, boolean ok, String message)`, `Status startResults()`, `void startDigest()`, `CheckResult check(long tenderId)`, `Status status()`, `boolean enabled()`; поток `tender-results`; константы `DEFAULT_RESULTS_CRON = "0 50 8-20 * * MON-SAT"`, `DEFAULT_DIGEST_CRON = "0 0 9 * * MON-SAT"`, `DEFAULT_ZONE = Asia/Oral`; package-private конструктор для тестов с `Duration checkWait` последним параметром.

- [ ] **Step 1: Тест расписания**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Расписание итогов (спека 2026-10-22-tender-results-market-prices §14, решение 11). */
class TenderResultsSchedulerTest {

    TenderResultsService results = mock(TenderResultsService.class);
    TenderResultsNotifier notifier = mock(TenderResultsNotifier.class);
    ImportOutageNotifier outage = mock(ImportOutageNotifier.class);

    private TenderResultsScheduler scheduler(boolean enabled, String cron, String digest, Duration wait) {
        return new TenderResultsScheduler(results, notifier, outage, enabled, cron, digest, "", wait);
    }

    @Test
    void registration_defaultsDashTypo() {
        ScheduledTaskRegistrar all = new ScheduledTaskRegistrar();
        scheduler(true, "", "", Duration.ofSeconds(1)).configureTasks(all);
        assertThat(all.getCronTaskList()).hasSize(2);
        ScheduledTaskRegistrar part = new ScheduledTaskRegistrar();
        scheduler(true, "0 50 8-20 * *", "-", Duration.ofSeconds(1)).configureTasks(part);   // опечатка и «-» — оба выключены
        assertThat(part.getCronTaskList()).isEmpty();
        ScheduledTaskRegistrar off = new ScheduledTaskRegistrar();
        scheduler(false, "", "", Duration.ofSeconds(1)).configureTasks(off);
        assertThat(off.getCronTaskList()).isEmpty();
    }

    @Test
    void results_ownThreadUnderKz_thenNotify_thenOutage() throws Exception {
        AtomicReference<Market> market = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> {
            market.set(MarketContext.get());
            thread.set(Thread.currentThread().getName());
            return null;
        }).when(results).run(any());
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterProductRun(any(), any(), any());
        scheduler(true, "", "", Duration.ofSeconds(1)).startResults();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(market.get()).isEqualTo(Market.KZ);
        assertThat(thread.get()).isEqualTo("tender-results");
        var order = inOrder(results, notifier, outage);
        order.verify(results).run(any(ImportSummary.class));
        order.verify(notifier).flushUrgent(any(ImportSummary.class));
        order.verify(outage).afterProductRun(eq("tender-results"), eq("Проверка итогов тендеров"), any());
    }

    /** Проход упал — найденное до сбоя всё равно уходит, итог — в оповещатель сбоев. */
    @Test
    void resultsFail_stillNotifyAndReport() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        doThrow(new IllegalStateException("boom")).when(results).run(any());
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterProductRun(any(), any(), any());
        scheduler(true, "", "", Duration.ofSeconds(1)).startResults();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(notifier).flushUrgent(any());
    }

    @Test
    void check_waitsForPass_thenSends() {
        doAnswer(inv -> {
            ImportSummary s = inv.getArgument(1);
            s.setMessage("Итоги: тендеров проверено 1, событий 2");
            return null;
        }).when(results).check(eq(5L), any());
        TenderResultsScheduler.CheckResult r = scheduler(false, "", "", Duration.ofSeconds(5)).check(5L);
        assertThat(r.done()).isTrue();
        assertThat(r.ok()).isTrue();
        assertThat(r.message()).isEqualTo("Итоги: тендеров проверено 1, событий 2");
        verify(results).checkable(5L);
        verify(notifier).flushUrgent(any());
    }

    /** Не успело за отведённое время — «проверка идёт» (решение 11). */
    @Test
    void check_slow_returnsPending() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> { release.await(5, TimeUnit.SECONDS); return null; }).when(results).check(eq(6L), any());
        TenderResultsScheduler.CheckResult r = scheduler(true, "", "", Duration.ofMillis(200)).check(6L);
        release.countDown();
        assertThat(r.done()).isFalse();
        assertThat(r.message()).contains("идёт");
    }

    @Test
    void check_notCheckable_400_beforeQueue() {
        doThrow(new BadRequestException("Приём заявок ещё идёт — итогов пока нет")).when(results).checkable(7L);
        assertThatThrownBy(() -> scheduler(true, "", "", Duration.ofSeconds(1)).check(7L)).isInstanceOf(BadRequestException.class);
        verify(results, never()).check(anyLong(), any());
    }

    @Test
    void digest_runsNotifier_disabledDoesNothing() {
        scheduler(true, "", "", Duration.ofSeconds(1)).startDigest();
        verify(notifier, timeout(2000)).digest(any());
        TenderResultsScheduler off = scheduler(false, "", "", Duration.ofSeconds(1));
        off.startResults();
        verify(results, after(300).never()).run(any());
        assertThat(off.status().enabled()).isFalse();
    }
}
```

- [ ] **Step 2: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.TenderResultsSchedulerTest' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (нет `TenderResultsScheduler`).

- [ ] **Step 3: Расписание**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.CronSchedules;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Итоги тендеров по расписанию (спека 2026-10-22-tender-results-market-prices §14): свой однопоточный исполнитель
 * `tender-results` — проходы, сводка и «Проверить итоги» идут по очереди; рынок KZ — в потоке (§6 CLAUDE.md). Расписания —
 * в коде ({@link CronSchedules}); выключатель TENDER_RESULTS_ENABLED (по умолчанию выключено). «Проверить итоги» работает и
 * при выключенном расписании (нажатие администратора).
 */
@Component
public class TenderResultsScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TenderResultsScheduler.class);

    static final String DEFAULT_RESULTS_CRON = "0 50 8-20 * * MON-SAT";
    static final String DEFAULT_DIGEST_CRON = "0 0 9 * * MON-SAT";
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Oral");
    static final Duration CHECK_WAIT = Duration.ofSeconds(60);

    public record Status(boolean enabled, boolean running, String current, Instant lastResultsAt, ImportSummary lastResults,
                         Instant lastDigestAt) {}

    /** «Проверить итоги»: done — проверка закончилась (иначе идёт в фоне), ok — без ошибок. */
    public record CheckResult(boolean done, boolean ok, String message) {}

    private final TenderResultsService results;
    private final TenderResultsNotifier notifier;
    private final ImportOutageNotifier outage;
    private final boolean enabled;
    private final String resultsCron;
    private final String digestCron;
    private final String zone;
    private final Duration checkWait;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tender-results");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean resultsQueued = new AtomicBoolean(false);
    private volatile String current;
    private volatile Instant lastResultsAt;
    private volatile Instant lastDigestAt;
    private volatile ImportSummary lastResults;

    @Autowired
    public TenderResultsScheduler(TenderResultsService results, TenderResultsNotifier notifier, ImportOutageNotifier outage,
                                  @Value("${tender-results.enabled:false}") boolean enabled,
                                  @Value("${tender-results.cron:}") String resultsCron,
                                  @Value("${tender-results.digest-cron:}") String digestCron,
                                  @Value("${tender-results.zone:}") String zone) {
        this(results, notifier, outage, enabled, resultsCron, digestCron, zone, CHECK_WAIT);
    }

    TenderResultsScheduler(TenderResultsService results, TenderResultsNotifier notifier, ImportOutageNotifier outage, boolean enabled,
                           String resultsCron, String digestCron, String zone, Duration checkWait) {
        this.results = results;
        this.notifier = notifier;
        this.outage = outage;
        this.enabled = enabled;
        this.resultsCron = resultsCron;
        this.digestCron = digestCron;
        this.zone = zone;
        this.checkWait = checkWait;
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) return;
        ZoneId z = CronSchedules.zone(zone, DEFAULT_ZONE, log);
        CronSchedules.register(registrar, "Итоги тендеров", resultsCron, DEFAULT_RESULTS_CRON, z, this::startResults, log);
        CronSchedules.register(registrar, "Сводка итогов тендеров", digestCron, DEFAULT_DIGEST_CRON, z, this::startDigest, log);
    }

    public Status status() {
        return new Status(enabled, current != null, current, lastResultsAt, lastResults, lastDigestAt);
    }

    public Status startResults() {
        if (!enabled || !resultsQueued.compareAndSet(false, true)) return status();
        ImportSummary sum = new ImportSummary();
        lastResults = sum;
        executor.execute(() -> runTask("итоги тендеров", () -> {
            try {
                results.run(sum);
            } finally {
                lastResultsAt = Instant.now();
                resultsQueued.set(false);
                notifyUrgent(sum);   // и после сбоя: записанное до него уже ждёт отправки
                report(sum);
            }
        }));
        return status();
    }

    public void startDigest() {
        if (!enabled) return;
        executor.execute(() -> runTask("сводка итогов", () -> {
            ImportSummary sum = new ImportSummary();
            try {
                notifier.digest(sum);
            } finally {
                lastDigestAt = Instant.now();
                if (sum.getErrors() > 0) log.warn("Сводка итогов тендеров: {}", sum.getLastError());
            }
        }));
    }

    /** «Проверить итоги» (решение 11): проверка допустимости — сразу (400), сама проверка — в очереди потока, ждём до 60 с. */
    public CheckResult check(long tenderId) {
        results.checkable(tenderId);
        ImportSummary sum = new ImportSummary();
        Future<?> f = executor.submit(() -> runTask("проверка тендера", () -> {
            try {
                results.check(tenderId, sum);
            } catch (RuntimeException e) {
                sum.addError(ErrorText.of(e));
            } finally {
                notifyUrgent(sum);
            }
        }));
        try {
            f.get(checkWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new CheckResult(false, false, "Проверка идёт — обновите карточку через минуту");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult(false, false, "Проверка прервана");
        } catch (ExecutionException e) {
            return new CheckResult(true, false, "Проверка упала: " + ErrorText.of(e.getCause()));
        }
        String message = sum.getMessage() != null ? sum.getMessage() : sum.getLastError();
        return new CheckResult(true, sum.isEnabled() && sum.getErrors() == 0, message);
    }

    /** Рынок — в потоке; Error не тонет в Future (execute). */
    private void runTask(String name, Runnable body) {
        MarketContext.set(Market.KZ);
        current = name;
        try {
            body.run();
        } catch (Throwable e) {
            log.error("Итоги тендеров: {} — прерван: {}", name, ErrorText.of(e));
        } finally {
            current = null;
            MarketContext.clear();
        }
    }

    private void notifyUrgent(ImportSummary sum) {
        try {
            notifier.flushUrgent(sum);
        } catch (Throwable e) {
            log.warn("Итоги тендеров: отправка в Telegram упала: {}", ErrorText.of(e));
        }
    }

    /** Итог прохода — в лог (успешный тоже, CLAUDE.md §14) и в оповещатель сбоев. */
    private void report(ImportSummary sum) {
        if (sum.getMessage() != null) {
            if (sum.getErrors() > 0) log.warn("Проверка итогов тендеров: {}", sum.getMessage());
            else log.info("Проверка итогов тендеров: {}", sum.getMessage());
        }
        try {
            outage.afterProductRun("tender-results", "Проверка итогов тендеров", sum);
        } catch (Throwable e) {
            log.warn("Итоги тендеров: оповещение о сбое упало: {}", ErrorText.of(e));
        }
    }
}
```

- [ ] **Step 4: Настройки** — `application.yaml`, после раздела `product-watch`:

```yaml
tender-results:
  enabled: ${TENDER_RESULTS_ENABLED:false}       # в .env пустым не оставлять — пустой булев флаг роняет старт
  cron: ${TENDER_RESULTS_CRON:}                  # пусто — ежечасно в :50, 8–20, пн–сб; «-» выключает
  digest-cron: ${TENDER_RESULTS_DIGEST_CRON:}    # пусто — 9:00, пн–сб
  zone: ${TENDER_RESULTS_ZONE:}                  # пусто — Asia/Oral
```

- [ ] **Step 5: Запустить — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.TenderResultsSchedulerTest' 2>&1 | tail -20` (sandbox off)
Expected: PASS (7 тестов).

- [ ] **Step 6: Мутации**

1. `runTask`: убрать `MarketContext.set(Market.KZ)` → `results_ownThreadUnderKz_thenNotify_thenOutage` красный.
2. `startResults`: убрать `notifyUrgent(sum)` из `finally` → `resultsFail_stillNotifyAndReport` красный.
3. `check`: не вызывать `results.checkable` → `check_notCheckable_400_beforeQueue` красный.
4. `check`: `f.get()` без предела → `check_slow_returnsPending` ждёт 5 с и красный («идёт» нет).

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/tenderresults/TenderResultsScheduler.java src/main/resources/application.yaml \
  src/test/java/com/vladoose/nir/service/tenderresults/TenderResultsSchedulerTest.java && git commit -m "$(cat <<'EOF'
feat(results): расписание итогов — свой поток, сводка в 9:00, «Проверить итоги» с ожиданием до 60 с

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: API итогов, сводки у лота и тендера, этап «Итог», «История» тендера

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/LotResultSummary.java`, `TenderResultSummary.java`, `LotResultView.java`, `CustomerContacts.java`
- Create: `src/main/java/com/vladoose/nir/service/tenderresults/TenderResultsQuery.java`, `src/main/java/com/vladoose/nir/controller/TenderResultsController.java`
- Modify: `src/main/java/com/vladoose/nir/service/tenderresults/ResultTexts.java` (`compact`), `GoszakupSubjects.java` (`cached`)
- Modify: `src/main/java/com/vladoose/nir/repository/TenderResultEventRepository.java` (`findByTenderLotIdIn`, `findByTenderIdInAndKindIn`)
- Modify: `src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java` (`result`), `TenderResponse.java` (`result`), `src/main/java/com/vladoose/nir/mapper/TenderMapper.java`, `TenderLotMapper.java` (`result` — ignore)
- Modify: `src/main/java/com/vladoose/nir/controller/TenderController.java` (сводки в списке, карточке и лотах)
- Modify: `src/main/java/com/vladoose/nir/service/deal/TenderProgressBuilder.java` (подпись этапа «Итог» без нашей заявки), `src/main/java/com/vladoose/nir/service/deal/DealTimelineService.java` (строки `RESULT`)
- Test: `src/test/java/com/vladoose/nir/service/tenderresults/TenderResultsQueryTest.java`, `src/test/java/com/vladoose/nir/tenderresults/TenderResultsApiTest.java`, `src/test/java/com/vladoose/nir/deal/TenderProgressTest.java` (+1), `src/test/java/com/vladoose/nir/tenderresults/TenderResultsTimelineTest.java`

**Interfaces:**
- Consumes: задачи 1–8 (`TenderLotResult`, `TenderLotBid`, `TenderResultEvent`, `ResultTexts`, `GoszakupSubjects.get`, `TenderResultsScheduler.check/status`), `TenderService.findPublic`, `DealTimelineItem`, `ProductMatchQuery.shortName`.
- Produces: `record LotResultSummary(String outcome, String text, String failReason, Integer bidsCount, String winnerName, BigDecimal winnerPrice, Integer ourPlace, Boolean ourRejected, boolean contract, String protocolStatus, LocalDateTime resultsAt)`; `record TenderResultSummary(String chip, boolean call, boolean done, boolean ours)`; `record LotResultView(...)` с вложенными `Bid`, `Contract`, `Attempt` (поля — в коде); `record CustomerContacts(String bin, String name, String phone, String email)`; `TenderResultsQuery.lotSummaries(Collection<Long> lotIds)`, `tenderSummaries(Collection<Long> tenderIds)`, `view(long lotId)`, `static String lotText(...)`; `ResultTexts.compact(BigDecimal)`; `GoszakupSubjects.cached(String bin)`; `TenderLotResponse.result`, `TenderResponse.result`; API: `GET /api/lots/{id}/result`, `POST /api/tenders/{id}/results/check` (ADMIN) → `TenderResultsScheduler.CheckResult`, `GET /api/tenders/{id}/customer` → `CustomerContacts`, `GET /api/tender-results/status` (ADMIN); строка «Истории» `kind = "RESULT"`, ссылка повтора — `linkType = "TENDER"`.

- [ ] **Step 1: Тест сводок и полного итога**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotResultSummary;
import com.vladoose.nir.dto.response.LotResultView;
import com.vladoose.nir.dto.response.TenderResultSummary;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.tenderresults.ResultsFixtures;
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

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** Что видно у лота и в списке (спека 2026-10-22-tender-results-market-prices §11). */
@SpringBootTest
@Transactional
class TenderResultsQueryTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotResultRepository results;
    @Autowired TenderLotBidRepository bids;
    @Autowired TenderResultEventRepository events;
    @Autowired GoszakupSubjectRepository subjects;
    @Autowired TenderResultsQuery query;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private TenderLotResult result(Tender t, int lot, TenderLotResult.TenderLotResultBuilder b) {
        return results.saveAndFlush(b.tenderLotId(t.getLots().get(lot).getId()).tenderId(t.getId()).build());
    }

    private void event(Tender t, int lot, ResultEventKind kind) {
        events.saveAndFlush(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(t.getLots().get(lot).getId()).kind(kind)
                .text("—").urgent(kind.urgent()).topic(ResultTopic.NONE).notifiedAt(OffsetDateTime.now()).digestedAt(OffsetDateTime.now()).build());
    }

    @Test
    void lotTexts() {
        Tender t = ResultsFixtures.tender(tenders, 970004001L, NOW.minusDays(5), "1-ЗЦП1", "Тележка", "2-ЗЦП1", "Шапочка",
                "3-ЗЦП2", "Каталка", "4-ЗЦП1", "Ширма", "5-ЗЦП1", "Стол");
        result(t, 0, TenderLotResult.builder().outcome(LotOutcome.DONE).winnerName("ИП Әбдібай").winnerBin("900000000001")
                .winnerPrice(new BigDecimal("251000")).bidsCount(10).minPrice(new BigDecimal("251000")).maxPrice(new BigDecimal("613500"))
                .ourPlace(2).ourPrice(new BigDecimal("260000")).ourRejected(false));
        result(t, 1, TenderLotResult.builder().outcome(LotOutcome.FAILED).failReason(FailReason.NO_BIDS).bidsCount(0));
        result(t, 2, TenderLotResult.builder().outcome(LotOutcome.FAILED).failReason(FailReason.ONE_BID).bidsCount(1)
                .minPrice(new BigDecimal("3700000"))
                .attempts(List.of(new ResultAttempt("970004001-1", "3-ЗЦП2", "ЗЦП", 370, null, null, null, null, null),
                        new ResultAttempt("970004002-1", "3-ОИ3", "ОИ", 370, null, null, null, "900000000009", null))));
        subjects.saveAndFlush(GoszakupSubject.builder().bin("900000000009").nameRu("ИП БОЛАТ").fetchedAt(OffsetDateTime.now()).build());
        event(t, 2, ResultEventKind.OI_FAILED);
        result(t, 3, TenderLotResult.builder().outcome(LotOutcome.CANCELLED));
        result(t, 4, TenderLotResult.builder().outcome(LotOutcome.PENDING));
        var s = query.lotSummaries(t.getLots().stream().map(TenderLot::getId).toList());
        assertThat(s.get(t.getLots().get(0).getId()).text())
                .isEqualTo("✓ Состоялся · победитель ИП Әбдібай · 251\u00A0000\u00A0₸ за шт · 10 заявок (251–613\u00A0тыс.) · наше место 2 из 10");
        assertThat(s.get(t.getLots().get(1).getId()).text()).isEqualTo("✗ Не состоялся · 0 заявок — объявят заново");
        assertThat(s.get(t.getLots().get(2).getId()).text())
                .isEqualTo("✗ Не состоялся · 1 заявка → из одного источника у ИП БОЛАТ, 3,7\u00A0млн\u00A0₸ — сорвалось, объявят заново");
        assertThat(s.get(t.getLots().get(3).getId()).text()).isEqualTo("Отменён");
        assertThat(s.get(t.getLots().get(4).getId()).text()).isEqualTo("Ждём итогов");
        LotResultSummary first = s.get(t.getLots().get(0).getId());
        assertThat(first.ourPlace()).isEqualTo(2);
        assertThat(first.outcome()).isEqualTo("DONE");
    }

    @Test
    void tenderChips_callDoneOurs() {
        Tender call = ResultsFixtures.tender(tenders, 970004011L, NOW.minusDays(5), "1-ЗЦП1", "Тележка");
        result(call, 0, TenderLotResult.builder().outcome(LotOutcome.FAILED).failReason(FailReason.NO_BIDS).bidsCount(0));
        event(call, 0, ResultEventKind.NO_BIDS);
        Tender won = ResultsFixtures.tender(tenders, 970004012L, NOW.minusDays(5), "1-ЗЦП1", "Тележка");
        result(won, 0, TenderLotResult.builder().outcome(LotOutcome.DONE).winnerName("Индивидуальный предприниматель Әбдібай Айдос")
                .winnerPrice(new BigDecimal("251000")).ourPlace(1));
        Tender repeat = ResultsFixtures.tender(tenders, 970004013L, NOW.minusDays(5), "1-ЗЦП1", "Тележка");
        result(repeat, 0, TenderLotResult.builder().outcome(LotOutcome.FAILED).failReason(FailReason.OTHER).nextTenderId(call.getId()));
        var s = query.tenderSummaries(List.of(call.getId(), won.getId(), repeat.getId()));
        TenderResultSummary c = s.get(call.getId());
        assertThat(c.chip()).isEqualTo("не состоялся · 0 заявок");
        assertThat(c.call()).isTrue();
        TenderResultSummary w = s.get(won.getId());
        assertThat(w.chip()).isEqualTo("итог: Индивидуальный предпринимател… 251 тыс. ₸");
        assertThat(w.done()).isTrue();
        assertThat(w.ours()).isTrue();
        assertThat(s.get(repeat.getId()).chip()).isEqualTo("объявлен повтор");
        assertThat(s.get(repeat.getId()).call()).isFalse();
    }

    @Test
    void view_bidsContractAttempts() {
        Tender t = ResultsFixtures.tender(tenders, 970004021L, NOW.minusDays(5), "88214663-ЗЦП2", "Тележка");
        TenderLotResult r = result(t, 0, TenderLotResult.builder().outcome(LotOutcome.DONE).bidsCount(2)
                .protocolStatus(ProtocolStatus.PARSED).protocolUrl(ResultsFixtures.URL).protocolName("protocol_PI_1.pdf")
                .contractNumber("070740006294/260209/00").contractSignedAt(LocalDate.of(2026, 10, 20)).contractUnitPrice(new BigDecimal("251000"))
                .contractSupplierBin("900000000001")
                .attempts(List.of(new ResultAttempt("970004021-1", "88214663-ЗЦП2", "ЗЦП", 360, "Закупка состоялась", null, null, null, null))));
        bids.saveAndFlush(TenderLotBid.builder().resultId(r.getId()).place(1).supplierName("Поставщик 1").supplierBin("900000000001")
                .unitPrice(new BigDecimal("251000")).winner(true).build());
        bids.saveAndFlush(TenderLotBid.builder().resultId(r.getId()).supplierName("Поставщик 3").supplierBin("900000000003")
                .rejected(true).rejectReason("нет РУ").build());
        subjects.saveAndFlush(GoszakupSubject.builder().bin("900000000001").nameRu("ТОО «Поставщик»").fetchedAt(OffsetDateTime.now()).build());
        LotResultView v = query.view(t.getLots().get(0).getId());
        assertThat(v.bids()).extracting(LotResultView.Bid::name).containsExactly("Поставщик 1", "Поставщик 3");
        assertThat(v.bids().get(1).rejected()).isTrue();
        assertThat(v.contract().supplierName()).isEqualTo("ТОО «Поставщик»");
        assertThat(v.attempts()).extracting(LotResultView.Attempt::own).containsExactly(true);
        assertThat(v.protocolUrl()).isEqualTo(ResultsFixtures.URL);
        assertThat(query.view(ResultsFixtures.tender(tenders, 970004022L, NOW.minusDays(5), "1-ЗЦП1", "Без итогов").getLots().get(0).getId())
                .text()).isEqualTo("Итоги ещё не проверялись");
    }
}
```

- [ ] **Step 2: Тест путей**

```java
package com.vladoose.nir.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderController;
import com.vladoose.nir.controller.TenderResultsController;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.repository.TenderLotResultRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Пути итогов (спека 2026-10-22-tender-results-market-prices §13): читают все, проверку запускает админ; чужой рынок — 404. */
@SpringBootTest
@Transactional
class TenderResultsApiTest {

    @Autowired TenderResultsController controller;
    @Autowired TenderController tenderController;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotResultRepository results;
    @Autowired GoszakupSubjectRepository subjects;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Tender withResult(long buyId) {
        Tender t = ResultsFixtures.tender(tenders, buyId, NOW.minusDays(5), "1-ЗЦП1", "Тележка");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId())
                .outcome(LotOutcome.DONE).winnerName("ИП X").winnerPrice(new BigDecimal("1000")).build());
        return t;
    }

    @Test
    @WithMockUser(roles = "USER")
    void operatorReads_cannotCheck() {
        Tender t = withResult(970005001L);
        subjects.saveAndFlush(GoszakupSubject.builder().bin("110240019426").nameRu("ГКП «ZZ больница»").phone("31398")
                .fetchedAt(OffsetDateTime.now()).build());
        assertThat(controller.lotResult(t.getLots().get(0).getId()).outcome()).isEqualTo("DONE");
        assertThat(controller.customer(t.getId()).phone()).isEqualTo("31398");
        assertThat(tenderController.getLots(t.getId()).get(0).getResult().text()).startsWith("✓ Состоялся · победитель ИП X");
        assertThat(tenderController.findById(t.getId()).getResult().chip()).startsWith("итог: ИП X");
        assertThatThrownBy(() -> controller.check(t.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.status()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void otherMarket_404_rf_400() {
        Tender t = withResult(970005002L);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.lotResult(t.getLots().get(0).getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.customer(t.getId())).isInstanceOf(NotFoundException.class);
        Tender rf = tenders.saveAndFlush(Tender.builder().tenderNumber("ZZ-RF-" + System.nanoTime()).status("ACTIVE").market(Market.RF).build());
        assertThatThrownBy(() -> controller.customer(rf.getId())).isInstanceOf(BadRequestException.class);
    }

    /** Ручной тендер или срок не прошёл — 400 сразу, без очереди. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void check_notEligible_400() {
        Tender future = ResultsFixtures.tender(tenders, 970005003L, NOW.plusYears(1), "1-ЗЦП1", "Ещё идёт");
        assertThatThrownBy(() -> controller.check(future.getId())).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("итогов пока нет");
    }
}
```

`TenderProgressTest.java` — добавить:

```java
    /** Без нашей заявки этап «Итог» подписан итогом площадки (спека 2026-10-22-tender-results-market-prices §11). */
    @Test
    void resultStepShowsPlatformResult(@Autowired TenderLotResultRepository results) {
        Tender t = tender(true);
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId())
                .outcome(LotOutcome.DONE).winnerName("ИП Әбдібай").winnerPrice(new BigDecimal("251000")).build());
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(1).getId()).tenderId(t.getId())
                .outcome(LotOutcome.FAILED).bidsCount(0).build());
        DealProgress p = builder.build(t, LocalDateTime.of(2026, 10, 25, 12, 0));
        assertThat(p.steps().stream().filter(s -> s.key().equals("RESULT")).findFirst().orElseThrow().detail())
                .isEqualTo("состоялся 1 из 2 · не состоялся 1");
        results.deleteAllInBatch(results.findByTenderId(t.getId()).stream().filter(r -> r.getOutcome() == LotOutcome.FAILED).toList());
        assertThat(builder.build(t, LocalDateTime.of(2026, 10, 25, 12, 0)).steps().stream().filter(s -> s.key().equals("RESULT"))
                .findFirst().orElseThrow().detail()).isEqualTo("победил ИП Әбдібай · 251\u00A0000\u00A0₸");
    }
```

(импорты `com.vladoose.nir.repository.TenderLotResultRepository` — уже под `repository.*`).

- [ ] **Step 3: Тест «Истории»**

```java
package com.vladoose.nir.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.repository.TenderResultEventRepository;
import com.vladoose.nir.service.deal.DealTimelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** «История» тендера — строки из событий итогов (спека §11): время площадки, повтор — ссылкой на тендер. */
@SpringBootTest
@Transactional
class TenderResultsTimelineTest {

    @Autowired DealTimelineService timeline;
    @Autowired TenderRepository tenders;
    @Autowired TenderResultEventRepository events;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void resultEventsInHistory() {
        Tender t = ResultsFixtures.tender(tenders, 970006001L, NOW.minusDays(5), "1-ЗЦП1", "Тележка");
        Tender next = ResultsFixtures.tender(tenders, 970006002L, NOW.plusDays(2), "1-ЗЦП2", "Тележка");
        Long lot = t.getLots().get(0).getId();
        events.saveAndFlush(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(lot).kind(ResultEventKind.NO_BIDS)
                .occurredAt(LocalDateTime.of(2026, 10, 17, 10, 0)).text("никто не подал — объявят заново").urgent(true)
                .topic(ResultTopic.NONE).notifiedAt(OffsetDateTime.now()).digestedAt(OffsetDateTime.now()).build());
        events.saveAndFlush(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(lot).kind(ResultEventKind.REPEAT).refKey("970006002-1")
                .occurredAt(LocalDateTime.of(2026, 10, 20, 9, 0)).text("объявлена повторная попытка № 970006002-1 (запрос цен)")
                .urgent(false).topic(ResultTopic.NONE).refTenderId(next.getId()).notifiedAt(OffsetDateTime.now()).digestedAt(OffsetDateTime.now())
                .build());
        List<DealTimelineItem> items = timeline.forTender(t.getId(), null, 50).items().stream().filter(i -> "RESULT".equals(i.kind())).toList();
        assertThat(items).extracting(DealTimelineItem::text).containsExactly("Итоги: никто не подал — объявят заново",
                "Объявлена повторная попытка № 970006002-1 (запрос цен)");
        assertThat(items.get(0).at().toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 10, 17, 10, 0));
        assertThat(items.get(1).linkType()).isEqualTo("TENDER");
        assertThat(items.get(1).linkId()).isEqualTo(next.getId());
    }
}
```

- [ ] **Step 4: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.TenderResultsQueryTest' --tests 'com.vladoose.nir.tenderresults.TenderResultsApiTest' --tests 'com.vladoose.nir.tenderresults.TenderResultsTimelineTest' --tests 'com.vladoose.nir.deal.TenderProgressTest' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (нет `TenderResultsQuery`, `TenderResultsController`, `getResult()`).

- [ ] **Step 5: DTO**

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Итог лота строкой у лота (спека 2026-10-22-tender-results-market-prices §11): outcome — PENDING / DONE / FAILED / CANCELLED. */
public record LotResultSummary(String outcome, String text, String failReason, Integer bidsCount, String winnerName,
                               BigDecimal winnerPrice, Integer ourPlace, Boolean ourRejected, boolean contract, String protocolStatus,
                               LocalDateTime resultsAt) {}
```

```java
package com.vladoose.nir.dto.response;

/** Итог тендера для списка: чип, «звонить» (никто не подал / ОИ сорвался), состоялся, мы участвовали. */
public record TenderResultSummary(String chip, boolean call, boolean done, boolean ours) {}
```

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** Полный итог лота (спека §11, §13): участники, договор, цепочка попыток, протокол. outcome пусто — итоги не проверялись. */
public record LotResultView(Long lotId, String outcome, String text, String lotStatus, String failReason, String failText,
                            LocalDateTime resultsAt, String protocolUrl, String protocolName, String protocolStatus, String protocolNote,
                            Integer bidsCount, Integer rejectedCount, List<Bid> bids, Contract contract, List<Attempt> attempts,
                            Long nextTenderId, String nextTenderNumber, OffsetDateTime checkedAt, OffsetDateTime finalAt) {

    public record Bid(Integer place, String name, String bin, BigDecimal unitPrice, BigDecimal total, LocalDateTime submittedAt,
                      boolean rejected, String rejectReason, boolean winner, boolean second, boolean ours) {}

    public record Contract(String number, LocalDate signedAt, BigDecimal unitPrice, BigDecimal sum, String supplierBin, String supplierName) {}

    public record Attempt(String anno, String lotNumber, String method, String methodName, String status, Integer statusId,
                          String publishedAt, String resultsAt, String supplierBin, String supplierName, Long tenderId, boolean own) {}
}
```

```java
package com.vladoose.nir.dto.response;

/** Контакты заказчика из справочника площадки (телефон бывает неполным). */
public record CustomerContacts(String bin, String name, String phone, String email) {}
```

`TenderLotResponse.java` — после `requirements`:

```java
    /** Итог лота на площадке (спека 2026-10-22-tender-results-market-prices §11); пусто — итоги не проверялись. */
    private LotResultSummary result;
```

`TenderResponse.java` — после `aiAnalog`:

```java
    /** Итог тендера для чипа и фильтра «Итоги» (одним запросом на список). */
    private TenderResultSummary result;
```

`TenderMapper.toResponse` — добавить `@Mapping(target = "result", ignore = true)`; `TenderLotMapper.toResponse` — то же.

`ResultTexts.java` — добавить:

```java
    /** Коротко для чипа: «251 тыс. ₸», «1,3 млн ₸», «950 ₸». */
    public static String compact(BigDecimal v) {
        if (v == null) return "";
        BigDecimal abs = v.abs();
        if (abs.compareTo(MILLION) >= 0) return num(v.divide(MILLION, 1, RoundingMode.HALF_UP)) + " млн ₸";
        if (abs.compareTo(THOUSAND) >= 0) return v.divide(THOUSAND, 0, RoundingMode.HALF_UP).toPlainString() + " тыс. ₸";
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₸";
    }
```

`GoszakupSubjects.java` — добавить:

```java
    /** Что есть у себя, без площадки (контакты в карточке при отказе площадки). */
    public SubjectInfo cached(String bin) {
        return bin == null ? null : repo.findById(bin).map(GoszakupSubjects::info).orElse(null);
    }
```

`TenderResultEventRepository.java` — добавить:

```java
    List<TenderResultEvent> findByTenderLotIdIn(Collection<Long> lotIds);

    List<TenderResultEvent> findByTenderIdInAndKindIn(Collection<Long> tenderIds, Collection<ResultEventKind> kinds);
```

- [ ] **Step 6: Чтение итогов**

```java
package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.dto.response.LotResultSummary;
import com.vladoose.nir.dto.response.LotResultView;
import com.vladoose.nir.dto.response.TenderResultSummary;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Итоги для экрана (спека 2026-10-22-tender-results-market-prices §11, §13): сводки — одним заходом на список, полный итог лота. */
@Component
public class TenderResultsQuery {

    static final Set<ResultEventKind> CALLS = EnumSet.of(ResultEventKind.NO_BIDS, ResultEventKind.FAILED_OTHER, ResultEventKind.OI_FAILED);

    private final TenderLotResultRepository results;
    private final TenderLotBidRepository bids;
    private final TenderResultEventRepository events;
    private final TenderLotRepository lots;
    private final TenderRepository tenders;
    private final GoszakupSubjectRepository subjects;

    public TenderResultsQuery(TenderLotResultRepository results, TenderLotBidRepository bids, TenderResultEventRepository events,
                              TenderLotRepository lots, TenderRepository tenders, GoszakupSubjectRepository subjects) {
        this.results = results;
        this.bids = bids;
        this.events = events;
        this.lots = lots;
        this.tenders = tenders;
        this.subjects = subjects;
    }

    @Transactional(readOnly = true)
    public Map<Long, LotResultSummary> lotSummaries(Collection<Long> lotIds) {
        if (lotIds.isEmpty()) return Map.of();
        List<TenderLotResult> rs = results.findByTenderLotIdIn(lotIds);
        if (rs.isEmpty()) return Map.of();
        Map<Long, TenderLot> lotById = lots.findAllById(rs.stream().map(TenderLotResult::getTenderLotId).toList()).stream()
                .collect(Collectors.toMap(TenderLot::getId, Function.identity()));
        Map<Long, Set<ResultEventKind>> kinds = new HashMap<>();
        for (TenderResultEvent e : events.findByTenderLotIdIn(lotIds)) {
            kinds.computeIfAbsent(e.getTenderLotId(), k -> EnumSet.noneOf(ResultEventKind.class)).add(e.getKind());
        }
        Map<String, String> names = names(rs.stream().flatMap(r -> r.getAttempts().stream()).map(ResultAttempt::supplierBin)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, LotResultSummary> out = new HashMap<>();
        for (TenderLotResult r : rs) {
            TenderLot lot = lotById.get(r.getTenderLotId());
            if (lot == null) continue;
            String unit = ResultTexts.unit(lot.getPlanUnit(), lot.getUnit());
            String oi = r.getAttempts().stream().filter(a -> "ОИ".equals(a.method()) && a.supplierBin() != null)
                    .map(a -> names.get(a.supplierBin())).filter(Objects::nonNull).findFirst().orElse(null);
            out.put(lot.getId(), new LotResultSummary(r.getOutcome().name(), lotText(r, unit, kinds.getOrDefault(lot.getId(), Set.of()), oi),
                    r.getFailReason() == null ? null : r.getFailReason().name(), r.getBidsCount(), r.getWinnerName(), r.getWinnerPrice(),
                    r.getOurPlace(), r.getOurRejected(), r.getContractNumber() != null,
                    r.getProtocolStatus() == null ? null : r.getProtocolStatus().name(), r.getResultsAt()));
        }
        return out;
    }

    /** Строка итога у лота (спека §11). */
    static String lotText(TenderLotResult r, String unit, Set<ResultEventKind> kinds, String oiSupplier) {
        String base = switch (r.getOutcome()) {
            case PENDING -> "Ждём итогов";
            case CANCELLED -> "Отменён";
            case DONE -> done(r, unit);
            case FAILED -> "✗ Не состоялся" + failTail(r, kinds, oiSupplier);
        };
        String ours = ours(r);
        return ours == null ? base : base + " · " + ours;
    }

    private static String done(TenderLotResult r, String unit) {
        StringBuilder b = new StringBuilder("✓ Состоялся");
        if (r.getWinnerName() != null || r.getWinnerBin() != null) b.append(" · победитель ").append(ResultTexts.who(r.getWinnerName(), r.getWinnerBin()));
        if (r.getWinnerPrice() != null) b.append(" · ").append(ResultTexts.money(r.getWinnerPrice())).append(" за ").append(unit);
        if (r.getBidsCount() != null && r.getBidsCount() > 0) {
            b.append(" · ").append(ResultTexts.bids(r.getBidsCount()));
            if (r.getMinPrice() != null && r.getMaxPrice() != null && r.getMinPrice().compareTo(r.getMaxPrice()) != 0) {
                b.append(" (").append(ResultTexts.range(r.getMinPrice(), r.getMaxPrice())).append(')');
            }
        }
        if (r.getContractNumber() != null) b.append(" · договор подписан");
        return b.toString();
    }

    private static String failTail(TenderLotResult r, Set<ResultEventKind> kinds, String oiSupplier) {
        if (r.getFailReason() == FailReason.ONE_BID || kinds.contains(ResultEventKind.ONE_BID)) {
            return " · 1 заявка → из одного источника" + (oiSupplier == null ? "" : " у " + oiSupplier)
                    + (r.getMinPrice() == null ? "" : ", " + ResultTexts.money(r.getMinPrice()))
                    + (kinds.contains(ResultEventKind.OI_FAILED) ? " — сорвалось, объявят заново"
                    : kinds.contains(ResultEventKind.OI_DONE) ? " — состоялся" : "");
        }
        if (r.getFailReason() == FailReason.NO_BIDS) return " · 0 заявок — объявят заново";
        if (kinds.contains(ResultEventKind.OI_FAILED)) return " · из одного источника сорвалось — объявят заново";
        if (r.getNextTenderId() != null) return " · объявлен повтор";
        return " · объявят заново";
    }

    private static String ours(TenderLotResult r) {
        if (Boolean.TRUE.equals(r.getOurRejected())) return "наша заявка отклонена";
        if (r.getOurPlace() == null) return null;
        if (r.getOutcome() == LotOutcome.DONE && r.getOurPlace() == 1) return "выиграли";
        return "наше место " + r.getOurPlace() + (r.getBidsCount() == null ? "" : " из " + r.getBidsCount());
    }

    @Transactional(readOnly = true)
    public Map<Long, TenderResultSummary> tenderSummaries(Collection<Long> tenderIds) {
        if (tenderIds.isEmpty()) return Map.of();
        List<TenderLotResult> rs = results.findByTenderIdIn(tenderIds);
        if (rs.isEmpty()) return Map.of();
        Set<Long> calls = events.findByTenderIdInAndKindIn(tenderIds, CALLS).stream().map(TenderResultEvent::getTenderId)
                .collect(Collectors.toSet());
        Map<Long, List<TenderLotResult>> byTender = rs.stream().collect(Collectors.groupingBy(TenderLotResult::getTenderId));
        Map<Long, TenderResultSummary> out = new HashMap<>();
        byTender.forEach((id, list) -> {
            boolean call = calls.contains(id);
            boolean done = list.stream().anyMatch(r -> r.getOutcome() == LotOutcome.DONE);
            boolean ours = list.stream().anyMatch(r -> r.getOurPlace() != null || r.getOurRejected() != null);
            out.put(id, new TenderResultSummary(chip(list, call), call, done, ours));
        });
        return out;
    }

    /** Чип списка: «не состоялся · 0 заявок» / «итог: ИП X 251 тыс. ₸» / «объявлен повтор» / «отменён» / «ждём итогов». */
    static String chip(List<TenderLotResult> list, boolean call) {
        if (call) {
            boolean none = list.stream().anyMatch(r -> r.getFailReason() == FailReason.NO_BIDS);
            return none ? "не состоялся · 0 заявок" : "не состоялся — звонить";
        }
        Optional<TenderLotResult> won = list.stream().filter(r -> r.getOutcome() == LotOutcome.DONE && r.getWinnerName() != null).findFirst();
        if (won.isPresent()) {
            String name = won.get().getWinnerName().trim();
            if (name.length() > 30) name = name.substring(0, 29).trim() + "…";
            String price = ResultTexts.compact(won.get().getWinnerPrice());
            return "итог: " + name + (price.isEmpty() ? "" : " " + price);
        }
        if (list.stream().anyMatch(r -> r.getOutcome() == LotOutcome.DONE)) return "состоялся";
        if (list.stream().anyMatch(r -> r.getNextTenderId() != null)) return "объявлен повтор";
        if (list.stream().anyMatch(r -> r.getFailReason() == FailReason.ONE_BID)) return "1 заявка → из одного источника";
        if (list.stream().allMatch(r -> r.getOutcome() == LotOutcome.CANCELLED)) return "отменён";
        if (list.stream().anyMatch(r -> r.getOutcome() == LotOutcome.FAILED)) return "не состоялся";
        return "ждём итогов";
    }

    /** Полный итог лота (панель «Итог»). */
    @Transactional(readOnly = true)
    public LotResultView view(long lotId) {
        TenderLot lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Лот не найден"));
        TenderLotResult r = results.findByTenderLotId(lotId).orElse(null);
        if (r == null) {
            return new LotResultView(lotId, null, "Итоги ещё не проверялись", null, null, null, null, null, null, null, null, null, null,
                    List.of(), null, List.of(), null, null, null, null);
        }
        List<TenderLotBid> list = bids.findByResultIdOrderByPlaceAscIdAsc(r.getId());
        Set<String> bins = new HashSet<>();
        if (r.getContractSupplierBin() != null) bins.add(r.getContractSupplierBin());
        r.getAttempts().stream().map(ResultAttempt::supplierBin).filter(Objects::nonNull).forEach(bins::add);
        Map<String, String> names = names(bins);
        String unit = ResultTexts.unit(lot.getPlanUnit(), lot.getUnit());
        Set<ResultEventKind> kinds = events.findByTenderLotIdIn(List.of(lotId)).stream().map(TenderResultEvent::getKind)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ResultEventKind.class)));
        String oi = r.getAttempts().stream().filter(a -> "ОИ".equals(a.method()) && a.supplierBin() != null)
                .map(a -> names.get(a.supplierBin())).filter(Objects::nonNull).findFirst().orElse(null);
        String anno = lot.getTender().getSourceExtId();
        List<LotResultView.Attempt> attempts = r.getAttempts().stream().map(a -> new LotResultView.Attempt(a.anno(), a.lotNumber(), a.method(),
                ResultTexts.methodName(a.method()), a.status(), a.statusId(), a.publishedAt(), a.resultsAt(), a.supplierBin(),
                a.supplierBin() == null ? null : names.get(a.supplierBin()), a.tenderId(),
                Objects.equals(a.anno(), anno) && Objects.equals(a.lotNumber(), lot.getSourceLotCode()))).toList();
        LotResultView.Contract contract = r.getContractNumber() == null ? null : new LotResultView.Contract(r.getContractNumber(),
                r.getContractSignedAt(), r.getContractUnitPrice(), r.getContractSum(), r.getContractSupplierBin(),
                r.getContractSupplierBin() == null ? null : names.get(r.getContractSupplierBin()));
        String nextNumber = r.getNextTenderId() == null ? null : tenders.findById(r.getNextTenderId()).map(Tender::getTenderNumber).orElse(null);
        return new LotResultView(lotId, r.getOutcome().name(), lotText(r, unit, kinds, oi), r.getLotStatus(),
                r.getFailReason() == null ? null : r.getFailReason().name(), r.getFailText(), r.getResultsAt(), r.getProtocolUrl(),
                r.getProtocolName(), r.getProtocolStatus() == null ? null : r.getProtocolStatus().name(), r.getProtocolNote(), r.getBidsCount(),
                r.getRejectedCount(), list.stream().map(b -> new LotResultView.Bid(b.getPlace(), b.getSupplierName(), b.getSupplierBin(),
                        b.getUnitPrice(), b.getTotal(), b.getSubmittedAt(), b.isRejected(), b.getRejectReason(), b.isWinner(), b.isSecondPlace(),
                        b.isOurs())).toList(),
                contract, attempts, r.getNextTenderId(), nextNumber, r.getCheckedAt(), r.getFinalAt());
    }

    private Map<String, String> names(Collection<String> bins) {
        if (bins.isEmpty()) return Map.of();
        return subjects.findByBinIn(bins).stream().filter(s -> s.getNameRu() != null && !s.getNameRu().isBlank())
                .collect(Collectors.toMap(GoszakupSubject::getBin, GoszakupSubject::getNameRu, (a, b) -> a));
    }
}
```

- [ ] **Step 7: Пути**

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.CustomerContacts;
import com.vladoose.nir.dto.response.LotResultView;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.TenderService;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.service.tenderresults.TenderResultsQuery;
import com.vladoose.nir.service.tenderresults.TenderResultsScheduler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Итоги тендеров (спека 2026-10-22-tender-results-market-prices §13): читают все вошедшие, проверку запускает администратор. */
@RestController
@RequestMapping("/api")
public class TenderResultsController {

    private final TenderService tenders;
    private final TenderLotRepository lots;
    private final TenderResultsQuery query;
    private final TenderResultsScheduler scheduler;
    private final GoszakupSubjects subjects;

    public TenderResultsController(TenderService tenders, TenderLotRepository lots, TenderResultsQuery query,
                                   TenderResultsScheduler scheduler, GoszakupSubjects subjects) {
        this.tenders = tenders;
        this.lots = lots;
        this.query = query;
        this.scheduler = scheduler;
        this.subjects = subjects;
    }

    /** Полный итог лота; лот чужого рынка или частной заявки — 404 (гард по тендеру, §6 CLAUDE.md). */
    @GetMapping("/lots/{id}/result")
    public LotResultView lotResult(@PathVariable Long id) {
        TenderLot lot = lots.findById(id).orElseThrow(() -> new NotFoundException("Лот не найден"));
        tenders.findPublic(lot.getTender().getId());
        return query.view(id);
    }

    @PostMapping("/tenders/{id}/results/check")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderResultsScheduler.CheckResult check(@PathVariable Long id) {
        tenders.findPublic(id);
        return scheduler.check(id);
    }

    /** Контакты заказчика: справочник площадки (раз в 30 дней); площадка отказала — что есть у себя. */
    @GetMapping("/tenders/{id}/customer")
    public CustomerContacts customer(@PathVariable Long id) {
        Tender t = tenders.findPublic(id);
        if (t.getMarket() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        String bin = t.getCustomerBin();
        if (bin == null || !bin.matches("\\d{12}")) return new CustomerContacts(bin, t.getCustomerName(), null, null);
        SubjectInfo s;
        try {
            s = subjects.get(bin);
        } catch (RuntimeException e) {
            s = subjects.cached(bin);
        }
        return s == null ? new CustomerContacts(bin, t.getCustomerName(), null, null)
                : new CustomerContacts(bin, s.nameRu() != null ? s.nameRu() : t.getCustomerName(), s.phone(), s.email());
    }

    @GetMapping("/tender-results/status")
    @PreAuthorize("hasRole('ADMIN')")
    public TenderResultsScheduler.Status status() {
        return scheduler.status();
    }
}
```

- [ ] **Step 8: Сводки в путях тендеров** — `TenderController.java`: добавить поле и параметр конструктора `TenderResultsQuery resultsQuery` (последним, присвоить); в `withOurProduct` перед `return list;`:

```java
        Map<Long, TenderResultSummary> rs = resultsQuery.tenderSummaries(list.stream().map(TenderResponse::getId).toList());
        for (TenderResponse r : list) r.setResult(rs.get(r.getId()));
```

в `getLots` перед `return lots;`:

```java
        Map<Long, LotResultSummary> results = resultsQuery.lotSummaries(entities.stream().map(TenderLot::getId).toList());
        for (TenderLotResponse l : lots) l.setResult(results.get(l.getId()));
```

(импорты `com.vladoose.nir.dto.response.LotResultSummary`, `TenderResultSummary`, `com.vladoose.nir.service.tenderresults.TenderResultsQuery`).

- [ ] **Step 9: Этап «Итог»** — `TenderProgressBuilder.java`: поле и параметр конструктора `TenderLotResultRepository results` (последним); в `build` после массива `details`:

```java
        if (details[3] == null && apply == null) details[3] = platformResult(results.findByTenderId(t.getId()));
```

и метод:

```java
    /** Итог площадки без нашей заявки (спека 2026-10-22-tender-results-market-prices §11): один лот — подробно, несколько — счётом. */
    static String platformResult(List<TenderLotResult> list) {
        List<TenderLotResult> known = list.stream().filter(r -> r.getOutcome() != LotOutcome.PENDING).toList();
        if (known.isEmpty()) return null;
        if (list.size() == 1) {
            TenderLotResult r = known.get(0);
            return switch (r.getOutcome()) {
                case DONE -> r.getWinnerName() == null ? "состоялся"
                        : "победил " + r.getWinnerName() + (r.getWinnerPrice() == null ? "" : " · " + ResultTexts.money(r.getWinnerPrice()));
                case FAILED -> "не состоялся" + (r.getBidsCount() == null ? "" : " · " + ResultTexts.bids(r.getBidsCount()));
                case CANCELLED -> "отменён";
                case PENDING -> null;
            };
        }
        long done = known.stream().filter(r -> r.getOutcome() == LotOutcome.DONE).count();
        long failed = known.stream().filter(r -> r.getOutcome() == LotOutcome.FAILED).count();
        return "состоялся " + done + " из " + list.size() + (failed > 0 ? " · не состоялся " + failed : "");
    }
```

(импорты `com.vladoose.nir.repository.TenderLotResultRepository`, `com.vladoose.nir.service.tenderresults.ResultTexts`). «0 заявок» — `ResultTexts.bids(0)` = «0 заявок».

- [ ] **Step 10: «История»** — `DealTimelineService.java`: поле и параметр конструктора `TenderResultEventRepository resultEvents` (последним); в `forTender` после `addEvents(all, t.getId());` — `addResults(all, t);`; методы:

```java
    /** Строки итогов (спека 2026-10-22-tender-results-market-prices §11): время площадки, повтор — ссылкой на тендер в АИС. */
    private void addResults(List<DealTimelineItem> all, Tender t) {
        List<TenderResultEvent> list = resultEvents.findByTenderIdOrderByIdAsc(t.getId());
        if (list.isEmpty()) return;
        Map<Long, String> names = new HashMap<>();
        for (TenderLot l : t.getLots()) names.put(l.getId(), ProductMatchQuery.shortName(l.getEquipName()));
        boolean many = t.getLots().size() > 1;
        for (TenderResultEvent e : list) {
            OffsetDateTime at = e.getOccurredAt() != null ? e.getOccurredAt().atZone(Market.KZ.zone()).toOffsetDateTime() : e.getCreatedAt();
            all.add(new DealTimelineItem(at, false, "RESULT", resultText(e.getKind(), many ? names.get(e.getTenderLotId()) : null, e.getText()),
                    null, e.getRefTenderId() == null ? null : "TENDER", e.getRefTenderId(), null));
        }
    }

    static String resultText(ResultEventKind kind, String lotName, String text) {
        String lot = lotName == null ? null : "лот «" + lotName + "»";
        return switch (kind) {
            case OUR_BID -> lot == null ? text : text + " (" + lot + ")";
            case CONTRACT, REPEAT -> lot == null ? capitalize(text) : capitalize(lot) + ": " + text;
            default -> "Итоги" + (lot == null ? "" : ", " + lot) + ": " + text;
        };
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
```

(импорты `com.vladoose.nir.entity.ResultEventKind`, `TenderResultEvent`, `com.vladoose.nir.repository.TenderResultEventRepository`, `com.vladoose.nir.service.productwatch.ProductMatchQuery`; `Market` — уже в `entity.*`).

- [ ] **Step 11: Запустить — зелёный, и соседи**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.tenderresults.*' --tests 'com.vladoose.nir.tenderresults.*' --tests 'com.vladoose.nir.deal.*' --tests 'com.vladoose.nir.productwatch.TenderOurProductApiTest' 2>&1 | tail -20` (sandbox off)
Expected: PASS.

- [ ] **Step 12: Мутации**

1. `TenderResultsController.lotResult`: убрать `tenders.findPublic(...)` → `otherMarket_404_rf_400` красный.
2. `TenderResultsQuery.chip`: убрать ветку `call` → `tenderChips_callDoneOurs` красный.
3. `lotText`: `failTail` без OI_FAILED → `lotTexts` красный.
4. `TenderProgressBuilder`: убрать `platformResult` → `resultStepShowsPlatformResult` красный.
5. `DealTimelineService.addResults`: `linkType` всегда пусто → `resultEventsInHistory` красный.

- [ ] **Step 13: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java/com/vladoose/nir/service/tenderresults/TenderResultsQueryTest.java \
  src/test/java/com/vladoose/nir/tenderresults src/test/java/com/vladoose/nir/deal/TenderProgressTest.java && git commit -m "$(cat <<'EOF'
feat(results): API итогов, итог у лота и чип у тендера, этап «Итог» и «История» из событий

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Цены рынка — набор договоров: ежедневный сбор, живой запрос, история заказчика

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/market/MarketContractWriter.java`, `MarketCollector.java`, `CustomerHistoryService.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/CustomerHistory.java`
- Modify: `src/main/java/com/vladoose/nir/service/tenderresults/TenderResultsScheduler.java` (сбор цен по расписанию), `src/main/java/com/vladoose/nir/controller/TenderResultsController.java` (`GET /tenders/{id}/customer-history`), `src/main/resources/application.yaml` (`market-cron`)
- Modify (тесты): `src/test/java/com/vladoose/nir/tenderresults/FakeGoszakupResultsClient.java` (в журнале вызова — дата «от»), `src/test/java/com/vladoose/nir/service/tenderresults/TenderResultsSchedulerTest.java` (конструктор, +1 тест)
- Test: `src/test/java/com/vladoose/nir/market/MarketCollectorTest.java`, `CustomerHistoryServiceTest.java`

**Interfaces:**
- Consumes: `GoszakupResultsClient.closedLots/contractsByBuyIds/contractsByCustomer/lotsByIds`, `ContractRow.concluded()`, `MarketContractRepository`, `MarketLookupRepository`, `RegionResolver.resolve(String...)`, `GoszakupSubjects.names`, `CatalogSource.get().groups()` (`GroupRule.searchWords()`), `MedicalGoodsVocabulary.hasDeviceStrong(String)`, `ProductWatchService.stopping`, `ImportOutageNotifier.afterProductRun`.
- Produces: `MarketContractWriter.upsert(List<ClosedLot>, List<ContractRow>, OffsetDateTime) → int`, `lookup(String key) → Optional<MarketLookup>`, `mark(String key, int rows, String error, OffsetDateTime)`, `static BigDecimal budget(ClosedLot)`; `MarketCollector.record Lookup(boolean ok, OffsetDateTime fetchedAt, String error)`, `record Collected(int rows, boolean complete)`, `collectDaily(ImportSummary)`, `Lookup ensureWord(String word, Duration budget)`, `Lookup ensureCustomer(String bin)`, `static String key(String prefix, String value)`; константы `FRESH = 24 ч`, `ERROR_BACKOFF = 10 мин`, `YEARS = 2`, `DAILY = "daily"`; `CustomerHistoryService.forTender(Tender) → CustomerHistory`; `record CustomerHistory(String bin, List<Row> rows, OffsetDateTime fetchedAt, String error)` с `Row(LocalDate signedAt, String name, BigDecimal quantity, String unit, BigDecimal unitPrice, String supplierName, String region, boolean sameCategory, String anno)`; `TenderResultsScheduler.startMarket()`, `Status` — ещё `lastMarketAt`, `lastMarket`; `DEFAULT_MARKET_CRON = "0 15 7 * * *"`; `GET /api/tenders/{id}/customer-history`.

- [ ] **Step 1: Фейк пишет дату «от»** — `FakeGoszakupResultsClient.closedLots`: `call("closed:" + w);` → `call("closed:" + w + "@" + f);`.

- [ ] **Step 2: Тест набора**

```java
package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MarketContract;
import com.vladoose.nir.entity.MarketLookup;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.repository.MarketContractRepository;
import com.vladoose.nir.repository.MarketLookupRepository;
import com.vladoose.nir.service.market.MarketCollector;
import com.vladoose.nir.service.market.MarketContractWriter;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Набор цен рынка (спека 2026-10-22-tender-results-market-prices §9). */
@SpringBootTest
@Transactional
class MarketCollectorTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final String CODE = "266011.150.000000";

    @Autowired MarketContractWriter writer;
    @Autowired MarketContractRepository contracts;
    @Autowired MarketLookupRepository lookups;
    @Autowired GoszakupSubjectRepository subjectRepo;

    FakeGoszakupResultsClient fake;
    MarketCollector collector;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lookups.deleteAllInBatch();
        contracts.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        collector = new MarketCollector(fake, writer, new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { }),
                () -> List.of("дефибриллятор"), CLOCK, ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ClosedLot lot(long id, long buy, String count, String amount) {
        return new ClosedLot(id, id + "-ЗЦП1", "Дефибриллятор", "для терапии нарушений сердечного ритма",
                count == null ? null : new BigDecimal(count), new BigDecimal(amount), buy, buy + "-1", "990240006109", "ГКП «Больница»",
                CODE, "бифазный, с монитором", "Штука", "Актюбинская область, г.Актобе", "Запрос ценовых предложений");
    }

    static ContractRow contract(long id, long buy, long lotId, String price, String signed, int status) {
        return new ContractRow(id, buy, "Д-" + id, signed, status, "900000000001", new BigDecimal(price),
                List.of(new ContractUnitRow(lotId, new BigDecimal(price), BigDecimal.ONE, new BigDecimal(price))));
    }

    @Test
    void daily_rowFromLotAndLatestConcludedContract() {
        fake.closed.put("дефибриллятор", List.of(lot(99000001L, 99100001L, "2", "2800000"), lot(99000002L, 99100002L, "1", "900000")));
        fake.contracts.add(contract(1, 99100001L, 99000001L, "1340000", "2026-05-10 10:00:00", 190));
        fake.contracts.add(contract(2, 99100001L, 99000001L, "1", "2026-06-01 10:00:00", 330));   // не заключён — мимо
        ImportSummary sum = new ImportSummary();
        collector.collectDaily(sum);
        assertThat(sum.getErrors()).isZero();
        List<MarketContract> rows = contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(CODE, LocalDate.of(2024, 10, 22));
        assertThat(rows).hasSize(1);
        MarketContract m = rows.get(0);
        assertThat(m.getUnitPrice()).isEqualByComparingTo("1340000");
        assertThat(m.getBudgetUnitPrice()).isEqualByComparingTo("1400000.00");
        assertThat(m.getRegion()).isEqualTo("Актюбинская область");
        assertThat(m.getSignedAt()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(m.getUnit()).isEqualTo("Штука");
        assertThat(fake.calls).contains("closed:дефибриллятор@2024-10-22");
        assertThat(lookups.findById(MarketCollector.DAILY)).isPresent();
        collector.collectDaily(new ImportSummary());
        assertThat(contracts.findByLotExtIdIn(List.of(99000001L))).as("повторный сбор не дублирует").hasSize(1);
    }

    /** Дальше — с прошлого удачного сбора минус 3 дня. */
    @Test
    void daily_fromLastSuccessMinus3Days() {
        lookups.saveAndFlush(MarketLookup.builder().lookupKey(MarketCollector.DAILY).fetchedAt(OffsetDateTime.parse("2026-10-20T03:00:00Z")).build());
        collector.collectDaily(new ImportSummary());
        assertThat(fake.calls).contains("closed:дефибриллятор@2026-10-17");
    }

    /** Без количества — бюджета за единицу нет, строка есть (Review Focus 4). */
    @Test
    void noQuantity_noBudget() {
        fake.closed.put("дефибриллятор", List.of(lot(99000003L, 99100003L, null, "2800000")));
        fake.contracts.add(contract(3, 99100003L, 99000003L, "1340000", "2026-05-10 10:00:00", 190));
        collector.collectDaily(new ImportSummary());
        MarketContract m = contracts.findByLotExtIdIn(List.of(99000003L)).get(0);
        assertThat(m.getBudgetUnitPrice()).isNull();
        assertThat(m.getUnitPrice()).isEqualByComparingTo("1340000");
    }

    /** 403 — стоп, «удачный сбор» не отмечается. */
    @Test
    void stopCode_stops_notMarked() {
        fake.forbidFromCall = 1;
        ImportSummary sum = new ImportSummary();
        collector.collectDaily(sum);
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).contains("сбор остановлен");
        assertThat(lookups.findById(MarketCollector.DAILY)).isEmpty();
    }

    /** Живой запрос: свежий — без площадки; сбой — повтор не раньше чем через 10 мин. */
    @Test
    void ensureWord_freshNoCall_errorBackoff() {
        MarketCollector.Lookup first = collector.ensureWord("Дефибриллятор", Duration.ofSeconds(20));
        assertThat(first.ok()).isTrue();
        int calls = fake.calls.size();
        assertThat(collector.ensureWord("дефибриллятор", Duration.ofSeconds(20)).ok()).isTrue();
        assertThat(fake.calls).hasSize(calls);
        fake.forbidFromCall = fake.calls.size() + 1;
        assertThat(collector.ensureWord("монитор", Duration.ofSeconds(20)).ok()).isFalse();
        int afterFail = fake.calls.size();
        assertThat(collector.ensureWord("монитор", Duration.ofSeconds(20)).error()).contains("не удалось обновить");
        assertThat(fake.calls).as("в течение 10 мин после сбоя площадку не дёргаем").hasSize(afterFail);
    }
}
```

```java
package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.CustomerHistory;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
import com.vladoose.nir.integration.goszakup.dto.SubjectInfo;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.market.CustomerHistoryService;
import com.vladoose.nir.service.market.MarketCollector;
import com.vladoose.nir.service.market.MarketContractWriter;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** История заказчика (спека §8): договоры за 2 года — сначала той же категории, потом прочие медизделия; не медицина — мимо. */
@SpringBootTest
@Transactional
class CustomerHistoryServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);

    @Autowired MarketContractWriter writer;
    @Autowired MarketContractRepository contracts;
    @Autowired MarketLookupRepository lookups;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired TenderRepository tenders;

    @BeforeEach
    void kz() {
        MarketContext.set(Market.KZ);
        lookups.deleteAllInBatch();
        contracts.deleteAllInBatch();   // nirdb постоянная: договоры этого заказчика из живых проверок — мимо (откат после)
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ClosedLot lot(long id, String name, String code) {
        return new ClosedLot(id, id + "-ЗЦП1", name, null, BigDecimal.ONE, new BigDecimal("100000"), 99200000L + id % 1000,
                (99200000L + id % 1000) + "-1", "110240019426", "ГКП «ZZ больница»", code, null, "Штука", null, "Запрос ценовых предложений");
    }

    @Test
    void sameCategoryFirst_thenMedical_noOffice() {
        Tender t = ResultsFixtures.tender(tenders, 970007001L, NOW.minusDays(3), "1-ЗЦП1", "Тележка");
        t.getLots().get(0).setEnstruCode("309911.100.000001");
        tenders.saveAndFlush(t);
        FakeGoszakupResultsClient fake = new FakeGoszakupResultsClient();
        fake.contracts.add(new ContractRow(1L, 99200001L, "Д-1", "2026-09-01 10:00:00", 190, "900000000001", new BigDecimal("100000"),
                List.of(new ContractUnitRow(99300001L, new BigDecimal("100000"), BigDecimal.ONE, new BigDecimal("100000")))));
        fake.contracts.add(new ContractRow(2L, 99200002L, "Д-2", "2026-10-01 10:00:00", 190, "900000000002", new BigDecimal("200000"),
                List.of(new ContractUnitRow(99300002L, new BigDecimal("200000"), BigDecimal.ONE, new BigDecimal("200000")))));
        fake.contracts.add(new ContractRow(3L, 99200003L, "Д-3", "2026-10-05 10:00:00", 190, "900000000003", new BigDecimal("5000"),
                List.of(new ContractUnitRow(99300003L, new BigDecimal("5000"), BigDecimal.ONE, new BigDecimal("5000")))));
        fake.lotsById.put(99300001L, lot(99300001L, "Тележка медицинская", "309911.100.000001"));
        fake.lotsById.put(99300002L, lot(99300002L, "Электрокардиограф 12-канальный", "266012.100.000000"));
        fake.lotsById.put(99300003L, lot(99300003L, "Бумага офисная А4", "171812.000.000000"));
        fake.subjects.put("900000000001", new SubjectInfo("900000000001", "ТОО «Поставщик»", null, null));
        GoszakupSubjects subjects = new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { });
        MarketCollector collector = new MarketCollector(fake, writer, subjects, List::of, CLOCK, ms -> { });
        CustomerHistory h = new CustomerHistoryService(collector, contracts, subjectRepo, CLOCK).forTender(t);
        assertThat(h.rows()).extracting(CustomerHistory.Row::name).containsExactly("Тележка медицинская", "Электрокардиограф 12-канальный");
        assertThat(h.rows().get(0).sameCategory()).isTrue();
        assertThat(h.rows().get(0).supplierName()).isEqualTo("ТОО «Поставщик»");
        assertThat(h.error()).isNull();
    }
}
```

- [ ] **Step 3: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.market.*' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (нет пакета `service.market`).

- [ ] **Step 4: Запись набора**

```java
package com.vladoose.nir.service.market;

import com.vladoose.nir.entity.MarketContract;
import com.vladoose.nir.entity.MarketLookup;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
import com.vladoose.nir.repository.MarketContractRepository;
import com.vladoose.nir.repository.MarketLookupRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Строки набора цен рынка (спека 2026-10-22-tender-results-market-prices §9): состоявшийся лот + последний подписанный не
 * расторгнутый договор по нему; ключ — id лота площадки (повторный сбор обновляет). Договор без строк или без цены — мимо.
 */
@Component
public class MarketContractWriter {

    private record Pick(ContractRow row, ContractUnitRow unit) {}

    private final MarketContractRepository contracts;
    private final MarketLookupRepository lookups;
    private final RegionResolver regions;

    public MarketContractWriter(MarketContractRepository contracts, MarketLookupRepository lookups, RegionResolver regions) {
        this.contracts = contracts;
        this.lookups = lookups;
        this.regions = regions;
    }

    @Transactional
    public int upsert(List<ClosedLot> lots, List<ContractRow> rows, OffsetDateTime now) {
        Map<Long, Pick> best = new HashMap<>();
        for (ContractRow c : rows) {
            if (!c.concluded()) continue;
            for (ContractUnitRow u : c.units()) {
                if (u.lotId() == null || u.unitPrice() == null || u.unitPrice().signum() <= 0) continue;
                Pick cur = best.get(u.lotId());
                if (cur == null || signed(c).isAfter(signed(cur.row()))) best.put(u.lotId(), new Pick(c, u));
            }
        }
        if (best.isEmpty()) return 0;
        Map<Long, MarketContract> existing = contracts.findByLotExtIdIn(best.keySet()).stream()
                .collect(Collectors.toMap(MarketContract::getLotExtId, Function.identity()));
        int n = 0;
        for (ClosedLot l : lots) {
            Pick p = l.id() == null ? null : best.get(l.id());
            if (p == null) continue;
            MarketContract m = existing.getOrDefault(l.id(), MarketContract.builder().lotExtId(l.id()).build());
            m.setAnno(cut(l.anno(), 30));
            m.setLotNumber(cut(l.lotNumber(), 50));
            m.setEnstruCode(cut(l.enstruCode(), 30));
            m.setName(cut(l.nameRu(), 500));
            m.setDescription(cut(l.descriptionRu(), 1000));
            m.setExtraDesc(l.extraDesc());
            m.setUnit(cut(l.unit(), 50));
            m.setCustomerBin(cut(l.customerBin(), 20));
            m.setCustomerName(cut(l.customerName(), 500));
            m.setRegion(cut(regions.resolve(l.deliveryPlace(), l.customerName()), 100));
            m.setQuantity(l.count());
            m.setBudgetUnitPrice(budget(l));
            m.setUnitPrice(p.unit().unitPrice());
            m.setSupplierBin(cut(p.row().supplierBin(), 20));
            LocalDate signedAt = signed(p.row());
            m.setSignedAt(signedAt.equals(LocalDate.MIN) ? null : signedAt);
            m.setContractNumber(cut(p.row().number(), 60));
            m.setTradeMethod(cut(l.tradeMethodName(), 150));
            m.setFetchedAt(now);
            existing.put(l.id(), contracts.save(m));
            n++;
        }
        return n;
    }

    /** Бюджет за единицу: сумма лота / количество; нет количества — пусто (Review Focus 4). */
    public static BigDecimal budget(ClosedLot l) {
        if (l.amount() == null || l.count() == null || l.count().signum() <= 0) return null;
        return l.amount().divide(l.count(), 2, RoundingMode.HALF_UP);
    }

    private static LocalDate signed(ContractRow c) {
        LocalDateTime t = ImportDeadline.parse(c.signDate());
        return t == null ? LocalDate.MIN : t.toLocalDate();
    }

    @Transactional(readOnly = true)
    public Optional<MarketLookup> lookup(String key) {
        return lookups.findById(key);
    }

    @Transactional
    public void mark(String key, int rows, String error, OffsetDateTime now) {
        MarketLookup l = lookups.findById(key).orElseGet(() -> MarketLookup.builder().lookupKey(key).build());
        l.setFetchedAt(now);
        l.setRowsCount(rows);
        l.setError(cut(error, 300));
        lookups.save(l);
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
```

- [ ] **Step 5: Сбор**

```java
package com.vladoose.nir.service.market;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MarketLookup;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ContractUnitRow;
import com.vladoose.nir.integration.goszakup.dto.SearchPage;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.service.productwatch.CatalogSource;
import com.vladoose.nir.service.productwatch.ProductWatchService;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * Набор цен рынка (спека 2026-10-22-tender-results-market-prices §9): раз в сутки — по словам групп «нашей продукции»; живой
 * запрос — слово, не обновлявшееся сутки (до budget); история заказчика — его договоры за 2 года. Сеть — здесь, вне транзакций;
 * запись — {@link MarketContractWriter}. Пауза 300 мс; 401 / 403 / 429 — стоп (токен общий с продом).
 */
@Service
public class MarketCollector {

    private static final Logger log = LoggerFactory.getLogger(MarketCollector.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static final String DAILY = "daily";
    static final int WORD_PAGES = 5;
    static final int CONTRACT_BATCH = 100;
    static final int LOTS_BATCH = 200;
    static final long PAUSE_MS = 300;
    static final Duration FRESH = Duration.ofHours(24);
    static final Duration ERROR_BACKOFF = Duration.ofMinutes(10);
    static final int YEARS = 2;
    static final int DOWN_STOP = 3;

    /** Состояние запроса: ok — данные свежие; fetchedAt — когда обновлялись; error — что не вышло. */
    public record Lookup(boolean ok, OffsetDateTime fetchedAt, String error) {}

    public record Collected(int rows, boolean complete) {}

    private final GoszakupResultsClient client;
    private final MarketContractWriter writer;
    private final GoszakupSubjects subjects;
    private final Supplier<List<String>> words;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public MarketCollector(GoszakupResultsClient client, MarketContractWriter writer, GoszakupSubjects subjects, CatalogSource catalog) {
        this(client, writer, subjects, () -> catalog.get().groups().stream().flatMap(g -> g.searchWords().stream())
                .map(w -> w.strip().toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty()).distinct().toList(), Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public MarketCollector(GoszakupResultsClient client, MarketContractWriter writer, GoszakupSubjects subjects, Supplier<List<String>> words,
                           Clock clock, UpstreamRetry.Sleeper sleeper) {
        this.client = client;
        this.writer = writer;
        this.subjects = subjects;
        this.words = words;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public static String key(String prefix, String value) {
        String k = prefix + value;
        return k.length() <= 150 ? k : k.substring(0, 150);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(Market.KZ.zone()));
    }

    /** Раз в сутки (7:15): «от» — прошлый удачный сбор минус 3 дня, впервые — 2 года назад. */
    public void collectDaily(ImportSummary sum) {
        if (!client.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = today();
        LocalDate from = writer.lookup(DAILY).filter(l -> l.getError() == null)
                .map(l -> l.getFetchedAt().atZoneSameInstant(Market.KZ.zone()).toLocalDate().minusDays(3))
                .orElse(today.minusYears(YEARS));
        List<String> list = words.get();
        int rows = 0, down = 0;
        for (String w : list) {
            try {
                rows += collectWord(w, from, today, null).rows();
                sum.setUpdated(sum.getUpdated() + 1);
                down = 0;
            } catch (RuntimeException e) {
                if (ProductWatchService.stopping(e)) {
                    sum.addError("сбор остановлен: " + ErrorText.of(e));
                    break;
                }
                sum.addError("«" + w + "»: " + ErrorText.of(e));
                log.warn("Цены рынка: слово «{}»: {}", w, ErrorText.of(e));
                if (++down >= DOWN_STOP) break;
            }
        }
        if (sum.getErrors() == 0) writer.mark(DAILY, rows, null, now);
        sum.setFetched(list.size());
        sum.setCreated(rows);
        sum.setMessage("Цены рынка: слов " + list.size() + " с " + from.format(DATE) + ", строк договоров " + rows
                + (sum.getErrors() > 0 ? "; ошибок " + sum.getErrors() + ": " + sum.getLastError() : ""));
    }

    /** Живой запрос по слову (спека §9): свежее суток — без площадки; сбой — повтор не раньше 10 мин. */
    public Lookup ensureWord(String word, Duration budget) {
        String w = word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
        if (w.length() < 3) return new Lookup(false, null, "слово слишком короткое");
        return ensure(key("w:", w), () -> {
            LocalDate today = today();
            return collectWord(w, today.minusYears(YEARS), today, clock.instant().plus(budget));
        });
    }

    /** История заказчика (спека §8): его договоры за 2 года → строки набора; свежесть — сутки. */
    public Lookup ensureCustomer(String bin) {
        if (bin == null || !bin.matches("\\d{12}")) return new Lookup(false, null, "БИН заказчика неизвестен");
        return ensure(key("c:", bin), () -> {
            LocalDate today = today();
            List<ContractRow> rows = call(() -> client.contractsByCustomer(bin, today.minusYears(YEARS), today));
            List<Long> lotIds = rows.stream().flatMap(c -> c.units().stream()).map(ContractUnitRow::lotId).filter(Objects::nonNull)
                    .distinct().toList();
            List<ClosedLot> lots = new ArrayList<>();
            for (int i = 0; i < lotIds.size(); i += LOTS_BATCH) {
                List<Long> batch = lotIds.subList(i, Math.min(lotIds.size(), i + LOTS_BATCH));
                lots.addAll(call(() -> client.lotsByIds(batch)));
            }
            subjects.names(rows.stream().map(ContractRow::supplierBin).filter(Objects::nonNull).toList(), 30);
            return new Collected(writer.upsert(lots, rows, OffsetDateTime.now(clock)), true);
        });
    }

    private Lookup ensure(String key, Supplier<Collected> work) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<MarketLookup> prev = writer.lookup(key);
        if (prev.isPresent() && fresh(prev.get(), now)) {
            return new Lookup(prev.get().getError() == null, prev.get().getFetchedAt(), prev.get().getError());
        }
        OffsetDateTime before = prev.map(MarketLookup::getFetchedAt).orElse(null);
        if (!client.isConfigured()) return new Lookup(false, before, "токен goszakup не настроен");
        try {
            Collected c = work.get();
            writer.mark(key, c.rows(), c.complete() ? null : "собрано не всё — обновится при следующем открытии", now);
            return new Lookup(c.complete(), now, c.complete() ? null : "собрано не всё — обновится позже");
        } catch (RuntimeException e) {
            String err = "не удалось обновить: " + ErrorText.of(e);
            writer.mark(key, 0, err, now);
            log.warn("Цены рынка: {} — {}", key, ErrorText.of(e));
            return new Lookup(false, before, err);
        }
    }

    static boolean fresh(MarketLookup l, OffsetDateTime now) {
        return l.getFetchedAt().isAfter(now.minus(l.getError() == null ? FRESH : ERROR_BACKOFF));
    }

    /** Слово: состоявшиеся лоты (до 5 страниц) → договоры пачками по 100 объявлений → строки; deadline — не дольше. */
    Collected collectWord(String word, LocalDate from, LocalDate to, Instant deadline) {
        List<ClosedLot> lots = new ArrayList<>();
        Long after = null;
        boolean complete = true;
        for (int page = 0; page < WORD_PAGES; page++) {
            if (late(deadline)) { complete = false; break; }
            Long a = after;
            SearchPage<ClosedLot> p = call(() -> client.closedLots(word, from, to, a));
            lots.addAll(p.items());
            after = p.nextAfter();
            if (after == null) break;
        }
        List<Long> buys = lots.stream().map(ClosedLot::buyId).filter(Objects::nonNull).distinct().toList();
        int n = 0;
        for (int i = 0; i < buys.size(); i += CONTRACT_BATCH) {
            if (late(deadline)) { complete = false; break; }
            List<Long> batch = buys.subList(i, Math.min(buys.size(), i + CONTRACT_BATCH));
            List<ContractRow> rows = call(() -> client.contractsByBuyIds(batch));
            Set<Long> inBatch = new HashSet<>(batch);
            n += writer.upsert(lots.stream().filter(l -> inBatch.contains(l.buyId())).toList(), rows, OffsetDateTime.now(clock));
        }
        return new Collected(n, complete);
    }

    private boolean late(Instant deadline) {
        return deadline != null && clock.instant().isAfter(deadline);
    }

    private <T> T call(Supplier<T> s) {
        try {
            return UpstreamRetry.call(sleeper, s);
        } finally {
            try {
                sleeper.sleep(PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

- [ ] **Step 6: История заказчика**

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Договоры заказчика за 2 года (спека 2026-10-22-tender-results-market-prices §8): сначала той же категории, потом медизделия. */
public record CustomerHistory(String bin, List<Row> rows, OffsetDateTime fetchedAt, String error) {
    public record Row(LocalDate signedAt, String name, BigDecimal quantity, String unit, BigDecimal unitPrice, String supplierName,
                      String region, boolean sameCategory, String anno) {}
}
```

```java
package com.vladoose.nir.service.market;

import com.vladoose.nir.dto.response.CustomerHistory;
import com.vladoose.nir.entity.GoszakupSubject;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MarketContract;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.repository.MarketContractRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** История заказчика по нажатию (спека §8): обновить его договоры (сутки), показать до 30 строк — своя категория, медизделия. */
@Service
public class CustomerHistoryService {

    static final int MAX_ROWS = 30;

    private final MarketCollector collector;
    private final MarketContractRepository contracts;
    private final GoszakupSubjectRepository subjects;
    private final Clock clock;

    @Autowired
    public CustomerHistoryService(MarketCollector collector, MarketContractRepository contracts, GoszakupSubjectRepository subjects) {
        this(collector, contracts, subjects, Clock.systemUTC());
    }

    public CustomerHistoryService(MarketCollector collector, MarketContractRepository contracts, GoszakupSubjectRepository subjects,
                                  Clock clock) {
        this.collector = collector;
        this.contracts = contracts;
        this.subjects = subjects;
        this.clock = clock;
    }

    public CustomerHistory forTender(Tender t) {
        String bin = t.getCustomerBin();
        if (bin == null || !bin.matches("\\d{12}")) return new CustomerHistory(bin, List.of(), null, "БИН заказчика неизвестен");
        MarketCollector.Lookup l = collector.ensureCustomer(bin);
        Set<String> codes = t.getLots().stream().map(TenderLot::getEnstruCode).filter(Objects::nonNull).collect(Collectors.toSet());
        LocalDate from = LocalDate.now(clock.withZone(Market.KZ.zone())).minusYears(MarketCollector.YEARS);
        List<MarketContract> all = contracts.findByCustomerBinAndSignedAtGreaterThanEqualOrderBySignedAtDesc(bin, from);
        List<MarketContract> same = all.stream().filter(m -> m.getEnstruCode() != null && codes.contains(m.getEnstruCode())).toList();
        List<MarketContract> medical = all.stream().filter(m -> !same.contains(m))
                .filter(m -> MedicalGoodsVocabulary.hasDeviceStrong(text(m))).toList();
        List<MarketContract> shown = Stream.concat(same.stream(), medical.stream()).limit(MAX_ROWS).toList();
        Map<String, String> names = subjects.findByBinIn(shown.stream().map(MarketContract::getSupplierBin).filter(Objects::nonNull)
                        .collect(Collectors.toSet())).stream().filter(s -> s.getNameRu() != null)
                .collect(Collectors.toMap(GoszakupSubject::getBin, GoszakupSubject::getNameRu, (a, b) -> a));
        List<CustomerHistory.Row> rows = shown.stream().map(m -> new CustomerHistory.Row(m.getSignedAt(), m.getName(), m.getQuantity(),
                m.getUnit(), m.getUnitPrice(), m.getSupplierBin() == null ? null : names.getOrDefault(m.getSupplierBin(), "БИН " + m.getSupplierBin()),
                m.getRegion(), same.contains(m), m.getAnno())).toList();
        return new CustomerHistory(bin, rows, l.fetchedAt(), l.ok() ? null : l.error());
    }

    private static String text(MarketContract m) {
        return String.join(" ", Objects.toString(m.getName(), ""), Objects.toString(m.getDescription(), ""),
                Objects.toString(m.getExtraDesc(), ""));
    }
}
```

`TenderResultsController.java` — поле и параметр конструктора `CustomerHistoryService history` (последним); путь:

```java
    /** История заказчика по нажатию (спека §8): обновление его договоров на площадке — до нескольких секунд. */
    @GetMapping("/tenders/{id}/customer-history")
    public CustomerHistory customerHistory(@PathVariable Long id) {
        Tender t = tenders.findPublic(id);
        if (t.getMarket() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        return history.forTender(t);
    }
```

- [ ] **Step 7: Сбор по расписанию** — `TenderResultsScheduler.java`:
  - поле `private final MarketCollector market;`, параметр конструкторов после `outage` (`MarketCollector market`), `@Value("${tender-results.market-cron:}") String marketCron` после `digestCron` (в package-private конструкторе — тоже после `digestCron`); поля `marketCron`, `AtomicBoolean marketQueued`, `volatile Instant lastMarketAt`, `volatile ImportSummary lastMarket`;
  - `static final String DEFAULT_MARKET_CRON = "0 15 7 * * *";`
  - `Status` → `record Status(boolean enabled, boolean running, String current, Instant lastResultsAt, ImportSummary lastResults, Instant lastDigestAt, Instant lastMarketAt, ImportSummary lastMarket)`; `status()` — с двумя новыми полями;
  - в `configureTasks`: `CronSchedules.register(registrar, "Сбор цен рынка", marketCron, DEFAULT_MARKET_CRON, z, this::startMarket, log);`
  - метод:

```java
    /** Сбор цен рынка (спека §9): раз в сутки по словам групп «нашей продукции»; два упавших подряд — «⚠️ Сбор цен рынка не работает». */
    public Status startMarket() {
        if (!enabled || !marketQueued.compareAndSet(false, true)) return status();
        ImportSummary sum = new ImportSummary();
        lastMarket = sum;
        executor.execute(() -> runTask("сбор цен рынка", () -> {
            try {
                market.collectDaily(sum);
            } finally {
                lastMarketAt = Instant.now();
                marketQueued.set(false);
                report("market-prices", "Сбор цен рынка", sum);
            }
        }));
        return status();
    }
```

  - `report(ImportSummary sum)` → `report(String key, String title, ImportSummary sum)` (в `startResults` — `report("tender-results", "Проверка итогов тендеров", sum)`), внутри — `title` и `key` вместо констант.

`application.yaml` — в `tender-results` после `digest-cron`:

```yaml
  market-cron: ${TENDER_RESULTS_MARKET_CRON:}    # пусто — сбор цен рынка в 7:15 ежедневно
```

`TenderResultsSchedulerTest.java`: поле `MarketCollector market = mock(MarketCollector.class);`, помощник:

```java
    private TenderResultsScheduler scheduler(boolean enabled, String cron, String digest, Duration wait) {
        return new TenderResultsScheduler(results, notifier, outage, market, enabled, cron, digest, "", "", wait);
    }
```

в `registration_defaultsDashTypo` первая проверка — `hasSize(3)`, вторая (`part`: опечатка и «-», сбор цен — по умолчанию) — `hasSize(1)`; добавить тест:

```java
    @Test
    void market_runsCollector_reportsOwnKey() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterProductRun(eq("market-prices"), any(), any());
        scheduler(true, "", "", Duration.ofSeconds(1)).startMarket();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(market).collectDaily(any());
        verify(outage).afterProductRun(eq("market-prices"), eq("Сбор цен рынка"), any());
    }
```

(импорт `com.vladoose.nir.service.market.MarketCollector`).

- [ ] **Step 8: Запустить — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.market.*' --tests 'com.vladoose.nir.service.tenderresults.*' --tests 'com.vladoose.nir.tenderresults.*' 2>&1 | tail -20` (sandbox off)
Expected: PASS.

- [ ] **Step 9: Мутации**

1. `MarketContractWriter.upsert`: убрать `if (!c.concluded()) continue;` → `daily_rowFromLotAndLatestConcludedContract` красный (цена 1).
2. `collectDaily`: «от» всегда 2 года назад → `daily_fromLastSuccessMinus3Days` красный.
3. `budget`: без проверки количества → `noQuantity_noBudget` красный (NPE).
4. `fresh`: без `ERROR_BACKOFF` (ошибка — всегда не свежо) → `ensureWord_freshNoCall_errorBackoff` красный.
5. `CustomerHistoryService`: без фильтра медизделий → `sameCategoryFirst_thenMedical_noOffice` красный.

- [ ] **Step 10: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/main/resources/application.yaml src/test/java/com/vladoose/nir/market \
  src/test/java/com/vladoose/nir/tenderresults/FakeGoszakupResultsClient.java \
  src/test/java/com/vladoose/nir/service/tenderresults/TenderResultsSchedulerTest.java && git commit -m "$(cat <<'EOF'
feat(market): набор цен рынка из договоров goszakup — ежедневный сбор, живой запрос, история заказчика

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Цены рынка — расчёт, категория по тексту, путь `/api/market-prices`, строка «Рынок» у лота

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/market/MarketStats.java`, `MarketCategories.java`, `MarketTexts.java`, `MarketPriceService.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/MarketPriceResponse.java`, `MarketLotSummary.java`, `MarketHint.java`, `src/main/java/com/vladoose/nir/dto/request/MarketHintsRequest.java`
- Create: `src/main/java/com/vladoose/nir/controller/MarketPriceController.java`
- Modify: `src/main/java/com/vladoose/nir/service/market/MarketContractWriter.java` (`fillLot`; конструктор — ещё `TenderLotRepository`), `src/main/java/com/vladoose/nir/repository/TenderLotBidRepository.java` (`findBidPrices`)
- Modify: `src/main/java/com/vladoose/nir/dto/response/TenderLotResponse.java` (`market`), `src/main/java/com/vladoose/nir/mapper/TenderLotMapper.java` (`market` — ignore), `src/main/java/com/vladoose/nir/controller/TenderController.java` (`getLots` — строка рынка)
- Test: `src/test/java/com/vladoose/nir/market/MarketStatsTest.java`, `MarketCategoriesTest.java`, `MarketPriceServiceTest.java`, `MarketPriceApiTest.java`

**Interfaces:**
- Consumes: задача 10 (`MarketCollector.ensureWord`, `Lookup`, `MarketContractWriter`, `MarketContract`), `ResultTexts.money/unit`, `MarketContractRepository.findByEnstruCodeAndSignedAtGreaterThanEqual/findCategories`, `GoszakupResultsClient.lotsByIds`, `TenderService.findPublic`, `PrivateRequestService.findById`.
- Produces: `record MarketStats(int count, BigDecimal median, BigDecimal p25, BigDecimal p75, BigDecimal min, BigDecimal max, BigDecimal budgetMedian, BigDecimal lotBudget, Integer budgetDiffPct)` + `static of(List<BigDecimal> prices, List<BigDecimal> budgets, BigDecimal lotBudget)`, `static BigDecimal percentile(List<BigDecimal> sorted, int pct)`; `MarketCategories.record Category(String code, String name, String description, int count)`, `static String keyword(String text)`, `static Category pick(String text, List<Category>)`; `MarketTexts.lotLine(MarketStats, String unit)`, `hintLine(MarketStats)`, `telegramLine(MarketStats)`, `contracts(int)`; `MarketPriceService.forLot(long lotId, String code, String unit, String filter)`, `forText(String text, String code, String unit, String filter)`, `lotSummaries(Collection<TenderLot>)`, `hints(List<String>)`, `LIVE_BUDGET = 20 с`; DTO `MarketPriceResponse(Category category, List<Category> alternatives, String unit, List<String> units, MarketStats stats, Bids bids, List<Row> rows, OffsetDateTime fetchedAt, boolean stale, String error)` (`Bids(int count, BigDecimal median)`, `Row(LocalDate signedAt, String region, String customer, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal budgetUnitPrice, String supplierName, String anno, String name)`; `Category` — `MarketCategories.Category`), `MarketLotSummary(String text, int count, BigDecimal median, String code)`, `MarketHint(String text, String line, int count, BigDecimal median, String code)`, `MarketHintsRequest(List<String> texts)`; `TenderLotResponse.market`; `MarketContractWriter.fillLot(long lotId, ClosedLot)`; `TenderLotBidRepository.findBidPrices(String code, LocalDateTime from)`; API `GET /api/market-prices?lotId=|q=&code=&unit=&filter=`, `POST /api/market-prices/hints {texts}`.

- [ ] **Step 1: Тесты расчёта и категорий**

```java
package com.vladoose.nir.market;

import com.vladoose.nir.service.market.MarketStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Расчёт цены рынка (спека 2026-10-22-tender-results-market-prices §9; Review Focus 5). */
class MarketStatsTest {

    private static List<BigDecimal> list(String... v) {
        return java.util.Arrays.stream(v).map(BigDecimal::new).toList();
    }

    @Test
    void oneRow_isEverything() {
        MarketStats s = MarketStats.of(list("1340000"), list(), null);
        assertThat(s.count()).isEqualTo(1);
        assertThat(s.median()).isEqualByComparingTo("1340000");
        assertThat(s.p25()).isEqualByComparingTo("1340000");
        assertThat(s.p75()).isEqualByComparingTo("1340000");
        assertThat(s.budgetDiffPct()).isNull();
    }

    @Test
    void medianPercentilesBudget() {
        MarketStats s = MarketStats.of(list("400", "100", "300", "200"), list("500", "300", "100"), new BigDecimal("265"));
        assertThat(s.median()).isEqualByComparingTo("250");
        assertThat(s.p25()).isEqualByComparingTo("175");
        assertThat(s.p75()).isEqualByComparingTo("325");
        assertThat(s.min()).isEqualByComparingTo("100");
        assertThat(s.max()).isEqualByComparingTo("400");
        assertThat(s.budgetMedian()).isEqualByComparingTo("300");
        assertThat(s.budgetDiffPct()).isEqualTo(6);
    }

    @Test
    void empty() {
        MarketStats s = MarketStats.of(List.of(), List.of(), new BigDecimal("1"));
        assertThat(s.count()).isZero();
        assertThat(s.median()).isNull();
    }
}
```

```java
package com.vladoose.nir.market;

import com.vladoose.nir.service.market.MarketCategories;
import com.vladoose.nir.service.market.MarketCategories.Category;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Категория по тексту (спека §9): ключевое слово без общих слов, лучшая — по совпадению слов, при равенстве — где больше договоров. */
class MarketCategoriesTest {

    @Test
    void keyword_skipsGeneric() {
        assertThat(MarketCategories.keyword("Изделие медицинское: Дефибриллятор бифазный")).isEqualTo("дефибриллятор");
        assertThat(MarketCategories.keyword("Аппарат ИВЛ")).isEqualTo("ивл");
        assertThat(MarketCategories.keyword("Набор для")).isNull();
        assertThat(MarketCategories.keyword(null)).isNull();
    }

    @Test
    void pick_byOverlapThenCount() {
        Category implanted = new Category("266011.300.000000", "Дефибриллятор", "имплантируемый кардиовертер", 40);
        Category external = new Category("266011.150.000000", "Дефибриллятор", "для терапии нарушений сердечного ритма", 20);
        assertThat(MarketCategories.pick("Дефибриллятор для терапии нарушений ритма", List.of(implanted, external))).isEqualTo(external);
        assertThat(MarketCategories.pick("Дефибриллятор", List.of(external, implanted))).as("равенство — где больше договоров").isEqualTo(implanted);
        assertThat(MarketCategories.pick("Дефибриллятор", List.of())).isNull();
    }
}
```

- [ ] **Step 2: Тест сервиса**

```java
package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.MarketPriceResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.market.MarketCollector;
import com.vladoose.nir.service.market.MarketContractWriter;
import com.vladoose.nir.service.market.MarketPriceService;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** Цена рынка у лота и по тексту (спека 2026-10-22-tender-results-market-prices §9, §10; Review Focus 5). */
@SpringBootTest
@Transactional
class MarketPriceServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final String CODE = "266011.150.000000";

    @Autowired MarketContractRepository contracts;
    @Autowired MarketLookupRepository lookups;
    @Autowired MarketContractWriter writer;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotBidRepository bids;
    @Autowired TenderLotResultRepository results;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired TenderRepository tenders;

    FakeGoszakupResultsClient fake;
    MarketPriceService service;
    long nextLot = 99500000L;
    long nextTender = 970008000L;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        contracts.deleteAllInBatch();
        lookups.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        MarketCollector collector = new MarketCollector(fake, writer, new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { }),
                List::of, CLOCK, ms -> { });
        service = new MarketPriceService(collector, writer, contracts, lots, bids, subjectRepo, fake, CLOCK);
        // слово «дефибриллятор» — свежее: живого запроса нет
        lookups.saveAndFlush(MarketLookup.builder().lookupKey("w:дефибриллятор").fetchedAt(OffsetDateTime.now(CLOCK)).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private void row(String price, String unit, String extra, String budget) {
        contracts.saveAndFlush(MarketContract.builder().lotExtId(nextLot++).enstruCode(CODE).name("Дефибриллятор")
                .description("для терапии нарушений сердечного ритма").extraDesc(extra).unit(unit).customerBin("990240006109")
                .customerName("ГКП «Больница»").region("Актюбинская область").quantity(BigDecimal.ONE)
                .budgetUnitPrice(budget == null ? null : new BigDecimal(budget)).unitPrice(new BigDecimal(price)).supplierBin("900000000001")
                .signedAt(LocalDate.of(2026, 5, 10)).anno("17000001-1").build());
    }

    private TenderLot lot(String unit, String budget) {
        Tender t = ResultsFixtures.tender(tenders, nextTender++, NOW.plusDays(3), "1-ЗЦП1", "Дефибриллятор бифазный");
        TenderLot l = t.getLots().get(0);
        l.setEnstruCode(CODE);
        l.setPlanUnit(unit);
        l.setMaxCost(new BigDecimal(budget));
        tenders.saveAndFlush(t);
        return l;
    }

    /** Штуки не смешиваются с упаковками; бюджет лота — отклонение от медианы. */
    @Test
    void lot_unitsNotMixed_budgetDiff() {
        row("100", "Штука", null, "110");
        row("200", "Штука", null, "210");
        row("300", "Штука", null, null);
        row("9000", "Упаковка", null, null);
        MarketPriceResponse r = service.forLot(lot("Штука", "212").getId(), null, null, null);
        assertThat(r.unit()).isEqualTo("шт");
        assertThat(r.units()).containsExactly("шт", "уп");
        assertThat(r.stats().count()).isEqualTo(3);
        assertThat(r.stats().median()).isEqualByComparingTo("200");
        assertThat(r.stats().budgetDiffPct()).isEqualTo(6);
        assertThat(r.rows()).hasSize(3);
        assertThat(r.category().code()).isEqualTo(CODE);
        assertThat(fake.calls).as("слово свежее — без площадки").isEmpty();
        MarketPriceResponse packs = service.forLot(lot("Штука", "212").getId(), null, "Упаковка", null);
        assertThat(packs.stats().count()).isEqualTo(1);
    }

    /** Фильтр по слову — числа по отфильтрованным строкам. */
    @Test
    void filterByWord() {
        row("100", "Штука", "автоматический", null);
        row("500", "Штука", "бифазный, с монитором", null);
        MarketPriceResponse r = service.forLot(lot("Штука", "300").getId(), null, null, "монитор");
        assertThat(r.stats().count()).isEqualTo(1);
        assertThat(r.stats().median()).isEqualByComparingTo("500");
    }

    /** Договоров нет — пустая статистика, а не ошибка (Review Focus 5). */
    @Test
    void noRows_emptyStats() {
        MarketPriceResponse r = service.forLot(lot("Штука", "300").getId(), null, null, null);
        assertThat(r.stats().count()).isZero();
        assertThat(r.rows()).isEmpty();
    }

    /** Лот goszakup без кода — код и единица берутся с площадки по id лота и пишутся у лота. */
    @Test
    void lotCodeFilledFromPlatform() {
        row("100", "Штука", null, null);
        Tender t = ResultsFixtures.tender(tenders, 970008900L, NOW.plusDays(3), "1-ЗЦП1", "Дефибриллятор");
        TenderLot l = t.getLots().get(0);
        l.setExtLotId(43999001L);
        tenders.saveAndFlush(t);
        fake.lotsById.put(43999001L, new ClosedLot(43999001L, "1-ЗЦП1", "Дефибриллятор", null, BigDecimal.ONE, BigDecimal.TEN, 1L, "1-1",
                null, null, CODE, null, "Штука", null, null));
        MarketPriceResponse r = service.forLot(l.getId(), null, null, null);
        assertThat(r.stats().count()).isEqualTo(1);
        assertThat(lots.findById(l.getId()).orElseThrow().getEnstruCode()).isEqualTo(CODE);
        assertThat(lots.findById(l.getId()).orElseThrow().getPlanUnit()).isEqualTo("Штука");
    }

    /** По тексту (позиция заявки, строка КП): категория — лучшая среди найденных по слову; другие — на выбор. */
    @Test
    void forText_categoryAndAlternatives() {
        row("100", "Штука", null, null);
        contracts.saveAndFlush(MarketContract.builder().lotExtId(nextLot++).enstruCode("266011.300.000000").name("Дефибриллятор")
                .description("имплантируемый кардиовертер").unit("Штука").unitPrice(new BigDecimal("7500000")).signedAt(LocalDate.of(2026, 3, 1))
                .build());
        MarketPriceResponse r = service.forText("Дефибриллятор для терапии нарушений сердечного ритма", null, null, null);
        assertThat(r.category().code()).isEqualTo(CODE);
        assertThat(r.alternatives()).extracting(c -> c.code()).containsExactly("266011.300.000000");
        assertThat(service.forText("Дефибриллятор", "266011.300.000000", null, null).stats().median()).isEqualByComparingTo("7500000");
    }

    @Test
    void lotSummaryAndHints() {
        row("1340000", "Штука", null, null);
        row("1340000", "Штука", null, null);
        TenderLot l = lot("Штука", "1420000");
        var s = service.lotSummaries(List.of(l)).get(l.getId());
        assertThat(s.text()).isEqualTo("Рынок: медиана 1,34\u00A0млн\u00A0₸ за шт · 2 договора · бюджет здесь 1,42\u00A0млн\u00A0₸ (+6\u00A0%)");
        var hints = service.hints(List.of("Дефибриллятор бифазный", "zz"));
        assertThat(hints.get(0).line()).isEqualTo("госзакуп: медиана 1,34\u00A0млн\u00A0₸ (2 дог.)");
        assertThat(hints.get(1).line()).isNull();
    }

    /** Цены участников протоколов той же категории. */
    @Test
    void bidsOfSameCategory() {
        row("100", "Штука", null, null);
        TenderLot l = lot("Штука", "300");
        TenderLotResult r = results.saveAndFlush(TenderLotResult.builder().tenderLotId(l.getId()).tenderId(l.getTender().getId())
                .outcome(LotOutcome.DONE).resultsAt(LocalDateTime.of(2026, 10, 1, 10, 0)).build());
        bids.saveAndFlush(TenderLotBid.builder().resultId(r.getId()).place(1).unitPrice(new BigDecimal("150")).build());
        bids.saveAndFlush(TenderLotBid.builder().resultId(r.getId()).place(2).unitPrice(new BigDecimal("250")).build());
        bids.saveAndFlush(TenderLotBid.builder().resultId(r.getId()).rejected(true).unitPrice(new BigDecimal("1")).build());
        MarketPriceResponse resp = service.forLot(l.getId(), null, null, null);
        assertThat(resp.bids().count()).isEqualTo(2);
        assertThat(resp.bids().median()).isEqualByComparingTo("200");
    }
}
```

- [ ] **Step 3: Тест пути**

```java
package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.MarketPriceController;
import com.vladoose.nir.dto.request.MarketHintsRequest;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MarketLookupRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.entity.MarketLookup;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Путь цен рынка (спека §13): только KZ, лот чужого рынка — 404, читают все. */
@SpringBootTest
@Transactional
@WithMockUser(roles = "USER")
class MarketPriceApiTest {

    @Autowired MarketPriceController controller;
    @Autowired TenderRepository tenders;
    @Autowired MarketLookupRepository lookups;

    @BeforeEach
    void kz() {
        MarketContext.set(Market.KZ);
        lookups.saveAndFlush(MarketLookup.builder().lookupKey("w:тележка").fetchedAt(OffsetDateTime.now()).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void operatorReads_rf400_otherMarketLot404() {
        Tender t = ResultsFixtures.tender(tenders, 970009001L, NOW.plusDays(3), "1-ЗЦП1", "Тележка");
        assertThat(controller.get(t.getLots().get(0).getId(), null, null, null, null)).isNotNull();
        assertThat(controller.hints(new MarketHintsRequest(List.of("Тележка")))).hasSize(1);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.get(t.getLots().get(0).getId(), null, null, null, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Казахстана");
        MarketContext.set(Market.KZ);
        Tender rf = tenders.saveAndFlush(Tender.builder().tenderNumber("ZZ-RF-" + System.nanoTime()).status("ACTIVE").market(Market.RF).build());
        rf.getLots().add(com.vladoose.nir.entity.TenderLot.builder().tender(rf).equipName("Тележка").build());
        tenders.saveAndFlush(rf);
        assertThatThrownBy(() -> controller.get(rf.getLots().get(0).getId(), null, null, null, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.get(null, " ", null, null, null)).isInstanceOf(BadRequestException.class);
    }
}
```

- [ ] **Step 4: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.market.*' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (нет `MarketStats`, `MarketCategories`, `MarketPriceService`, `MarketPriceController`).

- [ ] **Step 5: Расчёт, категории, тексты**

```java
package com.vladoose.nir.service.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Цена рынка по строкам договоров (спека 2026-10-22-tender-results-market-prices §9): число, медиана, середина рынка (25-й…75-й
 * процентиль), минимум и максимум; медиана бюджета; отклонение бюджета лота от медианы, %. Процентили — линейной интерполяцией;
 * одна строка — она сама (Review Focus 5).
 */
public record MarketStats(int count, BigDecimal median, BigDecimal p25, BigDecimal p75, BigDecimal min, BigDecimal max,
                          BigDecimal budgetMedian, BigDecimal lotBudget, Integer budgetDiffPct) {

    public static MarketStats of(List<BigDecimal> prices, List<BigDecimal> budgets, BigDecimal lotBudget) {
        List<BigDecimal> p = prices.stream().filter(Objects::nonNull).filter(x -> x.signum() > 0).sorted().toList();
        List<BigDecimal> b = budgets.stream().filter(Objects::nonNull).filter(x -> x.signum() > 0).sorted().toList();
        BigDecimal budgetMedian = b.isEmpty() ? null : percentile(b, 50);
        if (p.isEmpty()) return new MarketStats(0, null, null, null, null, null, budgetMedian, lotBudget, null);
        BigDecimal median = percentile(p, 50);
        Integer diff = lotBudget == null || median.signum() == 0 ? null
                : lotBudget.subtract(median).multiply(BigDecimal.valueOf(100)).divide(median, 0, RoundingMode.HALF_UP).intValue();
        return new MarketStats(p.size(), median, percentile(p, 25), percentile(p, 75), p.get(0), p.get(p.size() - 1), budgetMedian,
                lotBudget, diff);
    }

    public static BigDecimal percentile(List<BigDecimal> sorted, int pct) {
        if (sorted.size() == 1) return sorted.get(0).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pos = BigDecimal.valueOf(sorted.size() - 1L).multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        int lo = pos.intValue();
        BigDecimal frac = pos.subtract(BigDecimal.valueOf(lo));
        BigDecimal a = sorted.get(lo);
        BigDecimal c = sorted.get(Math.min(lo + 1, sorted.size() - 1));
        return a.add(c.subtract(a).multiply(frac)).setScale(2, RoundingMode.HALF_UP);
    }
}
```

```java
package com.vladoose.nir.service.market;

import java.util.*;

/**
 * Категория цен по тексту (спека §9): ключевое слово — первое значимое (без общих «изделие», «медицинский», «аппарат», «набор»…),
 * категории набора с этим словом — лучшая по совпадению слов текста с названием и описанием, при равенстве — где больше договоров.
 */
public final class MarketCategories {

    public record Category(String code, String name, String description, int count) {}

    private static final Set<String> GENERIC = Set.of("изделие", "изделия", "изделий", "медицинский", "медицинская", "медицинское",
            "медицинские", "медицинских", "медицинского", "медицинской", "аппарат", "аппарата", "аппараты", "набор", "набора", "наборы",
            "система", "системы", "комплект", "комплекта", "прибор", "приборы", "оборудование", "оборудования", "устройство",
            "устройства", "товар", "товары", "расходный", "расходные", "материал", "материалы", "принадлежности", "одноразовый",
            "одноразовые", "стерильный", "стерильные", "для", "при", "под", "без", "или", "and", "for", "with");

    private MarketCategories() {}

    public static String keyword(String text) {
        if (text == null) return null;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.length() >= 3 && !GENERIC.contains(w)) return w;
        }
        return null;
    }

    public static Category pick(String text, List<Category> cats) {
        if (cats.isEmpty()) return null;
        Set<String> t = stems(text);
        return cats.stream().max(Comparator.comparingInt((Category c) -> overlap(t, stems(c.name() + " " + Objects.toString(c.description(), ""))))
                .thenComparingInt(Category::count)).orElse(null);
    }

    static Set<String> stems(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !GENERIC.contains(w)) out.add(w.length() > 5 ? w.substring(0, 5) : w);
        }
        return out;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String x : a) if (b.contains(x)) n++;
        return n;
    }
}
```

```java
package com.vladoose.nir.service.market;

import com.vladoose.nir.service.tenderresults.ResultTexts;

/** Строки цены рынка (спека §10): у лота, подсказка, Telegram. */
public final class MarketTexts {

    private static final char NBSP = '\u00A0';

    private MarketTexts() {}

    /** «Рынок: медиана 1,34 млн ₸ за шт · 69 договоров · бюджет здесь 1,42 млн ₸ (+6 %)». */
    public static String lotLine(MarketStats s, String unit) {
        if (s.count() == 0) return null;
        StringBuilder b = new StringBuilder("Рынок: медиана ").append(ResultTexts.money(s.median())).append(" за ").append(unit)
                .append(" · ").append(contracts(s.count()));
        if (s.lotBudget() != null && s.budgetDiffPct() != null) {
            b.append(" · бюджет здесь ").append(ResultTexts.money(s.lotBudget())).append(" (").append(pct(s.budgetDiffPct())).append(')');
        }
        return b.toString();
    }

    /** «госзакуп: медиана 1,34 млн ₸ (69 дог.)». */
    public static String hintLine(MarketStats s) {
        return s.count() == 0 ? null : "госзакуп: медиана " + ResultTexts.money(s.median()) + " (" + s.count() + " дог.)";
    }

    /** «рынок: медиана 1,34 млн ₸ (69 дог.) · бюджет 1,42 млн ₸». */
    public static String telegramLine(MarketStats s) {
        if (s.count() == 0) return null;
        return "рынок: медиана " + ResultTexts.money(s.median()) + " (" + s.count() + " дог.)"
                + (s.lotBudget() == null ? "" : " · бюджет " + ResultTexts.money(s.lotBudget()));
    }

    /** «1 договор», «2 договора», «5 договоров». */
    public static String contracts(int n) {
        int d = n % 10, h = n % 100;
        String w = d == 1 && h != 11 ? "договор" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "договора" : "договоров";
        return n + " " + w;
    }

    static String pct(int p) {
        return (p < 0 ? "−" + (-p) : "+" + p) + NBSP + "%";
    }
}
```

- [ ] **Step 6: DTO и запись полей лота**

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.service.market.MarketCategories.Category;
import com.vladoose.nir.service.market.MarketStats;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Цена рынка (спека 2026-10-22-tender-results-market-prices §13): категория и другие найденные, единица, расчёт, строки. */
public record MarketPriceResponse(Category category, List<Category> alternatives, String unit, List<String> units, MarketStats stats,
                                  Bids bids, List<Row> rows, OffsetDateTime fetchedAt, boolean stale, String error) {

    /** Цены участников разобранных протоколов той же категории и единицы. */
    public record Bids(int count, BigDecimal median) {}

    public record Row(LocalDate signedAt, String region, String customer, BigDecimal quantity, String unit, BigDecimal unitPrice,
                      BigDecimal budgetUnitPrice, String supplierName, String anno, String name) {}
}
```

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;

/** Строка «Рынок: …» у лота (только из набора, без запросов к площадке — решение 10). */
public record MarketLotSummary(String text, int count, BigDecimal median, String code) {}
```

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;

/** Подсказка цены по тексту (окно «Запросить цены», строка КП, товар сайта): line пусто — договоров нет. */
public record MarketHint(String text, String line, int count, BigDecimal median, String code) {}
```

```java
package com.vladoose.nir.dto.request;

import java.util.List;

public record MarketHintsRequest(List<String> texts) {}
```

`MarketContractWriter.java`: поле и параметр конструктора `TenderLotRepository lots` (последним); метод:

```java
    /** Код ЕНС ТРУ и единица лота с площадки (живой запрос цены у лота, у которого их ещё нет). */
    @Transactional
    public void fillLot(long lotId, ClosedLot l) {
        lots.findById(lotId).ifPresent(lot -> {
            if (lot.getEnstruCode() == null && l.enstruCode() != null) lot.setEnstruCode(cut(l.enstruCode(), 30));
            if (lot.getPlanUnit() == null && l.unit() != null) lot.setPlanUnit(cut(l.unit(), 50));
        });
    }
```

`TenderLotBidRepository.java` — добавить (импорт `java.time.LocalDateTime`):

```java
    /** Цены участников протоколов категории (спека §9): цена за единицу и единица пункта плана лота. */
    @Query(value = """
            SELECT b.unit_price, l.plan_unit FROM tender_lot_bid b
            JOIN tender_lot_result r ON r.id = b.result_id
            JOIN tender_lot l ON l.id = r.tender_lot_id
            WHERE l.enstru_code = :code AND b.rejected = false AND b.unit_price IS NOT NULL
              AND COALESCE(r.results_at, CAST(r.created_at AS timestamp)) >= :from
            """, nativeQuery = true)
    List<Object[]> findBidPrices(@Param("code") String code, @Param("from") LocalDateTime from);
```

`TenderLotResponse.java` — после `result`:

```java
    /** «Рынок: медиана … · N договоров · бюджет здесь …» (только KZ, только из набора). */
    private MarketLotSummary market;
```

`TenderLotMapper.toResponse` — `@Mapping(target = "market", ignore = true)`.

- [ ] **Step 7: Сервис цены рынка**

```java
package com.vladoose.nir.service.market;

import com.vladoose.nir.dto.response.MarketHint;
import com.vladoose.nir.dto.response.MarketLotSummary;
import com.vladoose.nir.dto.response.MarketPriceResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.dto.ClosedLot;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.repository.MarketContractRepository;
import com.vladoose.nir.repository.TenderLotBidRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.market.MarketCategories.Category;
import com.vladoose.nir.service.tenderresults.ResultTexts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Цена рынка (спека 2026-10-22-tender-results-market-prices §9, §10): «такой же товар» — та же категория ЕНС ТРУ и та же единица,
 * 2 года. Панель (лот, текст) — с живым запросом слова, не обновлявшегося сутки (до 20 с); сводки у лотов и подсказки — только из
 * набора. Метод не транзакционный: сеть — вне транзакций, чтение — репозиториями.
 */
@Service
public class MarketPriceService {

    static final Duration LIVE_BUDGET = Duration.ofSeconds(20);
    static final int ROWS = 100;
    static final int ALTERNATIVES = 5;
    static final int MAX_HINTS = 200;
    private static final MarketCollector.Lookup NONE = new MarketCollector.Lookup(false, null, "не из чего искать");

    private final MarketCollector collector;
    private final MarketContractWriter writer;
    private final MarketContractRepository contracts;
    private final TenderLotRepository lots;
    private final TenderLotBidRepository bids;
    private final GoszakupSubjectRepository subjects;
    private final GoszakupResultsClient client;
    private final Clock clock;

    @Autowired
    public MarketPriceService(MarketCollector collector, MarketContractWriter writer, MarketContractRepository contracts,
                              TenderLotRepository lots, TenderLotBidRepository bids, GoszakupSubjectRepository subjects,
                              GoszakupResultsClient client) {
        this(collector, writer, contracts, lots, bids, subjects, client, Clock.systemUTC());
    }

    public MarketPriceService(MarketCollector collector, MarketContractWriter writer, MarketContractRepository contracts,
                              TenderLotRepository lots, TenderLotBidRepository bids, GoszakupSubjectRepository subjects,
                              GoszakupResultsClient client, Clock clock) {
        this.collector = collector;
        this.writer = writer;
        this.contracts = contracts;
        this.lots = lots;
        this.bids = bids;
        this.subjects = subjects;
        this.client = client;
        this.clock = clock;
    }

    private LocalDate from() {
        return LocalDate.now(clock.withZone(Market.KZ.zone())).minusYears(MarketCollector.YEARS);
    }

    /** У лота (гард рынка — у вызывающего): код площадки, нет — с площадки по id лота, нет и его — по названию. */
    public MarketPriceResponse forLot(long lotId, String code, String unit, String filter) {
        TenderLot lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Лот не найден"));
        Tender t = lot.getTender();
        String lotCode = lot.getEnstruCode();
        if (lotCode == null && t.getPlatform() == TenderPlatform.GOSZAKUP && lot.getExtLotId() != null && client.isConfigured()) {
            try {
                List<ClosedLot> found = client.lotsByIds(List.of(lot.getExtLotId()));
                if (!found.isEmpty()) {
                    writer.fillLot(lotId, found.get(0));
                    lotCode = found.get(0).enstruCode();
                }
            } catch (RuntimeException e) {
                // категория — по названию лота
            }
        }
        String kw = MarketCategories.keyword(lot.getEquipName());
        MarketCollector.Lookup l = kw == null ? NONE : collector.ensureWord(kw, LIVE_BUDGET);
        List<Category> cats = categories(kw);
        String eff = code != null && !code.isBlank() ? code.strip() : lotCode;
        Category cat = eff != null ? category(eff, cats) : MarketCategories.pick(lot.getEquipName(), cats);
        BigDecimal budget = t.getSource() == Source.PUBLIC_TENDER ? lot.getMaxCost() : null;
        String lotUnit = lot.getUnit() != null && !lot.getUnit().isBlank() ? lot.getUnit() : lot.getPlanUnit();
        return build(cat, cats, unit, lotUnit, filter, budget, l);
    }

    /** По тексту (позиция заявки, строка КП, товар сайта). */
    public MarketPriceResponse forText(String text, String code, String unit, String filter) {
        String kw = MarketCategories.keyword(text);
        MarketCollector.Lookup l = kw == null ? NONE : collector.ensureWord(kw, LIVE_BUDGET);
        List<Category> cats = categories(kw);
        Category cat = code != null && !code.isBlank() ? category(code.strip(), cats) : MarketCategories.pick(text, cats);
        return build(cat, cats, unit, null, filter, null, l);
    }

    /** Сводки «Рынок: …» у лотов карточки — только из набора (решение 10). */
    @Transactional(readOnly = true)
    public Map<Long, MarketLotSummary> lotSummaries(Collection<TenderLot> list) {
        Map<String, List<MarketContract>> byCode = new HashMap<>();
        Map<Long, MarketLotSummary> out = new HashMap<>();
        for (TenderLot l : list) {
            if (l.getEnstruCode() == null) continue;
            List<MarketContract> rows = byCode.computeIfAbsent(l.getEnstruCode(), c -> contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(c, from()));
            if (rows.isEmpty()) continue;
            String unit = effectiveUnit(null, l.getUnit() != null && !l.getUnit().isBlank() ? l.getUnit() : l.getPlanUnit(), units(rows));
            List<MarketContract> same = rows.stream().filter(r -> norm(r.getUnit()).equals(unit)).toList();
            MarketStats st = MarketStats.of(prices(same), budgets(same), l.getMaxCost());
            if (st.count() > 0) out.put(l.getId(), new MarketLotSummary(MarketTexts.lotLine(st, unit), st.count(), st.median(), l.getEnstruCode()));
        }
        return out;
    }

    /** Подсказки по текстам — только из набора, до 200 (окно «Запросить цены», строки КП, товары сайта). */
    @Transactional(readOnly = true)
    public List<MarketHint> hints(List<String> texts) {
        Map<String, List<Category>> cats = new HashMap<>();
        Map<String, List<MarketContract>> rowsByCode = new HashMap<>();
        List<MarketHint> out = new ArrayList<>();
        for (String text : texts.stream().limit(MAX_HINTS).toList()) {
            String kw = MarketCategories.keyword(text);
            Category cat = kw == null ? null : MarketCategories.pick(text, cats.computeIfAbsent(kw, this::categories));
            if (cat == null) {
                out.add(new MarketHint(text, null, 0, null, null));
                continue;
            }
            List<MarketContract> rows = rowsByCode.computeIfAbsent(cat.code(), c -> contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(c, from()));
            String unit = effectiveUnit(null, null, units(rows));
            List<MarketContract> same = rows.stream().filter(r -> norm(r.getUnit()).equals(unit)).toList();
            MarketStats st = MarketStats.of(prices(same), budgets(same), null);
            out.add(new MarketHint(text, MarketTexts.hintLine(st), st.count(), st.median(), cat.code()));
        }
        return out;
    }

    private List<Category> categories(String kw) {
        if (kw == null) return List.of();
        List<Category> out = new ArrayList<>();
        for (Object[] row : contracts.findCategories("%" + kw + "%", from())) {
            out.add(new Category((String) row[0], (String) row[1], (String) row[2], ((Number) row[3]).intValue()));
        }
        return out;
    }

    private Category category(String code, List<Category> cats) {
        return cats.stream().filter(c -> c.code().equals(code)).findFirst().orElseGet(() -> {
            List<MarketContract> rows = contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(code, from());
            return rows.isEmpty() ? new Category(code, null, null, 0)
                    : new Category(code, rows.get(0).getName(), rows.get(0).getDescription(), rows.size());
        });
    }

    private MarketPriceResponse build(Category cat, List<Category> cats, String unit, String lotUnit, String filter, BigDecimal budget,
                                      MarketCollector.Lookup l) {
        List<Category> alternatives = cats.stream().filter(c -> cat == null || !c.code().equals(cat.code())).limit(ALTERNATIVES).toList();
        if (cat == null) {
            return new MarketPriceResponse(null, alternatives, null, List.of(), MarketStats.of(List.of(), List.of(), budget),
                    new MarketPriceResponse.Bids(0, null), List.of(), l.fetchedAt(), !l.ok(), l.error());
        }
        List<MarketContract> rows = contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(cat.code(), from());
        List<String> units = units(rows);
        String eff = effectiveUnit(unit, lotUnit, units);
        List<MarketContract> same = rows.stream().filter(r -> norm(r.getUnit()).equals(eff)).toList();
        String f = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        if (!f.isEmpty()) same = same.stream().filter(r -> text(r).contains(f)).toList();
        MarketStats st = MarketStats.of(prices(same), budgets(same), budget);
        Map<String, String> names = subjects.findByBinIn(same.stream().map(MarketContract::getSupplierBin).filter(Objects::nonNull)
                        .collect(Collectors.toSet())).stream().filter(s -> s.getNameRu() != null)
                .collect(Collectors.toMap(GoszakupSubject::getBin, GoszakupSubject::getNameRu, (a, b) -> a));
        List<MarketPriceResponse.Row> out = same.stream()
                .sorted(Comparator.comparing(MarketContract::getSignedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(ROWS)
                .map(r -> new MarketPriceResponse.Row(r.getSignedAt(), r.getRegion(), r.getCustomerName(), r.getQuantity(), norm(r.getUnit()),
                        r.getUnitPrice(), r.getBudgetUnitPrice(),
                        r.getSupplierBin() == null ? null : names.getOrDefault(r.getSupplierBin(), "БИН " + r.getSupplierBin()), r.getAnno(),
                        r.getName()))
                .toList();
        Category shown = new Category(cat.code(), cat.name() != null ? cat.name() : rows.isEmpty() ? null : rows.get(0).getName(),
                cat.description() != null ? cat.description() : rows.isEmpty() ? null : rows.get(0).getDescription(), rows.size());
        return new MarketPriceResponse(shown, alternatives, eff, units, st, bidStats(cat.code(), eff), out, l.fetchedAt(), !l.ok(), l.error());
    }

    private MarketPriceResponse.Bids bidStats(String code, String unit) {
        List<BigDecimal> prices = new ArrayList<>();
        for (Object[] row : bids.findBidPrices(code, from().atStartOfDay())) {
            if (norm((String) row[1]).equals(unit)) prices.add((BigDecimal) row[0]);
        }
        MarketStats st = MarketStats.of(prices, List.of(), null);
        return new MarketPriceResponse.Bids(st.count(), st.median());
    }

    /** Единицы категории по числу строк (больше — первой). */
    private static List<String> units(List<MarketContract> rows) {
        Map<String, Long> by = rows.stream().collect(Collectors.groupingBy(r -> norm(r.getUnit()), LinkedHashMap::new, Collectors.counting()));
        return by.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
    }

    /** Выбранная → единица лота → единица большинства строк (спека §9). */
    private static String effectiveUnit(String chosen, String lotUnit, List<String> units) {
        if (chosen != null && !chosen.isBlank()) return norm(chosen);
        if (lotUnit != null && !lotUnit.isBlank()) return norm(lotUnit);
        return units.isEmpty() ? "шт" : units.get(0);
    }

    static String norm(String unit) {
        return ResultTexts.unit(unit, null);
    }

    private static List<BigDecimal> prices(List<MarketContract> rows) {
        return rows.stream().map(MarketContract::getUnitPrice).toList();
    }

    private static List<BigDecimal> budgets(List<MarketContract> rows) {
        return rows.stream().map(MarketContract::getBudgetUnitPrice).filter(Objects::nonNull).toList();
    }

    private static String text(MarketContract m) {
        return (Objects.toString(m.getName(), "") + " " + Objects.toString(m.getDescription(), "") + " "
                + Objects.toString(m.getExtraDesc(), "")).toLowerCase(Locale.ROOT);
    }
}
```

`Function` и `Duration` в импортах сервиса не нужны, если IDE подсветит — убрать.

- [ ] **Step 8: Путь**

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.MarketHintsRequest;
import com.vladoose.nir.dto.response.MarketHint;
import com.vladoose.nir.dto.response.MarketPriceResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.TenderService;
import com.vladoose.nir.service.market.MarketPriceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Цены рынка (спека 2026-10-22-tender-results-market-prices §13): только KZ; лот — с гардом рынка его тендера или заявки. */
@RestController
@RequestMapping("/api/market-prices")
public class MarketPriceController {

    private final MarketPriceService service;
    private final TenderLotRepository lots;
    private final TenderService tenders;
    private final PrivateRequestService privateRequests;

    public MarketPriceController(MarketPriceService service, TenderLotRepository lots, TenderService tenders,
                                 PrivateRequestService privateRequests) {
        this.service = service;
        this.lots = lots;
        this.tenders = tenders;
        this.privateRequests = privateRequests;
    }

    private static void kzOnly() {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
    }

    @GetMapping
    public MarketPriceResponse get(@RequestParam(required = false) Long lotId, @RequestParam(required = false) String q,
                                   @RequestParam(required = false) String code, @RequestParam(required = false) String unit,
                                   @RequestParam(required = false) String filter) {
        kzOnly();
        if (lotId != null) {
            TenderLot lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Лот не найден"));
            Tender t = lot.getTender();
            if (t.getSource() == Source.PRIVATE_REQUEST) privateRequests.findById(t.getId());
            else tenders.findPublic(t.getId());
            return service.forLot(lotId, code, unit, filter);
        }
        if (q == null || q.strip().length() < 2) throw new BadRequestException("Укажите лот или текст для поиска");
        return service.forText(q.strip(), code, unit, filter);
    }

    @PostMapping("/hints")
    public List<MarketHint> hints(@RequestBody MarketHintsRequest req) {
        kzOnly();
        return service.hints(req.texts() == null ? List.of() : req.texts());
    }
}
```

`TenderController.java` — поле и параметр конструктора `MarketPriceService marketPrices` (последним); в `getLots` перед `return lots;`:

```java
        if (MarketContext.get() == Market.KZ) {
            Map<Long, MarketLotSummary> market = marketPrices.lotSummaries(entities);
            for (TenderLotResponse l : lots) l.setMarket(market.get(l.getId()));
        }
```

(импорты `com.vladoose.nir.context.MarketContext`, `com.vladoose.nir.dto.response.MarketLotSummary`, `com.vladoose.nir.service.market.MarketPriceService`; `Market` — из `entity`).

- [ ] **Step 9: Запустить — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.market.*' --tests 'com.vladoose.nir.tenderresults.*' 2>&1 | tail -20` (sandbox off)
Expected: PASS.

- [ ] **Step 10: Мутации**

1. `MarketPriceService.build`: без отбора по единице (`same = rows`) → `lot_unitsNotMixed_budgetDiff` красный.
2. `MarketStats.percentile`: без ветки «одна строка» и без интерполяции (`sorted.get(sorted.size() / 2)`) → `MarketStatsTest.medianPercentilesBudget` красный.
3. `MarketCategories.pick`: без `thenComparingInt(Category::count)` → `pick_byOverlapThenCount` красный.
4. `forLot`: без запроса кода с площадки → `lotCodeFilledFromPlatform` красный.
5. `MarketPriceController.get`: без гарда рынка лота → `operatorReads_rf400_otherMarketLot404` красный.
6. `bidStats`: без отбора отклонённых в запросе (`AND b.rejected = false` убрать) → `bidsOfSameCategory` красный.

- [ ] **Step 11: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java/com/vladoose/nir/market && git commit -m "$(cat <<'EOF'
feat(market): цена рынка — медиана, середина рынка, единица, фильтр, категория по тексту; путь и строка «Рынок» у лота

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Строка «рынок» в Telegram — пачка бота и «🏥 Новый тендер»

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/market/MarketLines.java`
- Modify: `src/main/java/com/vladoose/nir/service/market/MarketPriceService.java` (`implements MarketLines`, `telegramLines`), `MarketContractWriter.java` (`LotInfo`, `lotInfos`)
- Modify: `src/main/java/com/vladoose/nir/service/productwatch/ProductDigestComposer.java` (третья строка блока), `ProductWatchNotifier.java` (setter `setMarketLines`, строки в пачке)
- Modify: `src/main/java/com/vladoose/nir/service/tendernotify/NewTenderComposer.java` (`Card.market`), `NewTenderNotifier.java` (сначала карточки, потом строки рынка вне транзакции, потом текст)
- Test: `src/test/java/com/vladoose/nir/market/MarketTelegramLinesTest.java`, `src/test/java/com/vladoose/nir/productwatch/ProductDigestComposerTest.java` (+1), `src/test/java/com/vladoose/nir/service/tendernotify/NewTenderComposerTest.java` (+1), `NewTenderNotifierTest.java` (+1)

**Interfaces:**
- Consumes: задача 11 (`MarketPriceService`, `MarketTexts.telegramLine`, `MarketCategories`, `MarketCollector.ensureWord`), `ProductWatchComposer.Card.lots()` (`LotLine.lotId()`), `NewTenderComposer.Card`.
- Produces: `interface MarketLines { Map<Long, String> lines(Map<Long, List<Long>> lotIdsByTender); }` (бин — `MarketPriceService`); `MarketPriceService.telegramLines(Map<Long, List<Long>>, Duration)`, `TELEGRAM_BUDGET = 30 с`, `TELEGRAM_WORDS = 15`; `MarketContractWriter.record LotInfo(long lotId, String name, String code, Long extLotId, boolean goszakup, BigDecimal budget, String unit)`, `lotInfos(Collection<Long>)`; `ProductDigestComposer.compose(List<Card>, String publicUrl, Map<Long, String> market)`; `NewTenderComposer.Card` — последний компонент `String market` (+ прежний конструктор без него, `withMarket(String)`); `ProductWatchNotifier.setMarketLines(MarketLines)`, `NewTenderNotifier.setMarketLines(MarketLines)` (`@Autowired(required = false)`: ручные тесты — без строки).

- [ ] **Step 1: Тест строк**

```java
package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.market.MarketCollector;
import com.vladoose.nir.service.market.MarketContractWriter;
import com.vladoose.nir.service.market.MarketPriceService;
import com.vladoose.nir.service.tenderresults.GoszakupSubjects;
import com.vladoose.nir.tenderresults.FakeGoszakupResultsClient;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** «рынок: медиана … (N дог.) · бюджет …» для сообщений (спека 2026-10-22-tender-results-market-prices §10). */
@SpringBootTest
@Transactional
class MarketTelegramLinesTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);
    static final String CODE = "266011.150.000000";

    @Autowired MarketContractRepository contracts;
    @Autowired MarketLookupRepository lookups;
    @Autowired MarketContractWriter writer;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotBidRepository bids;
    @Autowired GoszakupSubjectRepository subjectRepo;
    @Autowired TenderRepository tenders;

    FakeGoszakupResultsClient fake;
    MarketPriceService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        contracts.deleteAllInBatch();
        lookups.deleteAllInBatch();
        fake = new FakeGoszakupResultsClient();
        MarketCollector collector = new MarketCollector(fake, writer, new GoszakupSubjects(fake, subjectRepo, CLOCK, ms -> { }),
                List::of, CLOCK, ms -> { });
        service = new MarketPriceService(collector, writer, contracts, lots, bids, subjectRepo, fake, CLOCK);
        for (int i = 0; i < 2; i++) {
            contracts.saveAndFlush(MarketContract.builder().lotExtId(99600000L + i).enstruCode(CODE).name("Дефибриллятор").unit("Штука")
                    .unitPrice(new BigDecimal("1340000")).signedAt(LocalDate.of(2026, 5, 10)).build());
        }
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void lineForLotWithData_noneWithout_liveAskedOnce() {
        Tender t = ResultsFixtures.tender(tenders, 970010001L, NOW.plusDays(3), "1-ЗЦП1", "Дефибриллятор бифазный", "2-ЗЦП1", "Кушетка");
        TenderLot defib = t.getLots().get(0);
        defib.setEnstruCode(CODE);
        defib.setPlanUnit("Штука");
        defib.setMaxCost(new BigDecimal("1420000"));
        tenders.saveAndFlush(t);
        Tender other = ResultsFixtures.tender(tenders, 970010002L, NOW.plusDays(3), "1-ЗЦП1", "Кушетка смотровая");
        Map<Long, List<Long>> in = new LinkedHashMap<>();
        in.put(t.getId(), List.of(t.getLots().get(1).getId(), defib.getId()));   // у первого лота данных нет — берётся следующий
        in.put(other.getId(), List.of(other.getLots().get(0).getId()));
        Map<Long, String> out = service.telegramLines(in, Duration.ofSeconds(30));
        assertThat(out.get(t.getId())).isEqualTo("рынок: медиана 1,34\u00A0млн\u00A0₸ (2 дог.) · бюджет 1,42\u00A0млн\u00A0₸");
        assertThat(out).doesNotContainKey(other.getId());
        assertThat(fake.calls.stream().filter(c -> c.startsWith("closed:")).toList())
                .as("живой запрос — по разу на слово").containsExactlyInAnyOrder("closed:кушетка@2024-10-22", "closed:дефибриллятор@2024-10-22");
    }

    /** Время вышло — без живых запросов, строки — из набора. */
    @Test
    void noTimeLeft_dbOnly() {
        Tender t = ResultsFixtures.tender(tenders, 970010003L, NOW.plusDays(3), "1-ЗЦП1", "Дефибриллятор бифазный");
        TenderLot l = t.getLots().get(0);
        l.setEnstruCode(CODE);
        tenders.saveAndFlush(t);
        Map<Long, String> out = service.telegramLines(Map.of(t.getId(), List.of(l.getId())), Duration.ZERO);
        assertThat(out.get(t.getId())).startsWith("рынок: медиана 1,34");
        assertThat(fake.calls).isEmpty();
    }
}
```

`ProductDigestComposerTest.java` — добавить:

```java
    /** Строка рынка — третьей строкой блока, экранирована (спека 2026-10-22-tender-results-market-prices §10). */
    @Test
    void marketLine() {
        var chunks = ProductDigestComposer.compose(List.of(card(7, ProductMatchLevel.TYPE, null, "П", null)), null,
                java.util.Map.of(7L, "рынок: медиана 1,34 млн ₸ (69 дог.) · <бюджет>"));
        assertThat(chunks.get(0).html()).contains("\n   рынок: медиана 1,34 млн ₸ (69 дог.) · &lt;бюджет&gt;");
    }
```

`NewTenderComposerTest.java` — добавить:

```java
    @Test
    void marketLine_underOurProduct() {
        Card c = card(5, "1-1", List.of(new LotLine("Дефибриллятор", 1))).withMarket("рынок: медиана 1,34 млн ₸ (69 дог.)");
        assertThat(NewTenderComposer.compose(List.of(c), URL)).contains("\nрынок: медиана 1,34 млн ₸ (69 дог.)\nСумма:");
    }
```

`NewTenderNotifierTest.java` — добавить (импорт `java.util.Map`):

```java
    /** Строки рынка — по лотам тендера, вне транзакции чтения, в текст «Нового тендера». */
    @Test
    void marketLineInMessage() {
        Tender t = tender("ZZ-MKT-1", ZKO, "ACTIVE", TODAY.plusDays(2));
        NewTenderNotifier n = notifier(true, TG_ON, "");
        List<Map<Long, List<Long>>> asked = new ArrayList<>();
        n.setMarketLines(byTender -> {
            asked.add(byTender);
            return Map.of(t.getId(), "рынок: медиана 1,34 млн ₸ (69 дог.)");
        });
        n.afterImport(created("ZZ-MKT-1"));
        assertThat(asked.get(0).get(t.getId())).containsExactly(t.getLots().get(0).getId());
        assertThat(sent.get(0)).contains("\nрынок: медиана 1,34 млн ₸ (69 дог.)");
    }
```

- [ ] **Step 2: Запустить — красный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.market.MarketTelegramLinesTest' --tests 'com.vladoose.nir.productwatch.ProductDigestComposerTest' --tests 'com.vladoose.nir.service.tendernotify.*' 2>&1 | tail -20` (sandbox off)
Expected: FAIL — компиляция (`telegramLines`, `compose(…, market)`, `withMarket`, `setMarketLines`).

- [ ] **Step 3: Интерфейс и строки** — `MarketLines.java`:

```java
package com.vladoose.nir.service.market;

import java.util.List;
import java.util.Map;

/** Строка «рынок: …» к сообщениям Telegram (спека 2026-10-22-tender-results-market-prices §10). Ничего не бросает. */
public interface MarketLines {
    /** tenderId → строка по первому лоту из списка, у которого есть данные; не успели или нет данных — без строки. */
    Map<Long, String> lines(Map<Long, List<Long>> lotIdsByTender);
}
```

`MarketContractWriter.java` — добавить (импорты `com.vladoose.nir.entity.Source`, `TenderPlatform`):

```java
    /** Что нужно о лоте для строки рынка — одной транзакцией чтения (потоки уведомлений — без сессии). */
    public record LotInfo(long lotId, String name, String code, Long extLotId, boolean goszakup, BigDecimal budget, String unit) {}

    @Transactional(readOnly = true)
    public List<LotInfo> lotInfos(Collection<Long> lotIds) {
        return lots.findAllById(lotIds).stream().map(l -> new LotInfo(l.getId(), l.getEquipName(), l.getEnstruCode(), l.getExtLotId(),
                l.getTender().getPlatform() == TenderPlatform.GOSZAKUP,
                l.getTender().getSource() == Source.PUBLIC_TENDER ? l.getMaxCost() : null,
                l.getUnit() != null && !l.getUnit().isBlank() ? l.getUnit() : l.getPlanUnit())).toList();
    }
```

`MarketPriceService.java`: `public class MarketPriceService implements MarketLines`; добавить (импорты `java.time.Instant`):

```java
    static final Duration TELEGRAM_BUDGET = Duration.ofSeconds(30);
    static final int TELEGRAM_WORDS = 15;

    @Override
    public Map<Long, String> lines(Map<Long, List<Long>> lotIdsByTender) {
        try {
            return telegramLines(lotIdsByTender, TELEGRAM_BUDGET);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * Строки для сообщений: по тендеру — первый лот с данными. Живой запрос — по новым словам, не больше 15 и не дольше budget на
     * всё (спека §10); время вышло — только набор. Код лота goszakup без кода — с площадки, пока есть время.
     */
    public Map<Long, String> telegramLines(Map<Long, List<Long>> lotIdsByTender, Duration budget) {
        Instant deadline = clock.instant().plus(budget);
        Set<Long> all = new HashSet<>();
        lotIdsByTender.values().forEach(all::addAll);
        if (all.isEmpty()) return Map.of();
        Map<Long, MarketContractWriter.LotInfo> info = new HashMap<>();
        for (MarketContractWriter.LotInfo i : writer.lotInfos(all)) info.put(i.lotId(), i);
        Set<String> asked = new HashSet<>();
        Map<String, List<MarketContract>> rowsByCode = new HashMap<>();
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Long>> e : lotIdsByTender.entrySet()) {
            for (Long lotId : e.getValue()) {
                MarketContractWriter.LotInfo i = info.get(lotId);
                if (i == null) continue;
                String line = telegramLine(i, deadline, asked, rowsByCode);
                if (line != null) {
                    out.put(e.getKey(), line);
                    break;
                }
            }
        }
        return out;
    }

    private String telegramLine(MarketContractWriter.LotInfo i, Instant deadline, Set<String> asked,
                                Map<String, List<MarketContract>> rowsByCode) {
        String code = i.code();
        if (code == null && i.goszakup() && i.extLotId() != null && client.isConfigured() && clock.instant().isBefore(deadline)) {
            try {
                List<ClosedLot> found = client.lotsByIds(List.of(i.extLotId()));
                if (!found.isEmpty()) {
                    writer.fillLot(i.lotId(), found.get(0));
                    code = found.get(0).enstruCode();
                }
            } catch (RuntimeException e) {
                // по названию
            }
        }
        String kw = MarketCategories.keyword(i.name());
        if (kw != null && asked.size() < TELEGRAM_WORDS && asked.add(kw)) {
            Duration left = Duration.between(clock.instant(), deadline);
            if (!left.isNegative() && !left.isZero()) collector.ensureWord(kw, left);
        }
        if (code == null) {
            Category cat = MarketCategories.pick(i.name(), categories(kw));
            code = cat == null ? null : cat.code();
        }
        if (code == null) return null;
        List<MarketContract> rows = rowsByCode.computeIfAbsent(code, c -> contracts.findByEnstruCodeAndSignedAtGreaterThanEqual(c, from()));
        if (rows.isEmpty()) return null;
        String unit = effectiveUnit(null, i.unit(), units(rows));
        List<MarketContract> same = rows.stream().filter(r -> norm(r.getUnit()).equals(unit)).toList();
        return MarketTexts.telegramLine(MarketStats.of(prices(same), budgets(same), i.budget()));
    }
```

- [ ] **Step 4: Пачка бота** — `ProductDigestComposer.java`:

```java
    public static List<Chunk> compose(List<ProductWatchComposer.Card> input, String publicUrl) {
        return compose(input, publicUrl, java.util.Map.of());
    }

    /** То же со строкой «рынок: …» у тендера (спека 2026-10-22-tender-results-market-prices §10). */
    public static List<Chunk> compose(List<ProductWatchComposer.Card> input, String publicUrl, java.util.Map<Long, String> market) {
```

(тело — прежнее, только `block(c, base, 99)` → `block(c, base, 99, market.get(c.tenderId()))` и `block(c, base, n++)` → `block(c, base, n++, market.get(c.tenderId()))`). `block`:

```java
    static String block(ProductWatchComposer.Card c, String base, int n) {
        return block(c, base, n, null);
    }

    static String block(ProductWatchComposer.Card c, String base, int n, String marketLine) {
```

в конце `block` перед `return`: `if (marketLine != null && !marketLine.isBlank()) b.append("\n   ").append(esc(TelegramText.oneLine(marketLine, 200)));`.

`ProductWatchNotifier.java` — поле `private MarketLines marketLines;` и:

```java
    /** Строка «рынок: …» в пачке (спека 2026-10-22-tender-results-market-prices §10); нет бина (тесты) — без строки. */
    @Autowired(required = false)
    public void setMarketLines(MarketLines marketLines) {
        this.marketLines = marketLines;
    }
```

в `publishDigest` перед циклом:

```java
        Map<Long, String> market = Map.of();
        if (marketLines != null) {
            Map<Long, List<Long>> lotIds = new LinkedHashMap<>();
            for (ProductWatchComposer.Card c : cards) lotIds.put(c.tenderId(), c.lots().stream().map(ProductMatchQuery.LotLine::lotId).toList());
            try {
                market = marketLines.lines(lotIds);
            } catch (RuntimeException e) {
                log.warn("«Наш товар»: строки цены рынка не собраны: {}", ErrorText.of(e));
            }
        }
```

и `ProductDigestComposer.compose(cards, publicUrl)` → `ProductDigestComposer.compose(cards, publicUrl, market)` (импорты `com.vladoose.nir.service.market.MarketLines`, `java.util.LinkedHashMap`).

- [ ] **Step 5: «🏥 Новый тендер»** — `NewTenderComposer.java`: у `Card` последний компонент `String market`; в записи:

```java
    record Card(long tenderId, String platformLabel, String number, String customer, String region, String subject,
                BigDecimal total, LocalDate deadline, LocalDateTime deadlineAt, List<LotLine> lots, String ourProduct, String market) {
        /** Без строки рынка — прежние вызовы. */
        Card(long tenderId, String platformLabel, String number, String customer, String region, String subject, BigDecimal total,
             LocalDate deadline, LocalDateTime deadlineAt, List<LotLine> lots, String ourProduct) {
            this(tenderId, platformLabel, number, customer, region, subject, total, deadline, deadlineAt, lots, ourProduct, null);
        }

        Card withMarket(String m) {
            return new Card(tenderId, platformLabel, number, customer, region, subject, total, deadline, deadlineAt, lots, ourProduct, m);
        }
    }
```

в `block` после строки «наш товар»: `if (c.market() != null && !c.market().isBlank()) b.append('\n').append(TelegramText.oneLine(c.market(), 200));`.

`NewTenderNotifier.java`:
- поле `private MarketLines marketLines;` и setter, как у `ProductWatchNotifier`;
- `Prepared` → `private record Prepared(List<Card> cards, List<String> extIds, Map<Long, ProductMatchLevel> levels, Map<Long, List<Long>> lotIds) {}`;
- `prepare` — без сборки текста:

```java
        if (found.isEmpty()) return new Prepared(List.of(), used, Map.of(), Map.of());
        Map<Long, ProductMatchQuery.Summary> ours = products.summaries(found.stream().map(Tender::getId).toList());
        List<Tender> ordered = found.stream()
                .sorted(Comparator.comparingInt((Tender t) -> rank(ours.get(t.getId()))).reversed())
                .toList();
        List<Card> cards = ordered.stream().map(t -> card(t, ours.get(t.getId()))).toList();
        Map<Long, ProductMatchLevel> levels = new HashMap<>();
        Map<Long, List<Long>> lotIds = new LinkedHashMap<>();
        for (Tender t : ordered) {
            ProductMatchQuery.Summary s = ours.get(t.getId());
            if (s != null && s.level() != null) levels.put(t.getId(), s.level());
            lotIds.put(t.getId(), t.getLots().stream()
                    .sorted(Comparator.comparing(TenderLot::getLotNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(TenderLot::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(TenderLot::getId).toList());
        }
        return new Prepared(cards, used, levels, lotIds);
```

- в `afterImport` после `pending.clear();` — строки рынка вне транзакции, потом текст:

```java
        if (p == null || p.cards().isEmpty()) return;   // никто не прошёл фильтр
        Map<Long, String> market = Map.of();
        if (marketLines != null) {
            try {
                market = marketLines.lines(p.lotIds());
            } catch (RuntimeException e) {
                log.warn("Telegram: строки цены рынка к новым тендерам не собраны: {}", ErrorText.of(e));
            }
        }
        Map<Long, String> lines = market;
        List<Card> cards = p.cards().stream().map(c -> c.withMarket(lines.get(c.tenderId()))).toList();
        NewTenderComposer.Composed composed = NewTenderComposer.composeShown(cards, publicUrl);
        Map<Long, ProductMatchLevel> shownOurs = new LinkedHashMap<>();
        boolean sound = false;
        for (Card c : cards.subList(0, composed.shown())) {
            ProductMatchLevel level = p.levels().get(c.tenderId());
            if (level == null) continue;
            shownOurs.put(c.tenderId(), level);
            sound |= level.alert();   // ★ и «✓ подходит» (спека 2026-10-20-product-spec-fit §6.4)
        }
```

далее прежняя отправка: `sender.send(composed.text(), !sound)`, в логе — `p.extIds().size()`, при сбое — `pending.addAll(p.extIds())`, после — `markOurProducts(shownOurs)`. (Импорты `com.vladoose.nir.service.market.MarketLines`, `java.util.HashMap`.)

- [ ] **Step 6: Запустить — зелёный, и соседи**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.market.*' --tests 'com.vladoose.nir.productwatch.*' --tests 'com.vladoose.nir.tgbot.*' --tests 'com.vladoose.nir.service.tendernotify.*' 2>&1 | tail -20` (sandbox off)
Expected: PASS (прежние тесты уведомлений — без изменений: ручные уведомители без строки рынка).

- [ ] **Step 7: Мутации**

1. `telegramLines`: без перехода к следующему лоту (`break` после первого лота без данных) → `lineForLotWithData_noneWithout_liveAskedOnce` красный.
2. `telegramLine`: без проверки оставшегося времени (`ensureWord` всегда) → `noTimeLeft_dbOnly` красный.
3. `ProductDigestComposer.block`: без `esc` у строки рынка → `marketLine` красный.
4. `NewTenderNotifier.afterImport`: строки рынка не подставлять (`p.cards()` как есть) → `marketLineInMessage` красный.

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java src/test/java/com/vladoose/nir/market/MarketTelegramLinesTest.java \
  src/test/java/com/vladoose/nir/productwatch/ProductDigestComposerTest.java src/test/java/com/vladoose/nir/service/tendernotify && \
  git commit -m "$(cat <<'EOF'
feat(market): строка «рынок» в пачке бота и «Новом тендере» — живой запрос до 15 слов и 30 с

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Фронт итогов — строка и панель итога у лота, заказчик и история, «Проверить итоги», чип и фильтр списка

**Files:**
- Create: `frontend/src/app/shared/tender-results.ts`, `frontend/src/app/pages/tenders/lot-result-panel.component.ts`, `frontend/src/app/pages/tenders/tender-customer.component.ts`
- Modify: `frontend/src/app/services/api.service.ts` (`getLotResult`, `checkTenderResults`, `getTenderCustomer`, `getCustomerHistory`)
- Modify: `frontend/src/app/pages/tenders/tender-lots.component.ts` (строка итога, панель `@defer`), `tenders.component.ts` (заказчик, «Проверить итоги», чип, фильтр), `tenders-filters.component.ts` (фильтр «Итоги»), `frontend/src/app/shared/deal.ts` (`RESULT`, ссылка `TENDER`)

**Interfaces:**
- Consumes: API задач 9–10 (`GET /api/lots/{id}/result`, `POST /api/tenders/{id}/results/check`, `GET /api/tenders/{id}/customer`, `GET /api/tenders/{id}/customer-history`); у лота `result: LotResultSummary`, у тендера `result: TenderResultSummary`.
- Produces: `shared/tender-results.ts` — типы `LotResultSummary`, `TenderResultSummary`, `LotResultView`, `LotResultBid`, `LotResultContract`, `LotResultAttempt`, `CustomerContacts`, `CustomerHistory`, `CustomerHistoryRow`, `ResultsCheck`; функции `resultTone(outcome)`, `resultMatches(summary, filter)`, `plainDate(iso)`, `instantText(iso)`, `announceUrl(anno)`; `RESULT_LABELS`; компоненты `app-lot-result-panel` (`@Input lotId`, `@Output close`), `app-tender-customer` (`@Input tenderId`); `TendersFilters.result`; `ApiService.getLotResult(lotId)`, `checkTenderResults(tenderId)`, `getTenderCustomer(tenderId)`, `getCustomerHistory(tenderId)`.

- [ ] **Step 1: Типы и помощники** — `shared/tender-results.ts`:

```ts
/** Итоги тендеров (спека 2026-10-22-tender-results-market-prices §11): типы ответов и помощники экрана. */
export type LotOutcome = 'PENDING' | 'DONE' | 'FAILED' | 'CANCELLED';

export interface LotResultSummary {
  outcome: LotOutcome; text: string; failReason: string | null; bidsCount: number | null; winnerName: string | null;
  winnerPrice: number | null; ourPlace: number | null; ourRejected: boolean | null; contract: boolean; protocolStatus: string | null;
  resultsAt: string | null;
}

export interface TenderResultSummary { chip: string; call: boolean; done: boolean; ours: boolean; }

export interface LotResultBid {
  place: number | null; name: string | null; bin: string | null; unitPrice: number | null; total: number | null;
  submittedAt: string | null; rejected: boolean; rejectReason: string | null; winner: boolean; second: boolean; ours: boolean;
}

export interface LotResultContract {
  number: string; signedAt: string | null; unitPrice: number | null; sum: number | null; supplierBin: string | null;
  supplierName: string | null;
}

export interface LotResultAttempt {
  anno: string; lotNumber: string | null; method: string | null; methodName: string; status: string | null; statusId: number | null;
  publishedAt: string | null; resultsAt: string | null; supplierBin: string | null; supplierName: string | null; tenderId: number | null;
  own: boolean;
}

export interface LotResultView {
  lotId: number; outcome: LotOutcome | null; text: string; lotStatus: string | null; failReason: string | null; failText: string | null;
  resultsAt: string | null; protocolUrl: string | null; protocolName: string | null; protocolStatus: string | null;
  protocolNote: string | null; bidsCount: number | null; rejectedCount: number | null; bids: LotResultBid[];
  contract: LotResultContract | null; attempts: LotResultAttempt[]; nextTenderId: number | null; nextTenderNumber: string | null;
  checkedAt: string | null; finalAt: string | null;
}

export interface CustomerContacts { bin: string | null; name: string | null; phone: string | null; email: string | null; }

export interface CustomerHistoryRow {
  signedAt: string | null; name: string | null; quantity: number | null; unit: string | null; unitPrice: number | null;
  supplierName: string | null; region: string | null; sameCategory: boolean; anno: string | null;
}

export interface CustomerHistory { bin: string | null; rows: CustomerHistoryRow[]; fetchedAt: string | null; error: string | null; }

export interface ResultsCheck { done: boolean; ok: boolean; message: string | null; }

/** Состоялся — зелёный, не состоялся — красный, прочее — приглушённый. */
export function resultTone(outcome: string | null | undefined): 'success' | 'danger' | 'muted' {
  return outcome === 'DONE' ? 'success' : outcome === 'FAILED' ? 'danger' : 'muted';
}

/** Фильтр «Итоги» списка: '' — все. */
export const RESULT_LABELS: Record<string, string> = { CALL: 'Не состоялся — звонить', DONE: 'Состоялся', OURS: 'Мы участвовали' };

export function resultMatches(r: TenderResultSummary | null | undefined, filter: string): boolean {
  if (!filter) return true;
  if (!r) return false;
  return filter === 'CALL' ? r.call : filter === 'DONE' ? r.done : filter === 'OURS' ? r.ours : true;
}

/** Время площадки без пояса строкой: «2026-10-14» → «14.10.2026», «2026-10-14T12:54:49» → «14.10.2026 12:54». */
export function plainDate(iso: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?/.exec(iso || '');
  if (!m) return '';
  return `${m[3]}.${m[2]}.${m[1]}` + (m[4] ? ` ${m[4]}:${m[5]}` : '');
}

/** Момент времени (с поясом) — по Уральску. */
export function instantText(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '' : d.toLocaleString('ru-RU', { timeZone: 'Asia/Oral', day: '2-digit', month: '2-digit',
    year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** Объявление на портале goszakup по номеру «17758520-1». */
export function announceUrl(anno: string): string {
  return 'https://goszakup.gov.kz/ru/announce/index/' + encodeURIComponent((anno || '').split('-')[0]);
}
```

- [ ] **Step 2: API** — `api.service.ts`: импорт `import type { CustomerContacts, CustomerHistory, LotResultView, ResultsCheck } from '../shared/tender-results';` и методы:

```ts
  /** Итоги тендеров (спека 2026-10-22-tender-results-market-prices §13). */
  getLotResult(lotId: number): Observable<LotResultView> {
    return this.http.get<LotResultView>(`${this.base}/lots/${lotId}/result`);
  }

  checkTenderResults(tenderId: number): Observable<ResultsCheck> {
    return this.http.post<ResultsCheck>(`${this.base}/tenders/${tenderId}/results/check`, {});
  }

  getTenderCustomer(tenderId: number): Observable<CustomerContacts> {
    return this.http.get<CustomerContacts>(`${this.base}/tenders/${tenderId}/customer`);
  }

  getCustomerHistory(tenderId: number): Observable<CustomerHistory> {
    return this.http.get<CustomerHistory>(`${this.base}/tenders/${tenderId}/customer-history`);
  }
```

- [ ] **Step 3: Панель итога лота** — `pages/tenders/lot-result-panel.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { errorText } from '../../shared/error-text';
import { LotResultView, announceUrl, instantText, plainDate, resultTone } from '../../shared/tender-results';

/** Итог лота подробно (спека 2026-10-22-tender-results-market-prices §11): участники, договор, попытки, протокол. Ленивый чанк. */
@Component({
  selector: 'app-lot-result-panel',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink, MarketMoneyPipe],
  template: `
    <div class="rp">
      <div class="rp-head">
        <b>Итог лота</b>
        <button type="button" class="rp-link" (click)="close.emit()">Скрыть</button>
      </div>
      <p class="rp-muted" *ngIf="loading">Загружаю…</p>
      <p class="rp-err" *ngIf="error">{{ error }}</p>
      <ng-container *ngIf="view as v">
        <p class="rp-text" [class]="'rp-text rp-' + tone(v.outcome)">{{ v.text }}</p>
        <p class="rp-muted">
          <span *ngIf="v.lotStatus">{{ v.lotStatus }}</span><span *ngIf="v.resultsAt"> · итоги {{ date(v.resultsAt) }}</span>
          <a *ngIf="v.protocolUrl" [href]="v.protocolUrl" target="_blank" rel="noopener"> · протокол ↗</a>
        </p>
        <p class="rp-warn" *ngIf="v.protocolStatus && v.protocolStatus !== 'PARSED'">{{ protocolText(v) }}</p>

        <div *ngIf="v.bids.length">
          <div class="rp-sub">Участники{{ v.bidsCount != null ? ': ' + v.bidsCount : '' }}{{ v.rejectedCount ? ' · отклонено ' + v.rejectedCount : '' }}</div>
          <table class="responsive-cards card-grid rp-table">
            <thead><tr><th>Место</th><th>Поставщик</th><th>БИН / ИИН</th><th>Цена за ед.</th><th>Сумма</th><th>Подано</th></tr></thead>
            <tbody>
              <tr *ngFor="let b of v.bids" [class.rp-win]="b.winner" [class.rp-ours]="b.ours" [class.rp-rej]="b.rejected">
                <td data-label="Место">{{ b.rejected ? 'отклонён' : (b.place ?? '—') }}</td>
                <td data-label="Поставщик">{{ b.name || '—' }}<span class="rp-tag" *ngIf="b.winner">победитель</span><span class="rp-tag"
                    *ngIf="b.second">2-е место</span><span class="rp-tag rp-tag-ours" *ngIf="b.ours">мы</span>
                  <div class="rp-reason" *ngIf="b.rejectReason">{{ b.rejectReason }}</div></td>
                <td data-label="БИН / ИИН">{{ b.bin || '—' }}</td>
                <td data-label="Цена за ед.">{{ b.unitPrice | money:2 }}</td>
                <td data-label="Сумма">{{ b.total | money:2 }}</td>
                <td data-label="Подано">{{ date(b.submittedAt) || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="rp-block" *ngIf="v.contract as c">
          <div class="rp-sub">Договор</div>
          № {{ c.number }}<ng-container *ngIf="c.signedAt"> от {{ date(c.signedAt) }}</ng-container> ·
          {{ c.supplierName || c.supplierBin || 'поставщик' }} · {{ c.unitPrice | money:2 }} за ед.<ng-container *ngIf="c.sum"> ·
          сумма {{ c.sum | money:2 }}</ng-container>
        </div>

        <div class="rp-block" *ngIf="v.attempts.length > 1">
          <div class="rp-sub">Попытки закупки</div>
          <div class="rp-att" *ngFor="let a of v.attempts" [class.rp-own]="a.own">
            <a [href]="url(a.anno)" target="_blank" rel="noopener">{{ a.lotNumber || a.anno }} ↗</a>
            · {{ a.methodName }} · {{ a.status || '—' }}<ng-container *ngIf="a.supplierName || a.supplierBin"> ·
            {{ a.supplierName || ('БИН ' + a.supplierBin) }}</ng-container>
            <span class="rp-tag" *ngIf="a.own">этот лот</span>
            <a *ngIf="a.tenderId && !a.own" class="xref" [routerLink]="['/tenders']" [queryParams]="{ openId: a.tenderId }">в АИС ›</a>
          </div>
        </div>
        <p class="rp-block" *ngIf="v.nextTenderId">Дальше — повторная попытка:
          <a class="xref" [routerLink]="['/tenders']" [queryParams]="{ openId: v.nextTenderId }">№ {{ v.nextTenderNumber || v.nextTenderId }}</a></p>
        <p class="rp-muted" *ngIf="v.checkedAt">Проверено {{ instant(v.checkedAt) }}{{ v.finalAt ? ' · итог окончательный' : '' }}</p>
      </ng-container>
    </div>
  `,
  styles: [`
    .rp { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin: 10px 0; background: var(--surface); }
    .rp-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .rp-link { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 13px; padding: 4px 0; }
    .rp-muted { color: var(--text-muted); font-size: 13px; margin: 6px 0; }
    .rp-err { color: var(--danger-text); font-size: 13px; }
    .rp-warn { color: var(--warn-text); font-size: 13px; margin: 6px 0; }
    .rp-text { font-weight: 500; margin: 8px 0 4px; }
    .rp-success { color: var(--success-text); }
    .rp-danger { color: var(--danger-text); }
    .rp-muted.rp-text { color: var(--text); }
    .rp-sub { font-weight: 600; font-size: 13px; margin: 12px 0 6px; color: var(--text); }
    .rp-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .rp-table th, .rp-table td { padding: 6px 8px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
    .rp-win td { background: color-mix(in srgb, var(--success) 8%, var(--surface)); }
    .rp-ours td { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .rp-rej td { color: var(--text-muted); }
    .rp-tag { display: inline-block; margin-left: 6px; font-size: 11px; padding: 1px 7px; border-radius: 10px;
              background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .rp-tag-ours { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .rp-reason { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
    .rp-block { font-size: 13px; color: var(--text); margin: 8px 0; }
    .rp-att { font-size: 13px; padding: 3px 0; }
    .rp-own { font-weight: 500; }
    .rp-att a { color: var(--accent); }
    @media (max-width: 900px) {
      .rp-table tr { grid-template-columns: auto 1fr auto; grid-template-areas: "place name price" "bin bin total"; }
      .rp-table td[data-label="Место"] { grid-area: place; font-weight: 600; }
      .rp-table td[data-label="Поставщик"] { grid-area: name; }
      .rp-table td[data-label="Цена за ед."] { grid-area: price; text-align: right; font-weight: 600; }
      .rp-table td[data-label="БИН / ИИН"] { grid-area: bin; color: var(--text-muted); font-size: 12px; }
      .rp-table td[data-label="Сумма"] { grid-area: total; text-align: right; color: var(--text-muted); font-size: 12px; }
      .rp-table td[data-label="Подано"] { display: none; }
      .rp-link { min-height: 40px; }
    }
  `],
})
export class LotResultPanelComponent implements OnChanges {
  @Input() lotId!: number;
  @Output() close = new EventEmitter<void>();

  view: LotResultView | null = null;
  loading = false;
  error = '';
  private seq = 0;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnChanges() {
    const n = ++this.seq;
    this.loading = true;
    this.error = '';
    this.api.getLotResult(this.lotId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { if (n !== this.seq) return; this.view = v; this.loading = false; this.cdr.markForCheck(); },
      error: e => { if (n !== this.seq) return; this.loading = false; this.error = 'Итог не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  tone(o: string | null): string { return resultTone(o); }
  date(iso: string | null): string { return plainDate(iso); }
  instant(iso: string | null): string { return instantText(iso); }
  url(anno: string): string { return announceUrl(anno); }

  protocolText(v: LotResultView): string {
    switch (v.protocolStatus) {
      case 'UNKNOWN_FORMAT': return 'Протокол не разобран (' + (v.protocolNote || 'вид не знаком') + ') — откройте файл.';
      case 'NO_FILE': return 'Файла протокола на площадке пока нет.';
      default: return 'Протокол не скачан или не разобран' + (v.protocolNote ? ': ' + v.protocolNote : '') + '.';
    }
  }
}
```

- [ ] **Step 4: Заказчик** — `pages/tenders/tender-customer.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, Input, OnChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { errorText } from '../../shared/error-text';
import { CustomerContacts, CustomerHistory, plainDate } from '../../shared/tender-results';

/** Заказчик на площадке (спека 2026-10-22-tender-results-market-prices §8, §11): телефон, почта, история договоров по нажатию. */
@Component({
  selector: 'app-tender-customer',
  standalone: true,
  imports: [NgIf, NgFor, MarketMoneyPipe],
  template: `
    <div class="tc">
      <span class="tc-label">Заказчик на площадке</span>
      <span class="tc-body" *ngIf="contacts as c">
        <span>{{ c.name || '—' }}</span>
        <a *ngIf="c.phone" [href]="'tel:' + tel(c.phone)">☎ {{ c.phone }}</a>
        <a *ngIf="c.email" [href]="'mailto:' + c.email">{{ c.email }}</a>
        <span class="tc-muted" *ngIf="!c.phone && !c.email">контактов в справочнике площадки нет</span>
      </span>
      <span class="tc-muted" *ngIf="!contacts && !error">Загружаю…</span>
      <span class="tc-err" *ngIf="error">{{ error }}</span>
      <button type="button" class="tc-link" (click)="toggleHistory()">{{ historyOpen ? 'Скрыть историю заказчика' : 'История заказчика' }}</button>
    </div>
    <div class="th" *ngIf="historyOpen">
      <p class="tc-muted" *ngIf="historyLoading">Загружаю договоры заказчика — до нескольких секунд…</p>
      <p class="tc-err" *ngIf="history?.error">{{ history!.error }}</p>
      <p class="tc-muted" *ngIf="history && !historyLoading && !history.rows.length">Договоров по медизделиям за 2 года нет</p>
      <table class="responsive-cards card-grid th-table" *ngIf="history?.rows?.length">
        <thead><tr><th>Дата</th><th>Что</th><th>Кол-во</th><th>Цена за ед.</th><th>Поставщик</th></tr></thead>
        <tbody>
          <tr *ngFor="let r of history!.rows" [class.th-same]="r.sameCategory">
            <td data-label="Дата">{{ date(r.signedAt) || '—' }}</td>
            <td data-label="Что">{{ r.name || '—' }}<span class="th-tag" *ngIf="r.sameCategory">та же категория</span></td>
            <td data-label="Кол-во">{{ r.quantity ?? '—' }} {{ r.unit || '' }}</td>
            <td data-label="Цена за ед.">{{ r.unitPrice | money:2 }}</td>
            <td data-label="Поставщик">{{ r.supplierName || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .tc { display: flex; flex-wrap: wrap; gap: 6px 12px; align-items: baseline; font-size: 14px; margin-top: 12px; }
    .tc-label { color: var(--text-muted); font-size: 13px; }
    .tc-body { display: inline-flex; flex-wrap: wrap; gap: 4px 12px; color: var(--text); }
    .tc-body a, .tc-link { color: var(--accent); }
    .tc-link { background: none; border: none; cursor: pointer; font-size: 13px; padding: 4px 0; }
    .tc-muted { color: var(--text-muted); font-size: 13px; }
    .tc-err { color: var(--danger-text); font-size: 13px; }
    .th { margin-top: 8px; }
    .th-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .th-table th, .th-table td { padding: 6px 8px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
    .th-same td { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .th-tag { display: inline-block; margin-left: 6px; font-size: 11px; padding: 1px 7px; border-radius: 10px;
              background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    @media (max-width: 900px) {
      .tc-link { min-height: 40px; }
      .th-table tr { grid-template-columns: 1fr auto; grid-template-areas: "name price" "date sup" "qty qty"; }
      .th-table td[data-label="Что"] { grid-area: name; }
      .th-table td[data-label="Цена за ед."] { grid-area: price; text-align: right; font-weight: 600; }
      .th-table td[data-label="Дата"] { grid-area: date; color: var(--text-muted); font-size: 12px; }
      .th-table td[data-label="Поставщик"] { grid-area: sup; text-align: right; color: var(--text-muted); font-size: 12px; }
      .th-table td[data-label="Кол-во"] { grid-area: qty; color: var(--text-muted); font-size: 12px; }
    }
  `],
})
export class TenderCustomerComponent implements OnChanges {
  @Input() tenderId!: number;

  contacts: CustomerContacts | null = null;
  error = '';
  historyOpen = false;
  historyLoading = false;
  history: CustomerHistory | null = null;
  private seq = 0;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnChanges() {
    const n = ++this.seq;
    this.contacts = null;
    this.error = '';
    this.historyOpen = false;
    this.history = null;
    this.api.getTenderCustomer(this.tenderId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: c => { if (n !== this.seq) return; this.contacts = c; this.cdr.markForCheck(); },
      error: e => { if (n !== this.seq) return; this.error = 'Контакты не загрузились: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  toggleHistory() {
    this.historyOpen = !this.historyOpen;
    if (!this.historyOpen || this.history || this.historyLoading) return;
    const n = this.seq;
    this.historyLoading = true;
    this.api.getCustomerHistory(this.tenderId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: h => { if (n !== this.seq) return; this.history = h; this.historyLoading = false; this.cdr.markForCheck(); },
      error: e => {
        if (n !== this.seq) return;
        this.historyLoading = false;
        this.history = { bin: null, rows: [], fetchedAt: null, error: 'История не загрузилась: ' + errorText(e) };
        this.cdr.markForCheck();
      },
    });
  }

  date(iso: string | null): string { return plainDate(iso); }

  /** «8 (71133) 31398» → «87113331398» для tel:. */
  tel(phone: string): string { return phone.replace(/[^\d+]/g, ''); }
}
```

- [ ] **Step 5: Строка итога у лота** — `tender-lots.component.ts`:
  - импорты: `import { LotResultPanelComponent } from './lot-result-panel.component';`, `import { resultTone } from '../../shared/tender-results';`; `LotResultPanelComponent` — в `imports` компонента;
  - в шапке лота после `<div class="lot-kp" …>`:

```html
          <!-- итог на площадке (спека 2026-10-22-tender-results-market-prices §11): видно и у свёрнутого лота, по нажатию — панель -->
          <div *ngIf="l.result" [class]="'lot-res lot-res-' + resTone(l)" (click)="openResult(l, $event)" title="Итог лота — подробно">🏁 {{ l.result.text }}</div>
```

  - в теле лота перед `@defer (on viewport)` сверки:

```html
        @defer (when resultOpen.has(l.id)) {
          <app-lot-result-panel *ngIf="resultOpen.has(l.id)" [lotId]="l.id" (close)="resultOpen.delete(l.id)"></app-lot-result-panel>
        }
```

  - стили (после `.lot-kp`):

```css
    .lot-res { margin-top: 4px; font-size: 13px; cursor: pointer; overflow-wrap: anywhere; }
    .lot-res-success { color: var(--success-text); }
    .lot-res-danger { color: var(--danger-text); }
    .lot-res-muted { color: var(--text-muted); }
```

  - поля и методы класса:

```ts
  /** Лоты, у которых открыта панель итога. */
  resultOpen = new Set<number>();

  resTone(l: any): string { return resultTone(l.result?.outcome); }

  /** Нажатие по строке итога: лот разворачивается, панель — переключается (лот не сворачивается). */
  openResult(l: any, e: Event) {
    e.stopPropagation();
    if (this.expandedLotId !== l.id) {
      if (this.expandedLotId !== null) this.closePanelsOf(this.expandedLotId);
      this.expandedLotId = l.id;
      this.resultOpen.add(l.id);
    } else if (this.resultOpen.has(l.id)) this.resultOpen.delete(l.id);
    else this.resultOpen.add(l.id);
    this.cdr.detectChanges();
  }
```

  - в `closePanelsOf` — `this.resultOpen.delete(lotId);`; в `resyncToLots` — `for (const id of Array.from(this.resultOpen)) if (!alive.has(id)) this.resultOpen.delete(id);`.

- [ ] **Step 6: Карточка и список тендеров** — `tenders.component.ts`:
  - импорты: `import { TenderCustomerComponent } from './tender-customer.component';`, `import { RESULT_LABELS, resultMatches } from '../../shared/tender-results';`, `import { errorText } from '../../shared/error-text';` (если ещё нет); `TenderCustomerComponent` — в `imports`;
  - в карточке списка после чипа «📌 …»:

```html
            <span *ngIf="t.result" class="res-chip" [class.res-call]="t.result.call" [class.res-done]="t.result.done && !t.result.call">{{ t.result.chip }}</span>
```

  - в `info-grid` после «Окончание приёма заявок»:

```html
          <div class="info-item" *ngIf="canCheckResults()"><span class="info-label">Итоги на площадке</span>
            <span><button type="button" class="btn btn-check-res" [disabled]="checkingResults" (click)="checkResults()">{{ checkingResults ? 'Проверяю…' : 'Проверить итоги' }}</button></span></div>
```

  - после `</div>` блока `info-grid` (перед `<p *ngIf="selectedTender.description" …>`):

```html
        <!-- заказчик на площадке и его история (спека 2026-10-22-tender-results-market-prices §8) — отдельным чанком -->
        @defer (on viewport) {
          <app-tender-customer *ngIf="isKz() && selectedTender.customerBin" [tenderId]="selectedTender.id"></app-tender-customer>
        } @placeholder {
          <div class="tc-ph"></div>
        }
```

  - стили:

```css
    .res-chip { display: inline-block; vertical-align: middle; font-size: 12px; padding: 2px 8px; border-radius: 10px; color: var(--text-muted);
                background: var(--surface-2); max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .res-chip.res-call { color: var(--danger-text); background: color-mix(in srgb, var(--danger) 15%, transparent); font-weight: 600; }
    .res-chip.res-done { color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .btn-check-res { padding: 4px 12px; font-size: 13px; }
    .tc-ph { min-height: 1px; }
```

  - поля и методы:

```ts
  filterResult = '';
  checkingResults = false;

  /** «Проверить итоги» — админ, KZ, goszakup, срок прошёл (спека §11). */
  canCheckResults(): boolean {
    const t = this.selectedTender;
    return !!t && this.isKz() && this.auth.isAdmin() && t.platform === 'GOSZAKUP' && deadlinePassed(t, this.market.value);
  }

  checkResults() {
    if (!this.selectedTender || this.checkingResults) return;
    const id = this.selectedTender.id;
    this.checkingResults = true;
    this.api.checkTenderResults(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        this.checkingResults = false;
        if (this.selectedTender?.id !== id) return;
        if (r.ok) this.notify.success(r.message || 'Итоги проверены');
        else if (!r.done) this.notify.info(r.message || 'Проверка идёт — обновите карточку через минуту');
        else this.notify.error(r.message || 'Проверка не удалась');
        this.loadLots();
        this.loadTenderProgress();
        this.tenderTimeline?.refresh();
        this.cdr.detectChanges();
      },
      error: e => { this.checkingResults = false; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }
```

  - фильтр: в `filtersState` — `result: this.filterResult`; в `onFiltersChange` — `this.filterResult = f.result;`; в `resetTendersFilter` — `this.filterResult = '';`; в `applyTendersFilter` перед `return true;` — `if (!resultMatches(t.result, this.filterResult)) return false;`.

- [ ] **Step 7: Фильтр «Итоги»** — `tenders-filters.component.ts`:
  - `TendersFilters` — поле `/** «Итоги» (спека 2026-10-22-tender-results-market-prices §11): '' — все, 'CALL', 'DONE', 'OURS'. */ result: string;`;
  - импорт `import { RESULT_LABELS } from '../../shared/tender-results';`, поле `readonly resultLabels = RESULT_LABELS;`;
  - в ряду после select «Наш товар»:

```html
      <select class="f-sel" *ngIf="isKz" [(ngModel)]="filters.result" (change)="emit()" title="Итоги">
        <option value="">Итоги: все</option><option value="CALL">{{ resultLabels['CALL'] }}</option>
        <option value="DONE">{{ resultLabels['DONE'] }}</option><option value="OURS">{{ resultLabels['OURS'] }}</option>
      </select>
```

  - в листе телефона после «Наш товар»:

```html
      <label *ngIf="isKz">Итоги<select [(ngModel)]="filters.result" (change)="emit()"><option value="">Все</option><option value="CALL">{{ resultLabels['CALL'] }}</option><option value="DONE">{{ resultLabels['DONE'] }}</option><option value="OURS">{{ resultLabels['OURS'] }}</option></select></label>
```

  - в `activeChips` — `if (f.result) out.push({ label: RESULT_LABELS[f.result] || f.result, key: 'result' });`.

- [ ] **Step 8: «История»** — `shared/deal.ts`: в `KIND_ICONS` — `RESULT: '🏁',`; в `timelineLink` — `case 'TENDER': return { path: ['/tenders'], query: { openId: i.linkId } };`.

- [ ] **Step 9: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -25`
Expected: сборка без ошибок; начальный бандл ≤ 1,5 МБ (сейчас ≈ 1,45; в выводе — строка `Initial total`); в списке ленивых чанков — `lot-result-panel` и `tender-customer` отдельными чанками (не в начальном).

- [ ] **Step 10: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app && git commit -m "$(cat <<'EOF'
feat(results-ui): итог у лота с панелью участников и цепочки, заказчик и история, «Проверить итоги», чип и фильтр «Итоги»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: Фронт цен рынка — общая панель, строка у лота, позиция заявки, «Запросить цены», строка КП, товары

**Files:**
- Create: `frontend/src/app/shared/market-price.ts`, `frontend/src/app/shared/market-price-panel.component.ts`
- Modify: `frontend/src/app/services/api.service.ts` (`getMarketPrices`, `marketHints`)
- Modify: `frontend/src/app/pages/tenders/tender-lots.component.ts` (строка «Рынок», кнопка, панель), `frontend/src/app/pages/private-requests/pr-lines.component.ts` (кнопка и панель в «⋯»), `pr-rfq-dialog.component.ts` (подсказки по позициям), `frontend/src/app/pages/client-offers/client-offer-items.component.ts` (подсказка и панель в «Подробнее»), `frontend/src/app/pages/product-groups/product-catalog.component.ts` (подсказка у товара)

**Interfaces:**
- Consumes: API задачи 11 (`GET /api/market-prices`, `POST /api/market-prices/hints`), у лота `market: MarketLotSummary`; `plainDate`, `instantText`, `announceUrl` из `shared/tender-results.ts` (задача 13).
- Produces: `shared/market-price.ts` — `MarketCategory`, `MarketStats`, `MarketRow`, `MarketPrice`, `MarketLotSummary`, `MarketHint`, `MarketQuery`; компонент `app-market-price-panel` (`@Input lotId: number | null`, `@Input query: string | null`, `@Output close`); `ApiService.getMarketPrices(q: MarketQuery)`, `marketHints(texts: string[])`.

- [ ] **Step 1: Типы** — `shared/market-price.ts`:

```ts
/** Цены рынка по договорам goszakup (спека 2026-10-22-tender-results-market-prices §9, §10, §13). */
export interface MarketCategory { code: string; name: string | null; description: string | null; count: number; }

export interface MarketStats {
  count: number; median: number | null; p25: number | null; p75: number | null; min: number | null; max: number | null;
  budgetMedian: number | null; lotBudget: number | null; budgetDiffPct: number | null;
}

export interface MarketRow {
  signedAt: string | null; region: string | null; customer: string | null; quantity: number | null; unit: string | null;
  unitPrice: number | null; budgetUnitPrice: number | null; supplierName: string | null; anno: string | null; name: string | null;
}

export interface MarketPrice {
  category: MarketCategory | null; alternatives: MarketCategory[]; unit: string | null; units: string[]; stats: MarketStats;
  bids: { count: number; median: number | null }; rows: MarketRow[]; fetchedAt: string | null; stale: boolean; error: string | null;
}

export interface MarketLotSummary { text: string; count: number; median: number | null; code: string; }

export interface MarketHint { text: string; line: string | null; count: number; median: number | null; code: string | null; }

export interface MarketQuery { lotId?: number | null; q?: string | null; code?: string | null; unit?: string | null; filter?: string | null; }
```

- [ ] **Step 2: API** — `api.service.ts`: `import type { MarketHint, MarketPrice, MarketQuery } from '../shared/market-price';`:

```ts
  /** Цены рынка (спека 2026-10-22-tender-results-market-prices §13): лот или текст; живой запрос — до 20 с. */
  getMarketPrices(p: MarketQuery): Observable<MarketPrice> {
    const params: Record<string, string> = {};
    if (p.lotId != null) params['lotId'] = String(p.lotId);
    if (p.q) params['q'] = p.q;
    if (p.code) params['code'] = p.code;
    if (p.unit) params['unit'] = p.unit;
    if (p.filter) params['filter'] = p.filter;
    return this.http.get<MarketPrice>(`${this.base}/market-prices`, { params });
  }

  /** Подсказки «госзакуп: медиана …» по текстам — только из набора, без площадки. */
  marketHints(texts: string[]): Observable<MarketHint[]> {
    return this.http.post<MarketHint[]>(`${this.base}/market-prices/hints`, { texts });
  }
```

- [ ] **Step 3: Панель** — `shared/market-price-panel.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';
import { MarketMoneyPipe } from '../pipes/market-money.pipe';
import { errorText } from './error-text';
import { MarketPrice } from './market-price';
import { announceUrl, instantText, plainDate } from './tender-results';

/**
 * Цены рынка (спека 2026-10-22-tender-results-market-prices §10): медиана, середина рынка, минимум–максимум, цены участников,
 * фильтр по слову, единица, другая категория, строки договоров. Лот — по коду категории, текст — по ключевому слову. Ленивый чанк.
 */
@Component({
  selector: 'app-market-price-panel',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, MarketMoneyPipe],
  template: `
    <div class="mp">
      <div class="mp-head">
        <b>Цены рынка — договоры goszakup за 2 года</b>
        <button type="button" class="mp-link" (click)="close.emit()">Скрыть</button>
      </div>
      <p class="mp-muted" *ngIf="loading">{{ data ? 'Обновляю…' : 'Собираю договоры — до 20 секунд…' }}</p>
      <p class="mp-err" *ngIf="error">{{ error }}</p>
      <ng-container *ngIf="data as d">
        <p class="mp-muted" *ngIf="!d.category">Категорию не нашли — договоров с таким товаром в наборе нет.</p>
        <ng-container *ngIf="d.category as c">
          <div class="mp-cat">{{ c.name || 'Категория' }}<span class="mp-muted"> · {{ c.code }}</span>
            <span class="mp-muted" *ngIf="c.description"> · {{ c.description }}</span></div>
          <div class="mp-row" *ngIf="d.alternatives.length">Другая категория:
            <button type="button" class="mp-chip" *ngFor="let a of d.alternatives" (click)="pickCategory(a.code)">{{ a.name || a.code }} · {{ a.count }}</button>
          </div>
          <div class="mp-row" *ngIf="d.units.length > 1">Единица:
            <button type="button" class="mp-chip" *ngFor="let u of d.units" [class.mp-on]="u === d.unit" (click)="pickUnit(u)">{{ u }}</button>
          </div>
          <form class="mp-filter" (submit)="applyFilter(); $event.preventDefault()">
            <input [(ngModel)]="filter" name="mpFilter" placeholder="Слово: автомат, монитор…" aria-label="Фильтр по слову" />
            <button type="submit" class="btn btn-line">Найти</button>
            <button type="button" class="mp-link" *ngIf="applied" (click)="filter = ''; applyFilter()">сбросить</button>
          </form>
          <p class="mp-muted" *ngIf="!d.stats.count">Договоров{{ applied ? ' со словом «' + applied + '»' : '' }} за 2 года нет.</p>
          <div class="mp-stats" *ngIf="d.stats.count">
            <div><span class="mp-label">Медиана</span><b>{{ d.stats.median | money }}</b> за {{ d.unit }}</div>
            <div><span class="mp-label">Половина договоров</span>{{ d.stats.p25 | money }} – {{ d.stats.p75 | money }}</div>
            <div><span class="mp-label">Мин – макс</span>{{ d.stats.min | money }} – {{ d.stats.max | money }}</div>
            <div><span class="mp-label">Договоров</span>{{ d.stats.count }}</div>
            <div *ngIf="d.stats.budgetMedian"><span class="mp-label">Медиана бюджета</span>{{ d.stats.budgetMedian | money }}</div>
            <div *ngIf="d.stats.lotBudget && d.stats.budgetDiffPct != null"><span class="mp-label">Бюджет здесь</span>{{ d.stats.lotBudget | money }} ({{ pct(d.stats.budgetDiffPct) }})</div>
            <div *ngIf="d.bids.count"><span class="mp-label">Цены участников</span>медиана {{ d.bids.median | money }} · {{ d.bids.count }} предл.</div>
          </div>
          <table class="responsive-cards card-grid mp-table" *ngIf="d.rows.length">
            <thead><tr><th>Дата</th><th>Регион</th><th>Заказчик</th><th>Кол-во</th><th>Цена договора</th><th>Бюджет</th><th>Поставщик</th></tr></thead>
            <tbody>
              <tr *ngFor="let r of d.rows">
                <td data-label="Дата">{{ date(r.signedAt) || '—' }}</td>
                <td data-label="Регион">{{ r.region || '—' }}</td>
                <td data-label="Заказчик">{{ r.customer || '—' }}<a *ngIf="r.anno" class="mp-anno" [href]="url(r.anno)" target="_blank" rel="noopener">№ {{ r.anno }} ↗</a></td>
                <td data-label="Кол-во">{{ r.quantity ?? '—' }} {{ r.unit }}</td>
                <td data-label="Цена договора">{{ r.unitPrice | money }}</td>
                <td data-label="Бюджет">{{ r.budgetUnitPrice | money }}</td>
                <td data-label="Поставщик">{{ r.supplierName || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </ng-container>
        <p class="mp-muted">{{ freshness(d) }}</p>
      </ng-container>
    </div>
  `,
  styles: [`
    .mp { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin: 10px 0; background: var(--surface); }
    .mp-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .mp-link { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 13px; padding: 4px 0; }
    .mp-muted { color: var(--text-muted); font-size: 13px; margin: 6px 0; }
    .mp-err { color: var(--danger-text); font-size: 13px; }
    .mp-cat { margin: 8px 0 4px; font-weight: 500; color: var(--text); overflow-wrap: anywhere; }
    .mp-row { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 13px; color: var(--text-muted); margin: 6px 0; }
    .mp-chip { font-size: 12px; padding: 3px 10px; border-radius: 999px; border: 1px solid var(--border); background: var(--surface);
               color: var(--text); cursor: pointer; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .mp-chip.mp-on { background: color-mix(in srgb, var(--accent) 15%, transparent); border-color: var(--accent); color: var(--accent); }
    .mp-filter { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 8px 0; }
    .mp-filter input { flex: 1 1 200px; min-width: 0; height: 34px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px;
                       background: var(--surface); color: var(--text); }
    .mp-stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 6px 16px; margin: 8px 0; font-size: 13px; color: var(--text); }
    .mp-label { display: block; font-size: 12px; color: var(--text-muted); }
    .mp-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .mp-table th, .mp-table td { padding: 6px 8px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
    .mp-anno { display: block; font-size: 12px; color: var(--accent); }
    @media (max-width: 900px) {
      .mp-link, .mp-chip { min-height: 40px; }
      .mp-filter input { height: 40px; }
      .mp-table tr { grid-template-columns: 1fr auto; grid-template-areas: "cust price" "reg date" "sup qty"; }
      .mp-table td[data-label="Заказчик"] { grid-area: cust; }
      .mp-table td[data-label="Цена договора"] { grid-area: price; text-align: right; font-weight: 600; }
      .mp-table td[data-label="Регион"] { grid-area: reg; color: var(--text-muted); font-size: 12px; }
      .mp-table td[data-label="Дата"] { grid-area: date; text-align: right; color: var(--text-muted); font-size: 12px; }
      .mp-table td[data-label="Поставщик"] { grid-area: sup; color: var(--text-muted); font-size: 12px; }
      .mp-table td[data-label="Кол-во"] { grid-area: qty; text-align: right; color: var(--text-muted); font-size: 12px; }
      .mp-table td[data-label="Бюджет"] { display: none; }
    }
  `],
})
export class MarketPricePanelComponent implements OnChanges {
  @Input() lotId: number | null = null;
  @Input() query: string | null = null;
  @Output() close = new EventEmitter<void>();

  data: MarketPrice | null = null;
  loading = false;
  error = '';
  filter = '';
  applied = '';
  private code: string | null = null;
  private unit: string | null = null;
  private seq = 0;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnChanges() {
    this.code = null;
    this.unit = null;
    this.filter = this.applied = '';
    this.data = null;
    this.load();
  }

  pickCategory(code: string) { this.code = code; this.unit = null; this.load(); }
  pickUnit(u: string) { this.unit = u; this.load(); }
  applyFilter() { this.applied = this.filter.trim(); this.load(); }

  private load() {
    const n = ++this.seq;
    this.loading = true;
    this.error = '';
    this.api.getMarketPrices({ lotId: this.lotId, q: this.lotId == null ? this.query : null, code: this.code, unit: this.unit,
      filter: this.applied || null }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: d => { if (n !== this.seq) return; this.data = d; this.loading = false; this.cdr.markForCheck(); },
      error: e => { if (n !== this.seq) return; this.loading = false; this.error = 'Цены рынка не загрузились: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  freshness(d: MarketPrice): string {
    const when = d.fetchedAt ? 'Обновлено с площадки ' + instantText(d.fetchedAt) : '';
    if (d.stale && d.error) return (d.error.charAt(0).toUpperCase() + d.error.slice(1)) + (when ? '; ' + when.toLowerCase() : '');
    return when;
  }

  pct(n: number): string { return (n < 0 ? '−' + (-n) : '+' + n) + ' %'; }
  date(iso: string | null): string { return plainDate(iso); }
  url(anno: string): string { return announceUrl(anno); }
}
```

- [ ] **Step 4: Лот тендера** — `tender-lots.component.ts`:
  - импорт `import { MarketPricePanelComponent } from '../../shared/market-price-panel.component';` и в `imports` компонента;
  - в шапке лота после строки итога:

```html
          <div class="lot-mkt" *ngIf="l.market" (click)="openMarket(l, $event)" title="Цены договоров goszakup за 2 года — подробно">📊 {{ l.market.text }}</div>
```

  - в `lot-actions` после кнопки «КП»: `<button class="btn btn-line" *ngIf="isKz()" (click)="openMarket(l)" title="Цены договоров goszakup за 2 года">Цены рынка</button>`;
  - в теле лота после `@defer` панели итога:

```html
        @defer (when marketOpen.has(l.id)) {
          <app-market-price-panel *ngIf="marketOpen.has(l.id)" [lotId]="l.id" (close)="marketOpen.delete(l.id)"></app-market-price-panel>
        }
```

  - стиль `.lot-mkt { margin-top: 3px; font-size: 13px; color: var(--text-muted); cursor: pointer; overflow-wrap: anywhere; }`;
  - поля и метод:

```ts
  /** Лоты, у которых открыта панель цен рынка. */
  marketOpen = new Set<number>();

  /** Строка «Рынок» (в шапке — без сворачивания лота) или кнопка «Цены рынка»: лот разворачивается, панель переключается. */
  openMarket(l: any, e?: Event) {
    e?.stopPropagation();
    if (this.expandedLotId !== l.id) {
      if (this.expandedLotId !== null) this.closePanelsOf(this.expandedLotId);
      this.expandedLotId = l.id;
      this.marketOpen.add(l.id);
    } else if (this.marketOpen.has(l.id)) this.marketOpen.delete(l.id);
    else this.marketOpen.add(l.id);
    this.cdr.detectChanges();
  }
```

  - в `closePanelsOf` — `this.marketOpen.delete(lotId);`; в `resyncToLots` — `for (const id of Array.from(this.marketOpen)) if (!alive.has(id)) this.marketOpen.delete(id);`.

- [ ] **Step 5: Позиция частной заявки** — `pr-lines.component.ts`:
  - импорт `MarketPricePanelComponent` (`'../../shared/market-price-panel.component'`) и в `imports`;
  - поле `marketFor: number | null = null;`;
  - в `detail-actions` после ссылки «на сайте ↗»: `<button type="button" class="btn btn-line" *ngIf="isKz" (click)="marketFor = marketFor === l.lotId ? null : l.lotId">Цены рынка</button>`;
  - после `</div>` блока `detail-actions` (внутри `.details`):

```html
            @defer (when marketFor === l.lotId) {
              <app-market-price-panel class="mkt-panel" *ngIf="marketFor === l.lotId" [lotId]="l.lotId" (close)="marketFor = null"></app-market-price-panel>
            }
```

  - стиль `.mkt-panel { grid-column: 1 / -1; display: block; }`.

- [ ] **Step 6: Окно «Запросить цены»** — `pr-rfq-dialog.component.ts`:
  - импорт `MarketService` (`'../../services/market.service'`), параметр конструктора `private marketSvc: MarketService`;
  - поле `hints: { name: string; line: string }[] = [];`;
  - в конце `ngOnInit`:

```ts
    // «Госзакуп по позициям» (решение 8 плана) — одним блоком над поставщиками, только из набора
    if (this.marketSvc.value === 'KZ' && this.lines.length) {
      const texts = this.lines.map(l => [l.name, l.brand].filter(x => !!x).join(' '));
      this.api.marketHints(texts).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: hs => {
          this.hints = hs.map((h, i) => ({ name: this.lines[i]?.name || h.text, line: h.line || '' })).filter(h => !!h.line);
          this.cdr.markForCheck();
        },
        error: () => { /* подсказка — не главное: окно работает и без неё */ },
      });
    }
```

  - в шаблоне после `<div class="empty" *ngIf="loading">Подбираю поставщиков…</div>`:

```html
        <div class="mkt" *ngIf="hints.length">
          <div class="mkt-title">Госзакуп по позициям</div>
          <div class="mkt-row" *ngFor="let h of hints"><span class="mkt-name">{{ h.name }}</span> — {{ h.line }}</div>
        </div>
```

  - стили: `.mkt { border: 1px dashed var(--border); border-radius: 8px; padding: 8px 10px; margin-bottom: 10px; font-size: 13px; } .mkt-title { font-weight: 600; margin-bottom: 4px; color: var(--text); } .mkt-row { color: var(--text-muted); overflow-wrap: anywhere; } .mkt-name { color: var(--text); }`.

- [ ] **Step 7: Строка КП** — `client-offer-items.component.ts`:
  - импорт `MarketPricePanelComponent` (`'../../shared/market-price-panel.component'`) и в `imports`;
  - поля `marketFor: string | null = null;` и `private hints = new Map<string, string | null>();`;
  - `toggleOpen`:

```ts
  toggleOpen(it: OfferItem) {
    it._open = !it._open;
    if (it._open && this.isKz) this.loadHint(it);
  }

  /** «госзакуп: медиана …» — по тексту строки, один раз на текст (только из набора). */
  private loadHint(it: OfferItem) {
    const t = this.itemText(it);
    if (!t || this.hints.has(t)) return;
    this.hints.set(t, null);
    this.api.marketHints([t]).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: hs => { this.hints.set(t, hs[0]?.line ?? null); this.cdr.markForCheck(); },
      error: () => { /* без подсказки */ },
    });
  }

  hintFor(it: OfferItem): string | null { return this.hints.get(this.itemText(it)) ?? null; }

  itemText(it: OfferItem): string { return [it.name, it.model].filter(x => !!x).join(' ').trim(); }
```

  - в `.details` после блока `wide supplier`:

```html
          <div class="wide mkt" *ngIf="isKz">
            <span class="mkt-hint" *ngIf="hintFor(it) as h">{{ h }}</span>
            <button type="button" class="btn btn-line" (click)="marketFor = marketFor === it.key ? null : it.key; $event.stopPropagation()">Цены рынка</button>
          </div>
          @defer (when marketFor === it.key) {
            <app-market-price-panel class="wide" *ngIf="marketFor === it.key" [lotId]="it.tenderLotId ?? null"
              [query]="it.tenderLotId ? null : itemText(it)" (close)="marketFor = null"></app-market-price-panel>
          }
```

  - стили: `.mkt { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; font-size: 13px; } .mkt-hint { color: var(--text-muted); }`.

  (`takeUntilDestroyed` — импорт из `@angular/core/rxjs-interop`, если в файле ещё нет.)

- [ ] **Step 8: Товары «Нашей продукции»** — `product-catalog.component.ts`:
  - поле `private hints = new Map<string, string>();` и метод `hint(p: ProductCatalogItem): string | null { return this.hints.get(p.name) ?? null; }`;
  - в `ngOnInit` в `next` после `this.state = s;`:

```ts
        if (s.kz && s.items.length) {
          this.api.marketHints(s.items.map(p => p.name)).subscribe({
            next: hs => { hs.forEach(h => { if (h.line) this.hints.set(h.text, h.line); }); this.cdr.detectChanges(); },
            error: () => { /* без подсказок */ },
          });
        }
```

  - в `pc-meta` перед «РУ»: `<span class="pc-mkt" *ngIf="hint(p) as h">{{ h }}</span>`; стиль `.pc-mkt { font-size: 12px; color: var(--text-muted); }`.

- [ ] **Step 9: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -25`
Expected: без ошибок; начальный бандл ≤ 1,5 МБ; `market-price-panel` — отдельный ленивый чанк (в выводе `Lazy chunk files`), в начальном — только типы и вызовы API.

- [ ] **Step 10: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app && git commit -m "$(cat <<'EOF'
feat(market-ui): панель цен рынка — у лота, позиции заявки, строки КП; подсказки в «Запросить цены» и у товаров

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: Полный прогон, живая проверка, документация, ревью ветки, мерж

**Files:**
- Modify: `CLAUDE.md` (§5 — подраздел «Итоги тендеров и цены рынка — локально», §7 — V43, §8 — блок, §12 — фронт, §13 — прогон, §14 — найденное, §15 — API, §16 — ограничения и бэклог), `DEPLOY.md` (новый §12), `docs/PROGRESS.md` («▶ Последняя задача»), `.env.example` (ключи `TENDER_RESULTS_*`, закомментированы), память `last-task-pointer.md`
- Ledger: `.superpowers/sdd/2026-10-22-tender-results-market-prices/progress.md` (git-ignored) — решения, мутации, найденное

- [ ] **Step 1: Полный прогон**

```bash
lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill
cd /Users/vlad/IdeaProjects/AIS && ./gradlew cleanTest test 2>&1 | tee .superpowers/sdd/2026-10-22-tender-results-market-prices/full-run.log | tail -30
```

(sandbox off; ≈ 25–30 мин.) Expected: `BUILD SUCCESSFUL`, 0 падений; число тестов и классов записать в леджер и в CLAUDE.md §13. Упало — разбираться по причине, не подгонять ожидания.

- [ ] **Step 2: Сборка фронта**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -25`
Expected: без ошибок; начальный бандл ≤ 1,5 МБ (записать: было 1,45).

- [ ] **Step 3: Живая проверка — запуск** (локально; токен goszakup общий с продом — малыми порциями; Telegram — только заглушка)

До проверки записать: `select max(id) from tender; select count(*) from tender_lot_result; select count(*) from market_contract; select count(*) from goszakup_subject;`, а группы «нашей продукции» сузить: `update product_group set active = (id in (1, 2, 10)) where market = 'KZ';`.

```bash
node scripts/telegram-stub.mjs &      # 127.0.0.1:7709
cd frontend && npm start &             # 4200
cd /Users/vlad/IdeaProjects/AIS && JAVA_TOOL_OPTIONS=-Xmx1g GOSZAKUP_TOKEN="$(cat ~/.config/ais/goszakup.token)" \
  TENDER_RESULTS_ENABLED=true TENDER_RESULTS_CRON=- TENDER_RESULTS_MARKET_CRON=- TENDER_RESULTS_DIGEST_CRON="0 */2 * * * *" \
  TENDERS_NOTIFY_ENABLED=true TELEGRAM_ENABLED=true TELEGRAM_API_URL=http://127.0.0.1:7709 TELEGRAM_BOT_TOKEN=dev:token \
  TELEGRAM_CHAT_ID=-1001 TELEGRAM_TENDERS_THREAD_ID=55 TELEGRAM_PRODUCTS_THREAD_ID=95 ./gradlew bootRun
```

(sandbox off). В логе при старте — `Итоги тендеров: выключено расписанием «-»`, `Сводка итогов тендеров: «0 */2 * * * *»`, `Сбор цен рынка: выключено расписанием «-»`.

- [ ] **Step 4: Живая проверка — итоги** (браузер `http://localhost:4200`, admin, рынок KZ; 1280 и 390, обе темы)

1. Тендер ЗКО с прошедшим сроком (`select id, tender_number from tender where market = 'KZ' and platform = 'GOSZAKUP' and deadline_at < now() - interval '1 day' order by deadline_at desc limit 5;`) → «Проверить итоги» → тост «Итоги: тендеров проверено 1, событий N (первый проход — без сообщений)»; у лотов — строка итога («✓ Состоялся · победитель …» / «✗ Не состоялся · 0 заявок — объявят заново»).
2. Нажатие на строку итога → панель: участники (места, цены, победитель и 2-е место выделены), договор (если есть), попытки закупки со ссылками, «протокол ↗».
3. «Заказчик на площадке» — название, ☎, почта; «История заказчика» → строки договоров (той же категории — подсвечены).
4. «История» тендера — строки «Итоги: …» с временем площадки; этап «Итог» — подпись итога.
5. Список «Тендеры» — чип итога; фильтр «Итоги: Не состоялся — звонить / Состоялся / Мы участвовали».
6. Telegram: первый проход молчит (`curl -s 127.0.0.1:7709/__messages` — пусто). Сделать одно событие ждущим: `update tender_result_event set notified_at = null, created_at = now() where id = (select id from tender_result_event where urgent and topic <> 'NONE' order by id desc limit 1);` (нет такого — `update tender_result_event set urgent = true, kind = 'NO_BIDS', topic = 'TENDERS', notified_at = null, created_at = now() where id = (select max(id) from tender_result_event);`) → ещё раз «Проверить итоги» → в заглушке сообщение «📞 Звонить: не состоялось 1» в теме 55, тихо (`silent: true`), с ☎ заказчика и «Открыть в АИС».
7. Сводка: `update tender_result_event set digested_at = null, topic = 'PRODUCTS', urgent = false, created_at = now() where id = (select max(id) from tender_result_event where not urgent);` → в течение 2 мин — «📊 Итоги по нашим тендерам: 1» в теме 95, тихо.
8. Оператор (operator/operator): строки и панели видны, кнопки «Проверить итоги» нет, 0 запросов записи.
9. РФ: ни строк итога, ни заказчика.

- [ ] **Step 5: Живая проверка — цены рынка**

1. Лот тендера KZ → «Цены рынка» → «Собираю договоры — до 20 секунд…» → медиана, середина рынка, мин–макс, строки договоров; «Другая категория», «Единица», фильтр по слову; повторное открытие — сразу (свежесть сутки, `select * from market_lookup;`).
2. Строка «Рынок: медиана …» у лота после перезагрузки карточки (из набора).
3. Частная заявка KZ: позиция «⋯ → Цены рынка» — панель по названию; «Запросить цены…» — блок «Госзакуп по позициям».
4. КП клиенту: строка «Подробнее» → «госзакуп: медиана …» и «Цены рынка».
5. «Наша продукция → Товары» — «госзакуп: медиана … (N дог.)» у товаров с данными.
6. Сбор раз в сутки: перезапустить бэкенд с `TENDER_RESULTS_MARKET_CRON="0 */3 * * * *"` (группы сужены) → в логе `Сбор цен рынка: Цены рынка: слов N с …, строк договоров M`; в `market_lookup` — строка `daily`.
7. Пачка бота / «Новый тендер» — строка «рынок: …»: при включённых `PRODUCT_WATCH_ENABLED=true PRODUCT_WATCH_NOTIFY=ALL TELEGRAM_BOT_ACTIONS_ENABLED=true` и сброшенном `product_notified_level` у тендера с отметкой — «Искать сейчас» → в заглушке строка «рынок: медиана …» под блоком тендера.
8. 390 px: панели карточками, без горизонтальной прокрутки; тёмная тема — токены; оператор — только просмотр.

Найденное живьём — правка с тестом, красным до неё (фронт — до/после в браузере), запись в леджер.

- [ ] **Step 6: Уборка** (только dev) — ОСТАНОВИТЬ бэкенд, потом SQL:

```sql
delete from tender_result_event; delete from tender_lot_result;            -- участники уходят каскадом
delete from tender where id > <max_tender>;                                 -- заведённые повторы
update product_group set active = true where market = 'KZ';
update product_group set updated_at = null, updated_by = null where market = 'KZ';
```

`market_contract`, `market_lookup`, `goszakup_subject` — оставить (данные площадки, тесты их не задевают) или убрать по желанию. Заглушку, `ng serve` — по LISTEN-портам (7709, 4200).

- [ ] **Step 7: Документация**
  - CLAUDE.md §5 — подраздел «### Итоги тендеров и цены рынка — локально (2026-10-22)»: выключено по умолчанию (`TENDER_RESULTS_ENABLED`), команда бэкенда из шага 3, SQL записи до проверки, уборка из шага 6, «первый проход молчит — как сделать событие ждущим» (шаг 4.6).
  - §7 — абзац V43: поля площадки у лота, `tender_lot_result`, `tender_lot_bid`, `tender_result_event`, `market_contract`, `market_lookup`, `goszakup_subject` (одной строкой каждая, как соседние абзацы).
  - §8 — блок «Итоги тендеров, «звонить заказчику» и цены рынка (2026-10-22, ветка `feature/tender-results-market-prices`, V43)»: что сделано по разделам спеки, решения при планировании 1–12, проверено (тесты с мутациями, живая проверка), найденное и исправленное.
  - §12 — «Итоги и цены рынка — фронт»: ленивые панели (`@defer`), `shared/tender-results.ts`, `shared/market-price.ts`, фильтр «Итоги», начальный бандл.
  - §13 — число тестов полного прогона.
  - §14 — что нашла живая проверка (если что-то общее).
  - §15 — пути API задач 9–11.
  - §16 — ограничения спеки §17 и что не делалось (итоги по всей стране, СК-Фармация, «Прямая продажа», прогноз цены).
  - DEPLOY.md §12 «Итоги тендеров и цены рынка»: push оператора (V43 только добавляет) → с согласия оператора `TENDER_RESULTS_ENABLED=true` в `/srv/ais/.env` (пустым не оставлять) → `docker compose up -d ais-backend` → в логе строки расписаний; первый проход итогов (в :50) молча собирает 30 дней, первый сбор договоров (7:15) — 2 года, ≈ 5–10 мин; проверки: `select outcome, count(*) from tender_lot_result group by 1;`, `select count(*) from market_contract;`; откат — `TENDER_RESULTS_ENABLED=false` (или откат кода без SQL); при 403 goszakup — оповещение «⚠️ Проверка итогов тендеров не работает» после 2 проходов.
  - `.env.example` — закомментированные `# TENDER_RESULTS_ENABLED=false`, `# TENDER_RESULTS_CRON=`, `# TENDER_RESULTS_MARKET_CRON=`, `# TENDER_RESULTS_DIGEST_CRON=` с пометкой «пустыми не вписывать».
  - PROGRESS.md «▶ Последняя задача» и память `last-task-pointer.md` — состояние блока, что ждёт push и включения.

- [ ] **Step 8: Ревью ветки (самому — CLAUDE.md §2)**

```bash
cd /Users/vlad/IdeaProjects/AIS && git diff main...feature/tender-results-market-prices --stat && git diff main...feature/tender-results-market-prices > /private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/93eed009-aee1-43c1-b2a3-1fbf579e9650/scratchpad/branch.diff
```

Читать дифф целиком свежим взглядом по Review Focus (5 пунктов в начале плана) и по списку: гард рынка у каждого пути по id (`findById` фильтр не режет); сеть вне транзакций; ни одного токена в логах и текстах; `TelegramText.oneLine` у всех полей площадки; обезличенность фикстур (`grep -E '[0-9]{12}' src/test/resources/goszakup/protocols/*` — только `9000000000NN` и БИН больниц); пустые env не роняют старт (`TENDER_RESULTS_CRON=` — умолчание). Найденное — правка с тестом, красным до неё; итог ревью — в леджер и CLAUDE.md §8.

- [ ] **Step 9: Мерж** (после зелёного прогона и ревью)

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout main && git merge --ff-only feature/tender-results-market-prices && git branch -d feature/tender-results-market-prices
```

Push — оператор: `! git push origin main` (авторежим не пускает агента в деплой, CLAUDE.md §5). После деплоя — проверить по SSH с согласия оператора: V43 применена, строки расписаний в логе (выключено, пока `TENDER_RESULTS_ENABLED` не задан).

