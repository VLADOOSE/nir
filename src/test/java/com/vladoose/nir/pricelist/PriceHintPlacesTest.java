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
