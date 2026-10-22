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
        assertThat(w.chip()).as("форма собственности коротко — иначе многоточие съедало имя").isEqualTo("итог: ИП Әбдібай Айдос 251 тыс. ₸");
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
