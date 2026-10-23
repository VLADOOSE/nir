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
