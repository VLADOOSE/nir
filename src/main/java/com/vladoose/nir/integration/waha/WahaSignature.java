package com.vladoose.nir.integration.waha;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Подпись вебхука WAHA: hex(HMAC-SHA512(сырое тело, ключ)), заголовки не подписываются (спека whatsapp-waha §2).
 * Сравнение — за постоянное время. Пустой ключ не принимает ничего: неподписанных событий не бывает.
 */
public final class WahaSignature {

    private static final String ALGORITHM = "HmacSHA512";

    private WahaSignature() {}

    public static String sign(byte[] body, String key) {
        return HexFormat.of().formatHex(mac(body, key));
    }

    public static boolean verify(byte[] body, String key, String header) {
        if (body == null || key == null || key.isEmpty() || header == null || header.isBlank()) return false;
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(header.strip().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(mac(body, key), actual);
    }

    private static byte[] mac(byte[] body, String key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA512 недоступен в JDK", e);
        }
    }
}
