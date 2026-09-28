package com.vladoose.nir.passkeys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Включение passkeys и правила доступа к встроенным эндпоинтам Spring (спека passkeys-login §5.1–5.3).
 * MockMvc — из общего контекста: @AutoConfigureMockMvc поднял бы лишний контекст со своим пулом (CLAUDE.md §14).
 */
@SpringBootTest
@Transactional
class PasskeySetupTest {

    static final String USER = "pk-setup";
    static final Instant T = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired WebApplicationContext wac;
    @Autowired UserAccountRepository users;
    @Autowired PublicKeyCredentialUserEntityRepository entities;
    @Autowired UserCredentialRepository credentials;
    @Autowired ServerProperties server;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        PasskeyFixtures.user(users, USER);
    }

    @Test
    void loggedInUserGetsRegistrationOptionsForThisDomain() throws Exception {
        String json = mvc.perform(post("/webauthn/register/options").with(user(USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode o = new ObjectMapper().readTree(json);

        assertThat(o.at("/rp/id").asText()).isEqualTo("localhost");
        assertThat(o.at("/rp/name").asText()).isEqualTo("АИС Медзакупки");
        assertThat(o.at("/user/name").asText()).isEqualTo(USER);
        assertThat(o.at("/authenticatorSelection/residentKey").asText()).isEqualTo("required");
        assertThat(o.at("/challenge").asText()).isNotBlank();
        assertThat(entities.findByUsername(USER))
                .as("запись пользователя WebAuthn создаётся при первых параметрах регистрации").isNotNull();
    }

    @Test
    void registrationNeedsLogin() throws Exception {
        // параметры: фильтр Spring стоит до проверки прав и сам отвечает анониму 400, а не 401 (спека §1.1 п. 7)
        mvc.perform(post("/webauthn/register/options")).andExpect(status().isBadRequest());
        // сама регистрация стоит после проверки прав — её закрывает правило authenticated()
        mvc.perform(post("/webauthn/register").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginOptionsAreOpenToAnonymous() throws Exception {
        mvc.perform(post("/webauthn/authenticate/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpId").value("localhost"))
                .andExpect(jsonPath("$.challenge").isNotEmpty());
    }

    @Test
    void builtInDeleteIsClosedForEveryone() throws Exception {
        PublicKeyCredentialUserEntity owner = PasskeyFixtures.owner(entities, USER);
        Bytes key = PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);

        mvc.perform(delete("/webauthn/register/" + key.toBase64UrlString()))
                .andExpect(status().isUnauthorized());                       // аноним получает точку входа
        mvc.perform(delete("/webauthn/register/" + key.toBase64UrlString()).with(user(USER)))
                .andExpect(status().isForbidden());                          // даже владельцу — только через /api/passkeys
        assertThat(credentials.findByCredentialId(key)).as("ключ на месте").isNotNull();
    }

    @Test
    void defaultRegistrationPageIsOff() throws Exception {
        mvc.perform(get("/webauthn/register").with(user(USER)))
                .andExpect(status().is(not(200)))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void sessionLastsAWorkingDay() {
        assertThat(server.getServlet().getSession().getTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(server.getServlet().getSession().getCookie().getMaxAge()).isEqualTo(Duration.ofHours(12));
    }
}
