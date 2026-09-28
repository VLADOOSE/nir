package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Домен ключей входа: фронт показывает кнопку «Войти с Face ID» только на нём (спека passkeys-login §6). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasskeyConfigResponse {
    private String rpId;
}
