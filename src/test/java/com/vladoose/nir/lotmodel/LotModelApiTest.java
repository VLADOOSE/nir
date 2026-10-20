package com.vladoose.nir.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API «модели в работе» (спека §5.5, §6): права, рынок, ввод, сквозной путь. */
@SpringBootTest
@Transactional
class LotModelApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired MedRegistryRepository registry;
    MockMvc mvc;
    TenderLot lot;
    SiteProduct dki;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        lot = LotModelFixtures.lot(tenders, Market.KZ, "ZZ Дефибриллятор");
        // карточка РУ уже открыта — закрепление не ходит в ndda.kz
        registry.saveAndFlush(MedRegistry.builder().regNumber("ZZ-РУ-API-1").name("ZZ Дефибриллятор D3").producer("ZZ")
                .unlimited(true).detailFetchedAt(OffsetDateTime.now()).build());
        dki = products.saveAndFlush(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID()).name("ZZ Дефибриллятор ДКИ-Н-12")
                .brand("Аксион").slug("zz-dki").syncedAt(OffsetDateTime.now()).build());
    }

    @AfterEach
    void clear() { MarketContext.clear(); }

    private static MockHttpServletRequestBuilder in(Market market, MockHttpServletRequestBuilder b) {
        return b.header("X-Market", market.name());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void pinSiteProduct_thenLotsTenderFitAndClear() throws Exception {
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"),
                        "{\"siteProductId\":" + dki.getId() + ",\"note\":\"ИИ: подходит\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedEquipment.source").value("SITE"))
                .andExpect(jsonPath("$.proposedEquipment.productUrl").value(endsWith("zz-dki")))
                .andExpect(jsonPath("$.modelNote").value("ИИ: подходит"))
                .andExpect(jsonPath("$.modelSetBy").value("vlad"));
        mvc.perform(in(Market.KZ, get("/api/tenders/" + lot.getTender().getId() + "/lots")))
                .andExpect(jsonPath("$[0].proposedEquipment.siteProductId").value(dki.getId()))
                .andExpect(jsonPath("$[0].proposedEquipment.productUrl").value(endsWith("zz-dki")));
        mvc.perform(in(Market.KZ, get("/api/tenders/" + lot.getTender().getId())))
                .andExpect(jsonPath("$.modelsInWork[0]").value("ZZ Дефибриллятор ДКИ-Н-12"));
        mvc.perform(in(Market.KZ, get("/api/lots/" + lot.getId() + "/model-fit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.source").value("SITE"))
                .andExpect(jsonPath("$.note").value("ИИ: подходит"));
        mvc.perform(in(Market.KZ, json(put("/api/lots/" + lot.getId() + "/model-note"), "{\"note\":\"новая\"}")))
                .andExpect(jsonPath("$.modelNote").value("новая"));
        mvc.perform(in(Market.KZ, delete("/api/lots/" + lot.getId() + "/model")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedEquipment").doesNotExist());
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void pinRegistry_byNumber() throws Exception {
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"), "{\"regNumber\":\" ZZ-РУ-API-1 \"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedEquipment.source").value("REGISTRY"))
                .andExpect(jsonPath("$.proposedEquipment.regNumber").value("ZZ-РУ-API-1"))
                .andExpect(jsonPath("$.proposedEquipment.regActive").value(true))
                .andExpect(jsonPath("$.proposedEquipment.regStatus").value("ACTIVE"));   // шапка лота: «РУ ✓ / истекло / не найдено»
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operator_readsButCannotWrite() throws Exception {
        mvc.perform(in(Market.KZ, get("/api/lots/" + lot.getId() + "/model-search").param("q", "дки"))).andExpect(status().isOk());
        mvc.perform(in(Market.KZ, get("/api/lots/" + lot.getId() + "/model-fit"))).andExpect(status().isOk());
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"), "{\"regNumber\":\"ZZ-РУ-API-1\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(in(Market.KZ, json(put("/api/lots/" + lot.getId() + "/model-note"), "{\"note\":\"x\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(in(Market.KZ, delete("/api/lots/" + lot.getId() + "/model"))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void badInputAndOtherMarket() throws Exception {
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"),
                "{\"siteProductId\":" + dki.getId() + ",\"regNumber\":\"ZZ-РУ-API-1\"}"))).andExpect(status().isBadRequest());
        mvc.perform(in(Market.KZ, json(post("/api/lots/" + lot.getId() + "/model"), "{}"))).andExpect(status().isBadRequest());
        mvc.perform(in(Market.KZ, json(put("/api/lots/" + lot.getId() + "/model-note"), "{\"note\":\"x\"}")))
                .andExpect(status().isBadRequest());                                  // модели нет
        mvc.perform(in(Market.RF, get("/api/lots/" + lot.getId() + "/model-fit"))).andExpect(status().isNotFound());
        mvc.perform(in(Market.RF, get("/api/lots/" + lot.getId() + "/model-search").param("q", "дки"))).andExpect(status().isNotFound());
        mvc.perform(in(Market.RF, json(post("/api/lots/" + lot.getId() + "/model"), "{\"siteProductId\":" + dki.getId() + "}")))
                .andExpect(status().isNotFound());
        mvc.perform(in(Market.RF, delete("/api/lots/" + lot.getId() + "/model"))).andExpect(status().isNotFound());
    }
}
