package com.vladoose.nir.service.supplierreply;

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
        assertThat(priceRequests.findById(pr.getId()).orElseThrow().getStatus()).isEqualTo("RESPONDED");   // цена в рублях — тоже ответ
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

    /** Тексты отказа (спека §4.4): нет нашего курса — куда его вписать; неизвестная валюта — прямо. */
    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void refusalTexts() throws Exception {
        MarketContext.clear();
        mvc.perform(put("/api/price-requests/" + pr.getId() + "/responses").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"itemId\":" + itemId + ",\"responseCurrency\":\"RUB\",\"responseOriginal\":150000}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Впишите наш курс для руб. в «Система → Курсы валют»"));
        mvc.perform(put("/api/price-requests/" + pr.getId() + "/responses").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"itemId\":" + itemId + ",\"responseCurrency\":\"XYZ\",\"responseOriginal\":150000}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Неизвестная валюта цены: «XYZ» (позиция " + itemId + ")"));
        MarketContext.set(Market.KZ);
    }
}
