package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.request.LinesMode;
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

    /** Курс сменили после «Всё верно»: цена идёт за курсом, у позиции — новый курс, исходная цена та же. */
    @Test
    void followRate_updatesStoredRate() {
        ourRub("6");
        Long f = replies.ingest(priceRequests.findById(pr.getId()).orElseThrow(), List.of(parsed("КП руб.xlsx", rub("150 000"))), null).fileIds().get(0);
        replies.verify(pr.getId());
        replies.setRate(pr.getId(), f, new BigDecimal("5.9"));
        PriceRequestItem it = items.findById(pr.getItems().get(0).getId()).orElseThrow();
        assertThat(it.getResponsePrice()).isEqualByComparingTo("885000");
        assertThat(it.getResponseRate()).isEqualByComparingTo("5.9");
        assertThat(it.getResponseOriginal()).isEqualByComparingTo("150000");
        assertThat(it.getResponseCurrency()).isEqualTo("RUB");
    }
}
