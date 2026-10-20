package com.vladoose.nir.service.productwatch.fit;

import java.util.Locale;

/** № РУ сайта → вид для запроса в ndda.kz и ключа `product_registration`. */
public final class RegNumbers {
    private RegNumbers() {}

    public static String normalize(String s) {
        if (s == null) return null;
        String t = s.replace(' ', ' ').replaceAll("\\s+", " ").strip();
        return t.isEmpty() ? null : t.toUpperCase(Locale.ROOT);
    }
}
