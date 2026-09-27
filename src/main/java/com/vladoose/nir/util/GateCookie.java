package com.vladoose.nir.util;

import org.springframework.http.ResponseCookie;

import java.time.Duration;

/** Cookie ключа устройства калитки (спека device-gate §6). */
public final class GateCookie {

    public static final String NAME = "ais_device";
    /** Предел Chrome; сам срок допуска решает сервер — 90 дней без визитов. */
    public static final Duration MAX_AGE = Duration.ofDays(400);

    private GateCookie() {}

    public static ResponseCookie of(String token) {
        return ResponseCookie.from(NAME, token)
                .path("/").httpOnly(true).secure(true).sameSite("Lax").maxAge(MAX_AGE)
                .build();
    }
}
