package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * «Указать клиента» в панели «КП» тендера (спека 2026-10-21-tender-telegram-bot §10 п. 2): строка о больнице — только тем,
 * кого отметили; правленый текст при смешанном составе — 400 (строка о больнице не уйдёт дилеру). SMTP в тестах не поднят —
 * письма «не ушли», запросы записаны: проверяется флаг disclose_client.
 */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class TenderKpClientLineTest {

    @Autowired WebApplicationContext wac;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired EmailTemplateRepository templates;
    MockMvc mvc;
    TenderLot lot;
    Distributor maker, dealer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        templates.deleteAllInBatch();   // шаблон письма рынка правит оператор — ждём зашитый; откат — транзакцией теста
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        maker = TgFixtures.distributor(distributors, "ZZ Завод", "m@x.kz", true, false, "ZZБренд");
        dealer = TgFixtures.distributor(distributors, "ZZ Дилер", "d@x.kz", false, false, "ZZБренд");
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private String req(List<Long> ids, List<Long> client, String bodyOverride) {
        return "{\"tenderId\":" + lot.getTender().getId() + ",\"distributorIds\":" + ids + ",\"discloseClientIds\":" + client
                + ",\"items\":[{\"tenderLotId\":" + lot.getId() + ",\"requestedQuantity\":1}]"
                + (bodyOverride == null ? "" : ",\"bodyOverride\":\"" + bodyOverride + "\"") + "}";
    }

    @Test
    void previewHasClientLineOnlyForChosen() throws Exception {
        mvc.perform(post("/api/price-requests/preview").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content(req(List.of(maker.getId()), List.of(maker.getId()), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(containsString("Конечный заказчик: ZZ Горбольница №7")));
        MarketContext.set(Market.KZ);
        mvc.perform(post("/api/price-requests/preview").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content(req(List.of(dealer.getId()), List.of(), null)))
                .andExpect(jsonPath("$.body").value(not(containsString("Конечный заказчик"))));
    }

    @Test
    void sendSplitsByClient_andEditedMixedIs400() throws Exception {
        mvc.perform(post("/api/price-requests/send").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content(req(List.of(maker.getId(), dealer.getId()), List.of(maker.getId()), null)))
                .andExpect(status().isOk());
        MarketContext.set(Market.KZ);
        assertThat(priceRequests.findAll()).filteredOn(p -> p.getTender().getId().equals(lot.getTender().getId()))
                .extracting(p -> p.getDistributor().getId(), PriceRequest::isDiscloseClient)
                .containsExactlyInAnyOrder(tuple(maker.getId(), true), tuple(dealer.getId(), false));
        mvc.perform(post("/api/price-requests/send").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content(req(List.of(maker.getId(), dealer.getId()), List.of(maker.getId()), "правка")))
                .andExpect(status().isBadRequest());
    }
}
