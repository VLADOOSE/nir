package com.vladoose.nir.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Ключ устройства калитки, его хеш и короткий код запроса (спека device-gate §6). */
public final class DeviceTokens {

    /** Без 0/O/1/I/L — не путаются ни на слух, ни на экране. */
    public static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    public static final int CODE_LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceTokens() {}

    /** 32 случайных байта в base64url без выравнивания — 43 символа. */
    public static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** SHA-256 в hex (64 символа) — только он и хранится в БД. */
    public static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    public static String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }

    /** «7K4QM2» → «7K4-QM2». */
    public static String display(String code) {
        return code == null || code.length() != CODE_LENGTH ? code : code.substring(0, 3) + "-" + code.substring(3);
    }
}
