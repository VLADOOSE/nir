package com.vladoose.nir.util;

/** Телефон зоны +7 (KZ/RU) в единый вид «+7XXXXXXXXXX» — ключ сопоставления каналов обращений. */
public final class PhoneNormalizer {

    private PhoneNormalizer() {}

    /** «8 (777) 075-27-70» / «+7 777 075 27 70» / «7770752770» → «+77770752770»; иное → null. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 11 && d.startsWith("8")) {
            d = "7" + d.substring(1);
        } else if (d.length() == 10) {
            d = "7" + d;
        }
        return d.length() == 11 && d.startsWith("7") ? "+" + d : null;
    }

    /** Последние 10 цифр нормализованного номера (абонентская часть) или null. */
    public static String last10(String normalized) {
        return normalized == null || normalized.length() < 10 ? null : normalized.substring(normalized.length() - 10);
    }
}
