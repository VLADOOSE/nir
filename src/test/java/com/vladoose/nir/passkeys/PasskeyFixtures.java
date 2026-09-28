package com.vladoose.nir.passkeys;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import java.time.Instant;
import java.util.Set;

/**
 * Учётки и ключи прямо в таблицах — для тестов, которым не нужна церемония WebAuthn
 * (саму церемонию проверяет PasskeyLoginFlowTest на эмуляторе ключа).
 */
final class PasskeyFixtures {

    private PasskeyFixtures() {}

    /** Учётка АИС: запись пользователя WebAuthn ссылается на её логин внешним ключом (V20). Пароль тесту не нужен. */
    static UserAccount user(UserAccountRepository users, String username) {
        return users.saveAndFlush(UserAccount.builder()
                .username(username).fullName(username).role("ROLE_USER").passwordHash("-").build());
    }

    static PublicKeyCredentialUserEntity owner(PublicKeyCredentialUserEntityRepository entities, String username) {
        PublicKeyCredentialUserEntity e = ImmutablePublicKeyCredentialUserEntity.builder()
                .name(username).id(Bytes.random()).displayName(username).build();
        entities.save(e);
        return e;
    }

    /** Ключ владельца. created == lastUsed — так Spring сохраняет только что зарегистрированный ключ. */
    static Bytes key(UserCredentialRepository credentials, PublicKeyCredentialUserEntity owner,
                     String label, Instant created, Instant lastUsed) {
        Bytes id = Bytes.random();
        credentials.save(ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(id)
                .userEntityUserId(owner.getId())
                .publicKey(new ImmutablePublicKeyCose(new byte[] {1, 2, 3}))
                .signatureCount(0)
                .uvInitialized(true)
                .transports(Set.of())
                .backupEligible(false)
                .backupState(false)
                .created(created)
                .lastUsed(lastUsed)
                .label(label)
                .build());
        return id;
    }
}
