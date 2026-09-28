package com.vladoose.nir.passkeys;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Полный цикл passkeys на эмуляторе ключа: регистрация в сессии, открытой паролем, → вход одним ключом
 * в новой сессии → /api/auth/me (спека passkeys-login §11). Плюс отказы: чужая подпись, удалённый ключ,
 * чужой origin, повтор того же ответа.
 */
@SpringBootTest
@Transactional
class PasskeyLoginFlowTest {

    static final String USER = "pk-flow";
    static final String PASSWORD = "flow-secret-pass";
    static final String PASSWORD_JSON = "{\"username\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}";

    @Autowired WebApplicationContext wac;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired UserCredentialRepository credentials;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        users.saveAndFlush(UserAccount.builder().username(USER).fullName("Проверка Ключей")
                .role("ROLE_ADMIN").passwordHash(encoder.encode(PASSWORD)).build());
    }

    @Test
    void registerWithPasswordSessionThenLogInWithKeyAlone() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");

        MockHttpSession fresh = new MockHttpSession();          // другой браузер: ни пароля, ни сессии
        loginWithKey(fresh, key.assertionBody(loginOptions(fresh), VirtualPasskey.ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(get("/api/auth/me").session(fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USER))
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));

        CredentialRecord stored = credentials.findByCredentialId(Bytes.fromBase64(key.credentialId()));
        assertThat(stored.getLabel()).isEqualTo("iPhone · Safari");
        assertThat(stored.getLastUsed()).as("вход ключом отмечен").isAfter(stored.getCreated());
    }

    @Test
    void foreignSignatureIsRejected() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        MockHttpSession s = new MockHttpSession();
        loginWithKey(s, key.impostor().assertionBody(loginOptions(s), VirtualPasskey.ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletedKeyIsRejected() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        credentials.delete(Bytes.fromBase64(key.credentialId()));
        MockHttpSession s = new MockHttpSession();
        loginWithKey(s, key.assertionBody(loginOptions(s), VirtualPasskey.ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void foreignOriginIsRejected() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        MockHttpSession s = new MockHttpSession();
        loginWithKey(s, key.assertionBody(loginOptions(s), "https://evil.example"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assertionCannotBeReplayed() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        MockHttpSession s = new MockHttpSession();
        String body = key.assertionBody(loginOptions(s), VirtualPasskey.ORIGIN);
        loginWithKey(s, body).andExpect(status().isOk());
        loginWithKey(s, body).andExpect(status().isUnauthorized());   // вызов одноразовый: параметры уже сняты с сессии
    }

    @Test
    void passwordLoginChangesSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON).content(PASSWORD_JSON))
                .andExpect(status().isOk());
        assertThat(session.getId()).as("id сессии после входа паролем").isNotEqualTo(before);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void keyLoginChangesSessionId() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        MockHttpSession session = new MockHttpSession();
        String options = loginOptions(session);                 // сессия открыта ещё до входа — её и подменяют
        String before = session.getId();
        loginWithKey(session, key.assertionBody(options, VirtualPasskey.ORIGIN)).andExpect(status().isOk());
        assertThat(session.getId()).as("id сессии после входа ключом").isNotEqualTo(before);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    // --- помощники (ими пользуется и Task 3) ---

    MockHttpSession loginWithPassword() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON).content(PASSWORD_JSON))
                .andExpect(status().isOk());
        return session;
    }

    VirtualPasskey registerKey(MockHttpSession session, String label) throws Exception {
        String options = mvc.perform(post("/webauthn/register/options").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        VirtualPasskey key = new VirtualPasskey();
        mvc.perform(post("/webauthn/register").session(session).contentType(APPLICATION_JSON)
                        .content(key.registrationBody(options, label, VirtualPasskey.ORIGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        return key;
    }

    String loginOptions(MockHttpSession session) throws Exception {
        return mvc.perform(post("/webauthn/authenticate/options").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    ResultActions loginWithKey(MockHttpSession session, String body) throws Exception {
        return mvc.perform(post("/login/webauthn").session(session).contentType(APPLICATION_JSON).content(body));
    }
}
