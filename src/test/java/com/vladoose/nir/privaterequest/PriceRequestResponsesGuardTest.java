package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Таблица цен пишет через PUT /api/price-requests/{id}/responses — запись только админу, строка — только своего запроса. */
@SpringBootTest
@Transactional
class PriceRequestResponsesGuardTest {

    @Autowired WebApplicationContext wac;
    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository lotRepository;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    MockMvc mvc;

    PriceRequest prA;
    PriceRequestItem itemA;
    PriceRequestItem itemB;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        Tender t = tenderRepository.save(Tender.builder().tenderNumber("ZZ-GUARD-1").status("NEW")
                .source(Source.PRIVATE_REQUEST).build());
        TenderLot lot = lotRepository.save(TenderLot.builder().tender(t).lotNumber(1).equipName("ZZ Гард").quantity(1).build());
        Distributor d = distributorRepository.save(Distributor.builder().name("ZZ Гард " + System.nanoTime()).build());
        prA = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").build());
        PriceRequest prB = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").build());
        itemA = itemRepository.save(PriceRequestItem.builder().priceRequest(prA).tenderLot(lot).requestedQuantity(1).build());
        itemB = itemRepository.save(PriceRequestItem.builder().priceRequest(prB).tenderLot(lot).requestedQuantity(1).build());
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private String body(Long itemId) {
        return "[{\"itemId\":" + itemId + ",\"responsePrice\":100,\"responseNote\":null}]";
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotWrite() throws Exception {
        mvc.perform(put("/api/price-requests/" + prA.getId() + "/responses").header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body(itemA.getId()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/price-requests/" + prA.getId() + "/close").header("X-Market", "KZ"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/price-requests/" + prA.getId()).header("X-Market", "KZ"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void itemOfAnotherRequestIs400_otherMarketIs404_ownItemIs200() throws Exception {
        mvc.perform(put("/api/price-requests/" + prA.getId() + "/responses").header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body(itemB.getId()))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/price-requests/" + prA.getId() + "/responses").header("X-Market", "RF")
                .contentType(MediaType.APPLICATION_JSON).content(body(itemA.getId()))).andExpect(status().isNotFound());
        mvc.perform(put("/api/price-requests/" + prA.getId() + "/responses").header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body(itemA.getId()))).andExpect(status().isOk());
    }
}
