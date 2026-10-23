package com.vladoose.nir.rates;

import com.vladoose.nir.repository.CurrencyRateRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class CurrencyRateApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired CurrencyRateRepository ours;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        ours.deleteAllInBatch();
    }

    @Test
    @WithMockUser(username = "operator", roles = "USER")
    void operator_readsButCannotWrite() throws Exception {
        mvc.perform(get("/api/currency-rates").header("X-Market", "KZ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].currency").value("RUB"));
        mvc.perform(put("/api/currency-rates").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rates\":[{\"currency\":\"RUB\",\"ourRate\":6}]}")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void admin_saves_marketsSeparate() throws Exception {
        mvc.perform(put("/api/currency-rates").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rates\":[{\"currency\":\"RUB\",\"ourRate\":6}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].ourRate").value(6.0))
                .andExpect(jsonPath("$.rates[0].updatedBy").value("vlad"));
        mvc.perform(get("/api/currency-rates").header("X-Market", "RF"))
                .andExpect(jsonPath("$.rates[0].currency").value("USD"))
                .andExpect(jsonPath("$.rates[0].ourRate").doesNotExist());
        mvc.perform(put("/api/currency-rates").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rates\":[{\"currency\":\"RUB\",\"ourRate\":-1}]}")).andExpect(status().isBadRequest());
    }
}
