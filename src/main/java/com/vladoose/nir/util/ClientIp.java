package com.vladoose.nir.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP клиента за двумя прокси (спека device-gate §7). nginx хоста ДОПИСЫВАЕТ реальный адрес к X-Forwarded-For,
 * присланному клиентом (первый адрес поэтому подделывается), фронт-контейнер дописывает свой шаг последним —
 * настоящий адрес предпоследний. IP справочный: чтобы админ узнал устройство, а не чтобы что-то решать.
 */
public final class ClientIp {

    private static final int MAX = 45;

    private ClientIp() {}

    public static String of(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        String ip;
        if (xff == null || xff.isBlank()) {
            ip = req.getRemoteAddr();
        } else {
            String[] hops = xff.split(",");
            ip = hops[hops.length >= 2 ? hops.length - 2 : 0].trim();
        }
        return ip == null || ip.length() <= MAX ? ip : ip.substring(0, MAX);
    }
}
