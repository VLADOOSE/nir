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
        Long noBag = fresh.getItems().get(1).getId();
        assertThat(hints.history(noBag).get(0).price()).isEqualByComparingTo("140000");   // своя комплектация — «без сумки»
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
