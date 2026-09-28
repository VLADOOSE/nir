package com.vladoose.nir.passkeys;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ключи привязаны к учётке каскадом по логину (V20, спека passkeys-login §4). Spring находит владельца ключа
 * по ЛОГИНУ, а логин в «Пользователях» редактируется: без каскада ключи удалённой учётки достались бы
 * новой учётке с тем же логином.
 */
@SpringBootTest
@Transactional
class PasskeyAccountLinkTest {

    static final Instant T = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired UserAccountRepository users;
    @Autowired PublicKeyCredentialUserEntityRepository entities;
    @Autowired UserCredentialRepository credentials;

    @Test
    void renamedAccountKeepsItsKeys() {
        UserAccount account = PasskeyFixtures.user(users, "pk-link-old");
        PublicKeyCredentialUserEntity owner = PasskeyFixtures.owner(entities, "pk-link-old");
        Bytes key = PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);

        account.setUsername("pk-link-new");
        users.saveAndFlush(account);

        assertThat(entities.findByUsername("pk-link-old")).isNull();
        assertThat(entities.findByUsername("pk-link-new").getId()).isEqualTo(owner.getId());
        assertThat(credentials.findByCredentialId(key)).isNotNull();
    }

    @Test
    void deletedAccountTakesItsKeysAndNewAccountWithSameLoginGetsNone() {
        UserAccount account = PasskeyFixtures.user(users, "pk-link-gone");
        PublicKeyCredentialUserEntity owner = PasskeyFixtures.owner(entities, "pk-link-gone");
        Bytes key = PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);

        users.delete(account);
        users.flush();
        assertThat(credentials.findByCredentialId(key)).isNull();
        assertThat(entities.findById(owner.getId())).isNull();

        PasskeyFixtures.user(users, "pk-link-gone");
        assertThat(entities.findByUsername("pk-link-gone")).as("новая учётка чужих ключей не получает").isNull();
    }

    @Test
    void webAuthnUserNeedsExistingAccount() {
        assertThatThrownBy(() -> PasskeyFixtures.owner(entities, "pk-link-nobody"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneWebAuthnUserPerLogin() {
        PasskeyFixtures.user(users, "pk-link-twice");
        PasskeyFixtures.owner(entities, "pk-link-twice");
        assertThatThrownBy(() -> PasskeyFixtures.owner(entities, "pk-link-twice"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
