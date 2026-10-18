package com.vladoose.nir.tender;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Гард рынка и источника у путей по id `/api/tenders/{id}` (ревью «Переходов между страницами», 2026-10-18).
 * `findById` — это `em.find`, фильтр рынка его не режет (§14 CLAUDE.md): открытая карточка живёт в адресе
 * (`/tenders?openId=`), смена рынка перезагружает тот же адрес — и на РФ открывался KZ-тендер. Частная заявка
 * формой тендера не открывается, не правится и не удаляется — у неё свои пути.
 */
@SpringBootTest
@Transactional
class TenderMarketGuardApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired TenderRepository tenders;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender tender(Market market, Source source, String number) {
        return tenders.saveAndFlush(Tender.builder().tenderNumber(number).status("ACTIVE").source(source)
                .market(market).currency(market.currencyCode()).build());
    }

    private static MockHttpServletRequestBuilder in(Market market, MockHttpServletRequestBuilder b) {
        return b.header("X-Market", market.name());
    }

    private static String body(String number) {
        return "{\"tenderNumber\":\"" + number + "\",\"status\":\"ACTIVE\",\"purchaseType\":\"ELECTRONIC_AUCTION\","
                + "\"deadline\":\"" + LocalDate.now().plusDays(10) + "\"}";
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void cardOfOtherMarketIsNotFound() throws Exception {
        Tender kz = tender(Market.KZ, Source.PUBLIC_TENDER, "ZZ-ГАРД-KZ");
        Tender rf = tender(Market.RF, Source.PUBLIC_TENDER, "ZZ-ГАРД-RF");

        mvc.perform(in(Market.KZ, get("/api/tenders/" + kz.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenderNumber").value("ZZ-ГАРД-KZ"));
        mvc.perform(in(Market.RF, get("/api/tenders/" + kz.getId()))).andExpect(status().isNotFound());
        mvc.perform(in(Market.KZ, get("/api/tenders/" + rf.getId()))).andExpect(status().isNotFound());
        // лоты чужого тендера — тоже нет (у лота своего фильтра рынка нет: он «через тендер»)
        mvc.perform(in(Market.RF, get("/api/tenders/" + kz.getId() + "/lots"))).andExpect(status().isNotFound());
        mvc.perform(in(Market.KZ, get("/api/tenders/" + kz.getId() + "/lots"))).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void otherMarketIsNeitherEditedNorDeleted() throws Exception {
        Tender kz = tender(Market.KZ, Source.PUBLIC_TENDER, "ZZ-ГАРД-KZ");

        mvc.perform(in(Market.RF, put("/api/tenders/" + kz.getId())).contentType(MediaType.APPLICATION_JSON)
                .content(body("ZZ-ПОДМЕНА"))).andExpect(status().isNotFound());
        mvc.perform(in(Market.RF, delete("/api/tenders/" + kz.getId()))).andExpect(status().isNotFound());
        assertThat(tenders.findById(kz.getId())).get().extracting(Tender::getTenderNumber).isEqualTo("ZZ-ГАРД-KZ");

        // свой рынок — как раньше
        mvc.perform(in(Market.KZ, put("/api/tenders/" + kz.getId())).contentType(MediaType.APPLICATION_JSON)
                .content(body("ZZ-ГАРД-KZ-2"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenderNumber").value("ZZ-ГАРД-KZ-2"));
        mvc.perform(in(Market.KZ, delete("/api/tenders/" + kz.getId()))).andExpect(status().isOk());
        assertThat(tenders.findById(kz.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void privateRequestIsNotATenderCard() throws Exception {
        Tender pr = tender(Market.KZ, Source.PRIVATE_REQUEST, "ZZ-ЧЗ-ГАРД");

        mvc.perform(in(Market.KZ, get("/api/tenders/" + pr.getId()))).andExpect(status().isNotFound());
        mvc.perform(in(Market.KZ, put("/api/tenders/" + pr.getId())).contentType(MediaType.APPLICATION_JSON)
                .content(body("ZZ-ПОДМЕНА"))).andExpect(status().isNotFound());
        mvc.perform(in(Market.KZ, delete("/api/tenders/" + pr.getId()))).andExpect(status().isNotFound());
        assertThat(tenders.findById(pr.getId())).get().extracting(Tender::getTenderNumber).isEqualTo("ZZ-ЧЗ-ГАРД");
    }
}
