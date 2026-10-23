package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.PriceList;
import com.vladoose.nir.entity.PriceListStatus;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceListRepository;
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

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
@WithMockUser(username = "op", roles = "OPERATOR")
class PriceHintsApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired DistributorRepository distributors;
    @Autowired PriceListRepository lists;
    MockMvc mvc;
    PriceList list;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "API подсказки");
        list = PriceListFixtures.list(lists, d, PriceListStatus.ACTIVE, "KZT", LocalDate.of(2026, 9, 1),
                new Object[]{"Отсасыватель ОМП-9/94", "60100", "омп994"});
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void operatorReads_searchHintsHistory_otherMarket404() throws Exception {
        mvc.perform(get("/api/price-lists/search").param("q", "омп-9/94").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].price").value(60100.0));
        mvc.perform(post("/api/price-lists/hints").header("X-Market", "KZ").contentType("application/json")
                        .content("{\"texts\":[\"Отсасыватель портативный ОМП-9/94\",\"без кода\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rows[0].supplierName").exists())
                .andExpect(jsonPath("$[1].rows.length()").value(0));
        Long rowId = list.getItems().get(0).getId();
        mvc.perform(get("/api/price-lists/rows/" + rowId + "/history").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].kind").value("LIST"));
        mvc.perform(get("/api/price-lists/rows/" + rowId + "/history").header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(post("/api/price-lists/hints").header("X-Market", "KZ").contentType("application/json")
                        .content("{\"texts\":[" + "\"x\",".repeat(200) + "\"x\"]}"))
                .andExpect(status().isBadRequest());
    }
}
