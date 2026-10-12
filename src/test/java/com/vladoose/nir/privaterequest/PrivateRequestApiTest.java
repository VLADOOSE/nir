package com.vladoose.nir.privaterequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-уровень: права (запись — только админ) и гард рынка по заголовку X-Market. */
@SpringBootTest
@Transactional
class PrivateRequestApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static MockHttpServletRequestBuilder kz(MockHttpServletRequestBuilder b) {
        return b.header("X-Market", "KZ");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorReadsButCannotWrite() throws Exception {
        mvc.perform(kz(get("/api/private-requests"))).andExpect(status().isOk());
        mvc.perform(kz(post("/api/private-requests"))).andExpect(status().isForbidden());
        mvc.perform(kz(patch("/api/private-requests/1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"NOTE\",\"value\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(kz(post("/api/private-requests/1/close")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"WON\"}")).andExpect(status().isForbidden());
        mvc.perform(kz(post("/api/private-requests/1/save-client"))).andExpect(status().isForbidden());
        mvc.perform(kz(delete("/api/private-requests/1"))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCreatesAndPatches_otherMarketIs404() throws Exception {
        String body = mvc.perform(kz(post("/api/private-requests")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = om.readTree(body).get("id").asLong();

        mvc.perform(kz(patch("/api/private-requests/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"CONTACT_NAME\",\"value\":\"Айгуль\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactName").value("Айгуль"))
                .andExpect(jsonPath("$.clientLabel").value("Айгуль"));
        mvc.perform(kz(patch("/api/private-requests/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"без поля\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/private-requests/" + id).header("X-Market", "RF")).andExpect(status().isNotFound());
    }
}
