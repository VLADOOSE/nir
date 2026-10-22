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
        // услуга со словом в названии — не категория цен товара и не вариант на выбор (живая проверка 2026-10-22)
        for (int i = 0; i < 3; i++) {
            contracts.saveAndFlush(MarketContract.builder().lotExtId(nextLot++).enstruCode("331312.200.000001")
                    .name("Услуги по техническому обслуживанию дефибрилляторов").description("сервис").unit("Одна услуга")
                    .unitPrice(new BigDecimal("50000")).signedAt(LocalDate.of(2026, 4, 1)).build());
        }
        MarketPriceResponse r = service.forText("Дефибриллятор для терапии нарушений сердечного ритма", null, null, null);
        assertThat(r.category().code()).isEqualTo(CODE);
        assertThat(r.alternatives()).extracting(c -> c.code()).containsExactly("266011.300.000000");
        assertThat(service.forText("Дефибриллятор", "266011.300.000000", null, null).stats().median()).isEqualByComparingTo("7500000");
        // заказчик в строке — коротко (на телефоне карточка росла на десяток строк)
        contracts.saveAndFlush(MarketContract.builder().lotExtId(nextLot++).enstruCode(CODE).name("Дефибриллятор").unit("Штука")
                .customerName("Коммунальное государственное предприятие на праве хозяйственного ведения «Областная больница»")
                .unitPrice(new BigDecimal("150")).signedAt(LocalDate.of(2026, 9, 1)).build());
        assertThat(service.forText("Дефибриллятор", CODE, null, null).rows().get(0).customer()).isEqualTo("КГП на ПХВ «Областная больница»");
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

    /**
     * Строка без единицы (лот 2026 года без пункта плана): единица — та, что у всех строк категории с единицей;
     * у категории с разными единицами — строка в числа не идёт (штуки не смешиваем с упаковками).
     */
    @Test
    void rowWithoutUnit_soleUnitOfCategory_orNotCounted() {
        row("50000", "Упаковка", null, null);
        row("60000", null, null, null);
        MarketPriceResponse one = service.forText("Дефибриллятор", CODE, null, null);
        assertThat(one.stats().count()).isEqualTo(2);
        assertThat(one.rows()).extracting(MarketPriceResponse.Row::unit).as("строка без единицы — в единице категории").containsOnly("уп");
        row("1000000", "Штука", null, null);
        MarketPriceResponse r = service.forText("Дефибриллятор", CODE, "уп", null);
        assertThat(r.stats().count()).as("единицы разные — строка без единицы не в счёт").isEqualTo(1);
        assertThat(r.units()).containsExactlyInAnyOrder("шт", "уп");
    }

    /** Лот без кода (площадка не отдала пункт плана) — строка «Рынок» по названию, как у сообщений Telegram. */
    @Test
    void lotWithoutCode_summaryByName() {
        row("1340000", "Штука", null, null);
        row("1340000", "Штука", null, null);
        TenderLot l = lot("Штука", "1420000");
        l.setEnstruCode(null);
        lots.saveAndFlush(l);
        var s = service.lotSummaries(List.of(l)).get(l.getId());
        assertThat(s).isNotNull();
        assertThat(s.count()).isEqualTo(2);
        assertThat(s.code()).isEqualTo(CODE);
    }
}
