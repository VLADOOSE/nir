package com.vladoose.nir.passkeys;

import com.vladoose.nir.controller.AuthController;
import com.vladoose.nir.controller.PasskeyController;
import com.vladoose.nir.dto.response.PasskeyListResponse;
import com.vladoose.nir.dto.response.PasskeyResponse;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** «Мой профиль»: только свои ключи (спека passkeys-login §5.4, §6). Контроллеры — напрямую, как в проекте. */
@SpringBootTest
@Transactional
class PasskeyControllerTest {

    static final Instant T = Instant.parse("2026-09-01T10:00:00Z");
    static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

    @Autowired PasskeyController controller;
    @Autowired AuthController auth;
    @Autowired UserAccountRepository users;
    @Autowired PublicKeyCredentialUserEntityRepository entities;
    @Autowired UserCredentialRepository credentials;
    @Autowired WebApplicationContext wac;

    PublicKeyCredentialUserEntity owner;
    PublicKeyCredentialUserEntity other;

    @BeforeEach
    void setUp() {
        PasskeyFixtures.user(users, "pk-owner");
        PasskeyFixtures.user(users, "pk-other");
        owner = PasskeyFixtures.owner(entities, "pk-owner");
        other = PasskeyFixtures.owner(entities, "pk-other");
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void listsOnlyOwnKeysOldestFirstWithSuggestedLabel() {
        PasskeyFixtures.key(credentials, owner, "Mac · Chrome", T.plusSeconds(60), T.plusSeconds(60));
        PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);
        PasskeyFixtures.key(credentials, other, "Чужой", T, T);

        PasskeyListResponse r = controller.list(IPHONE);

        assertThat(r.getKeys()).extracting(PasskeyResponse::getLabel).containsExactly("iPhone · Safari", "Mac · Chrome");
        assertThat(r.getSuggestedLabel()).isEqualTo("iPhone · Safari");
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void keyThatNeverLoggedInHasNoLastUse() {
        PasskeyFixtures.key(credentials, owner, "новый", T, T);                      // так Spring сохраняет новый ключ
        PasskeyFixtures.key(credentials, owner, "рабочий", T, T.plusSeconds(3600));

        List<PasskeyResponse> keys = controller.list(null).getKeys();

        assertThat(keys).filteredOn(k -> k.getLabel().equals("новый")).singleElement()
                .extracting(PasskeyResponse::getLastUsedAt).isNull();
        assertThat(keys).filteredOn(k -> k.getLabel().equals("рабочий")).singleElement()
                .extracting(PasskeyResponse::getLastUsedAt).isEqualTo(T.plusSeconds(3600));
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void deletesOwnKey() {
        Bytes mine = PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);
        controller.delete(mine.toBase64UrlString());
        assertThat(credentials.findByCredentialId(mine)).isNull();
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void foreignKeyLooksMissingAndStays() {
        Bytes theirs = PasskeyFixtures.key(credentials, other, "Чужой", T, T);
        assertThatThrownBy(() -> controller.delete(theirs.toBase64UrlString())).isInstanceOf(NotFoundException.class);
        assertThat(credentials.findByCredentialId(theirs)).as("чужой ключ на месте").isNotNull();
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void unknownOrGarbageIdIsNotFound() {
        assertThatThrownBy(() -> controller.delete(Bytes.random().toBase64UrlString())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.delete("не base64!")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void passkeyConfigNamesTheDomain() {
        assertThat(auth.passkeyConfig().getRpId()).isEqualTo("localhost");
    }

    @Test
    void httpRules() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        mvc.perform(get("/api/passkeys")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/passkeys/abc")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/passkey-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpId").value("localhost"));
    }
}
