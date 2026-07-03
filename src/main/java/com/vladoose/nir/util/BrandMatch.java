package com.vladoose.nir.util;

import java.util.List;

/** Матч бренда поставщика: строка (бренд строки/производитель) содержит бренд (case-insensitive, contains). */
public final class BrandMatch {

    private BrandMatch() {}

    /** @return первый бренд из brands, который содержится в haystack, или null. */
    public static String firstCarried(List<String> brands, String haystack) {
        if (haystack == null || haystack.isBlank() || brands == null) return null;
        String h = haystack.toLowerCase();
        for (String b : brands) {
            if (b != null && !b.isBlank() && h.contains(b.trim().toLowerCase())) return b;
        }
        return null;
    }
}
