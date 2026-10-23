package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CurrencyRatesRequest;
import com.vladoose.nir.dto.request.LinesMode;
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
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
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

    private JsonNode save(JsonNode offer, ObjectNode item) throws Exception {
        ObjectNode body = offer.deepCopy();
        ArrayNode items = body.putArray("items");
        items.add(item);
        return json(kz(put("/api/client-offers/" + offer.get("id").asLong())).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    @Test
    void rubles_serverConverts_todayTotalsInResponse() throws Exception {
        JsonNode o = json(kz(post("/api/client-offers")));
        JsonNode saved = save(o, rubItem("150000", null));
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
        JsonNode saved = save(o, rubItem("150000", null));
        MarketContext.set(Market.KZ);
        rates.save(Market.KZ, new CurrencyRatesRequest(List.of(new CurrencyRatesRequest.Entry("RUB", new BigDecimal("6.5")))), "vlad");
        ObjectNode stale = rubItem("150000", 6.0);
        stale.put("id", saved.get("items").get(0).get("id").asLong());
        JsonNode again = save(saved, stale);
        assertThat(again.get("items").get(0).get("purchasePrice").decimalValue()).isEqualByComparingTo("900000");   // курс строки
        ObjectNode recalc = rubItem("150000", 6.5);                                                                // «Пересчитать»
        recalc.put("id", stale.get("id").asLong());
        JsonNode latest = save(again, recalc);
        assertThat(latest.get("items").get(0).get("purchasePrice").decimalValue()).isEqualByComparingTo("975000");
        ObjectNode foreign = rubItem("150000", 7.0);
        foreign.put("id", stale.get("id").asLong());
        ObjectNode body = latest.deepCopy();
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
        JsonNode saved = save(o, rubItem("150000", null));
        ObjectNode tenge = (ObjectNode) saved.get("items").get(0).deepCopy();
        tenge.put("purchaseCurrency", "KZT");
        tenge.put("purchasePrice", 880000);
        JsonNode back = save(saved, tenge);
        assertThat(back.get("items").get(0).get("purchaseCurrency").isNull()).isTrue();
        assertThat(back.get("items").get(0).get("purchasePrice").decimalValue()).isEqualByComparingTo("880000");
    }

    /** КП из заявки: рубли ответа (исходная цена и курс) переходят в строку КП (шаг 5). */
    @Test
    void fromRequest_rublesOfAnswerGoToLine() throws Exception {
        MarketContext.set(Market.KZ);
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(com.vladoose.nir.service.PrivateRequestService.input("ZZ КП рубли ДКИ-Н-12", "Аксион", 1, null, null, null)),
                LinesMode.APPEND);
        Distributor d = distributors.save(Distributor.builder().name("ZZ КП рубли " + System.nanoTime()).email("d@x.kz").build());
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("RESPONDED")
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).build());
        pr.getItems().add(priceItems.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(t.getLots().get(0)).requestedQuantity(1)
                .responsePrice(new BigDecimal("900000")).responseCurrency("RUB").responseOriginal(new BigDecimal("150000"))
                .responseRate(new BigDecimal("6")).build()));
        priceItems.flush();
        JsonNode r = json(kz(post("/api/client-offers/from-private-request/" + t.getId())));
        JsonNode line = r.get("items").get(0);
        assertThat(line.get("purchasePrice").decimalValue()).isEqualByComparingTo("900000");
        assertThat(line.get("purchaseCurrency").asText()).isEqualTo("RUB");
        assertThat(line.get("purchaseOriginal").decimalValue()).isEqualByComparingTo("150000");
        assertThat(line.get("purchaseRate").decimalValue()).isEqualByComparingTo("6");
    }

    /** «Дублировать» — копия строки с рублями закупки (исходная цена и курс те же). */
    @Test
    void duplicate_keepsRubles() throws Exception {
        JsonNode o = json(kz(post("/api/client-offers")));
        save(o, rubItem("150000", null));
        JsonNode copy = json(kz(post("/api/client-offers/" + o.get("id").asLong() + "/duplicate")));
        JsonNode line = copy.get("items").get(0);
        assertThat(line.get("purchaseCurrency").asText()).isEqualTo("RUB");
        assertThat(line.get("purchaseOriginal").decimalValue()).isEqualByComparingTo("150000");
        assertThat(line.get("purchaseRate").decimalValue()).isEqualByComparingTo("6");
    }
}
