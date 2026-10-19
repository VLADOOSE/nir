package com.vladoose.nir.privaterequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestStageCalculator;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Заявка ↔ КП клиенту (спека 2026-10-12 §5.1, §5.4 правило 2, §8): этап, раздел «КП клиенту», номер заявки в КП. */
@SpringBootTest
@Transactional
class PrivateRequestStage3Test {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired EntityManager em;
    @Autowired PrivateRequestService service;
    @Autowired ClientOfferRequestService requestOffers;
    @Autowired PrivateRequestStageCalculator stages;
    @Autowired PrivateRequestViewBuilder views;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Tender request() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Этап КП", null, 1, null, null, null)), LinesMode.APPEND);
        return t;
    }

    @Test
    void stageFollowsBestKpStatus_closedRequestStaysClosed() {
        Tender t = request();
        ClientOffer first = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("KP_DRAFT");
        assertThat(stages.of(t).text()).isEqualTo("КП — черновик");

        first.setStatus(ClientOfferStatus.SENT);
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("KP_SENT");

        ClientOffer second = requestOffers.createFromPrivateRequest(t.getId(), "test");
        second.setStatus(ClientOfferStatus.ACCEPTED);
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("EXEC_CONTRACT");

        first.setStatus(ClientOfferStatus.REJECTED);
        second.setStatus(ClientOfferStatus.REJECTED);
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("KP_REJECTED");
        assertThat(stages.of(t).text()).isEqualTo("КП отклонено");

        t.setStatus("WON");
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("WON");
    }

    @Test
    void viewListsOffersOfTheRequest_newestFirst() {
        Tender t = request();
        ClientOffer older = requestOffers.createFromPrivateRequest(t.getId(), "test");
        ClientOffer newer = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();

        PrivateRequestViewResponse v = views.build(t);

        assertThat(v.getOffers()).extracting(r -> r.id()).containsExactly(newer.getId(), older.getId());
        assertThat(v.getOffers().get(0).status()).isEqualTo("DRAFT");
        assertThat(v.getOffers().get(0).itemCount()).isEqualTo(1);
        assertThat(v.getOffers().get(0).currency()).isEqualTo("KZT");
    }

    @Test
    @WithMockUser(roles = "USER")
    void offerAndJournalCarryRequestNumber() throws Exception {
        Tender t = request();
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();

        JsonNode one = om.readTree(mvc.perform(get("/api/client-offers/" + kp.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(one.get("tenderNumber").asText()).isEqualTo(t.getTenderNumber());

        JsonNode list = om.readTree(mvc.perform(get("/api/client-offers").param("q", String.valueOf(kp.getNumber()))
                .header("X-Market", "KZ")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        JsonNode row = null;
        for (JsonNode r : list) if (r.get("id").asLong() == kp.getId()) row = r;
        assertThat(row).isNotNull();
        assertThat(row.get("tenderNumber").asText()).isEqualTo(t.getTenderNumber());
    }
}
