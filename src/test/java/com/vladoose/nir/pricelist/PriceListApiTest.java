package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.service.pricelist.PriceListService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class PriceListApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired DistributorRepository distributors;
    @Autowired PriceListService service;
    MockMvc mvc;
    Distributor axion;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        axion = PriceListFixtures.supplier(distributors, Market.KZ, "Аксион API");
        MarketContext.clear();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void upload_view_file_otherMarket404() throws Exception {
        String body = mvc.perform(multipart("/api/price-lists").file(new MockMultipartFile("file", "ZZ прайс.xlsx",
                                "application/octet-stream", PriceListFiles.axionLikeXlsx()))
                        .param("distributorId", String.valueOf(axion.getId())).header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
        mvc.perform(get("/api/price-lists").header("X-Market", "KZ")).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/price-lists/" + id + "/file").header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/price-lists/" + id).header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(post("/api/price-lists/" + id + "/activate").header("X-Market", "KZ")).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(username = "op", roles = "OPERATOR")
    void operator_readsButCannotUpload() throws Exception {
        MarketContext.set(Market.KZ);
        Long id = service.upload(axion.getId(), "ZZ прайс.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        MarketContext.clear();
        mvc.perform(get("/api/price-lists/" + id).header("X-Market", "KZ")).andExpect(status().isOk());
        mvc.perform(multipart("/api/price-lists").file(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", new byte[]{1}))
                .param("distributorId", String.valueOf(axion.getId())).header("X-Market", "KZ")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/price-lists/" + id).header("X-Market", "KZ").contentType("application/json")
                .content("{\"field\":\"TERMS\",\"value\":\"x\"}")).andExpect(status().isForbidden());
    }
}
