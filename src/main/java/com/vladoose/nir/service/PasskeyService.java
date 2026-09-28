package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PasskeyResponse;
import com.vladoose.nir.exception.NotFoundException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Ключи входа (passkeys) пользователя — посмотреть и удалить свои (спека passkeys-login §5.4). Регистрацию и вход
 * ведёт Spring Security (http.webAuthn); здесь — то, чего у него нет: встроенное удаление Spring не проверяет
 * владельца и закрыто (SecurityConfig).
 */
@Service
public class PasskeyService {

    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final UserCredentialRepository credentials;

    public PasskeyService(PublicKeyCredentialUserEntityRepository userEntities, UserCredentialRepository credentials) {
        this.userEntities = userEntities;
        this.credentials = credentials;
    }

    public List<PasskeyResponse> list(String username) {
        PublicKeyCredentialUserEntity owner = userEntities.findByUsername(username);
        if (owner == null) return List.of();
        return credentials.findByUserId(owner.getId()).stream()
                .sorted(Comparator.comparing(CredentialRecord::getCreated, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(PasskeyService::toResponse)
                .toList();
    }

    /**
     * Удаляет свой ключ. Нет такого, чужой или битый id — одинаково «не найден»: по ответу нельзя узнать,
     * существует ли чужой ключ.
     */
    @Transactional
    public void delete(String username, String credentialId) {
        CredentialRecord key = find(credentialId);
        PublicKeyCredentialUserEntity owner = userEntities.findByUsername(username);
        if (key == null || owner == null || !owner.getId().equals(key.getUserEntityUserId())) {
            throw new NotFoundException("Ключ не найден");
        }
        credentials.delete(key.getCredentialId());
    }

    private CredentialRecord find(String credentialId) {
        try {
            return credentials.findByCredentialId(Bytes.fromBase64(credentialId));
        } catch (IllegalArgumentException notBase64Url) {
            return null;
        }
    }

    private static PasskeyResponse toResponse(CredentialRecord c) {
        // Spring при регистрации ставит last_used = created: такой ключ ещё не входил
        Instant lastUsed = c.getLastUsed() == null || c.getLastUsed().equals(c.getCreated()) ? null : c.getLastUsed();
        return new PasskeyResponse(c.getCredentialId().toBase64UrlString(), c.getLabel(), c.getCreated(), lastUsed);
    }
}
