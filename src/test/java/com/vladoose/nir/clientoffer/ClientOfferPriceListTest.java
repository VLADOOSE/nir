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
