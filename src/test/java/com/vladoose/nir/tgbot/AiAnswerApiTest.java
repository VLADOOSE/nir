package com.vladoose.nir.tgbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.TenderLot;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** «✍️ Вставить ответ ИИ» (спека 2026-10-21-tender-telegram-bot §10 п. 3): только администратор, вывод ИИ — у лота. */
@SpringBootTest
@Transactional
class AiAnswerApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired TenderRepository tenders;
    MockMvc mvc;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
        lot = TgFixtures.lot(tenders, "ZZ Облучатель", "ZZ Поликлиника №2");
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private MockHttpServletRequestBuilder answer(String text) {
        return post("/api/tenders/ai-answer").header("X-Market", "KZ").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + new ObjectMapper().valueToTree(text) + "}");
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void adminAppliesAnswer_lotShowsVerdict() throws Exception {
        mvc.perform(answer("L" + lot.getId() + " | не наше | — | нужен передвижной"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lots[0].outcome").value("NOT_OURS"));
        MarketContext.set(Market.KZ);   // запрос MockMvc чистит MarketContext потока (CLAUDE.md §14)
        mvc.perform(get("/api/tenders/" + lot.getTender().getId() + "/lots").header("X-Market", "KZ"))
                .andExpect(jsonPath("$[0].aiVerdict").value("NO"))
                .andExpect(jsonPath("$[0].aiNote").value("нужен передвижной"))
                .andExpect(jsonPath("$[0].aiBy").value("vlad"));
    }

    /** Фильтр «🔁 Аналог (по ИИ)» в «Тендерах» — признак тендера aiAnalog. */
    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void analog_tenderFlagForFilter() throws Exception {
        mvc.perform(get("/api/tenders/" + lot.getTender().getId()).header("X-Market", "KZ"))
                .andExpect(jsonPath("$.aiAnalog").value(false));
        MarketContext.set(Market.KZ);
        mvc.perform(answer("L" + lot.getId() + " | аналог | — | CUSA Clarity, Integra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lots[0].outcome").value("ANALOG"));
        MarketContext.set(Market.KZ);   // запрос MockMvc чистит MarketContext потока (CLAUDE.md §14)
        mvc.perform(get("/api/tenders/" + lot.getTender().getId()).header("X-Market", "KZ"))
                .andExpect(jsonPath("$.aiAnalog").value(true));
        MarketContext.set(Market.KZ);
        mvc.perform(get("/api/tenders/" + lot.getTender().getId() + "/lots").header("X-Market", "KZ"))
                .andExpect(jsonPath("$[0].aiVerdict").value("ANALOG"));
    }

    @Test
    @WithMockUser(username = "op", roles = "USER")
    void operator_403() throws Exception {
        mvc.perform(answer("L" + lot.getId() + " | не наше | — | x")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void blank_400() throws Exception {
        mvc.perform(answer("  ")).andExpect(status().isBadRequest());
    }
}
