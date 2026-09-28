package com.vladoose.nir.chat;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Права «Система → WhatsApp» через настоящую цепочку фильтров; MockMvc — из общего контекста (CLAUDE.md §14). */
@SpringBootTest
@Transactional
class WhatsappSessionSecurityTest {

    @Autowired WebApplicationContext wac;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/whatsapp/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/whatsapp/session/qr")).andExpect(status().isUnauthorized());
    }

    /** QR — это доступ к рабочему WhatsApp: оператор не видит и не трогает. */
    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotSeeOrManageTheNumber() throws Exception {
        mvc.perform(get("/api/whatsapp/session")).andExpect(status().isForbidden());
        mvc.perform(get("/api/whatsapp/session/qr")).andExpect(status().isForbidden());
        mvc.perform(post("/api/whatsapp/session/restart")).andExpect(status().isForbidden());
        mvc.perform(post("/api/whatsapp/session/logout")).andExpect(status().isForbidden());
    }

    /** В тестах приём выключен: страница честно говорит «выключено», в WAHA не ходит, действия — 409. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminSeesDisabledIntake() throws Exception {
        mvc.perform(get("/api/whatsapp/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("waha"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.qrAvailable").value(false));
        mvc.perform(post("/api/whatsapp/session/restart")).andExpect(status().isConflict());
        mvc.perform(get("/api/chats/status")).andExpect(jsonPath("$.provider").value("waha"));
    }
}
