package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Telegram ID учётки (спека 2026-10-21-tender-telegram-bot §8, §10 п. 4): цифры, уникально, пусто — снять. */
@SpringBootTest
@Transactional
@WithMockUser(username = "admin", roles = "ADMIN")
class UserTelegramIdApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired UserAccountRepository users;
    MockMvc mvc;
    UserAccount a, b;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        a = TgFixtures.user(users, "zza", "ROLE_ADMIN", null);
        b = TgFixtures.user(users, "zzb", "ROLE_USER", null);
    }

    private String body(UserAccount u, String telegram) {
        return "{\"username\":\"" + u.getUsername() + "\",\"role\":\"" + u.getRole() + "\""
                + (telegram == null ? "" : ",\"telegramUserId\":\"" + telegram + "\"") + "}";
    }

    @Test
    void setUnique_rejectGarbageAndDuplicate_clearWithEmpty_keepWhenAbsent() throws Exception {
        mvc.perform(put("/api/users/" + a.getId()).contentType(MediaType.APPLICATION_JSON).content(body(a, " 123456789 ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.telegramUserId").value(123456789L));
        mvc.perform(put("/api/users/" + b.getId()).contentType(MediaType.APPLICATION_JSON).content(body(b, "123456789")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("уже у пользователя")));
        mvc.perform(put("/api/users/" + b.getId()).contentType(MediaType.APPLICATION_JSON).content(body(b, "12ab")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("только цифры")));
        mvc.perform(put("/api/users/" + a.getId()).contentType(MediaType.APPLICATION_JSON).content(body(a, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.telegramUserId").value(123456789L));   // поля нет — не трогаем
        mvc.perform(put("/api/users/" + a.getId()).contentType(MediaType.APPLICATION_JSON).content(body(a, "123456789")))
                .andExpect(status().isOk());   // тот же ID той же учётке — не дубль
        mvc.perform(put("/api/users/" + a.getId()).contentType(MediaType.APPLICATION_JSON).content(body(a, "")))
                .andExpect(status().isOk());
        assertThat(users.findById(a.getId()).orElseThrow().getTelegramUserId()).isNull();
    }
}
