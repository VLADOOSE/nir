package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Ключ входа (passkey) в «Моём профиле» (спека passkeys-login §6). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasskeyResponse {
    private String id;               // id ключа, base64url — для удаления
    private String label;            // «iPhone · Safari»
    private Instant createdAt;
    private Instant lastUsedAt;      // null — ключом ещё не входили
}
