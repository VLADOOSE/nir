package com.vladoose.nir.util;

import com.vladoose.nir.exception.BadRequestException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Один или несколько адресов почты в одном поле — через запятую, точку с запятой или пробел (спека 2026-10-12 §6.2):
 * у ЭМА светильники и облучатели ведут разные отделы. Каждый адрес проверяется; неверный — 400 с самим адресом в тексте.
 */
public final class EmailAddresses {

    /** Колонки distributor.email и price_request.sent_to — VARCHAR(255). */
    static final int MAX_FIELD = 255;

    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");
    /** Простая форма «local@domain.tld»: имя с угловыми скобками и группы адресов в поле не нужны. */
    private static final Pattern SIMPLE =
            Pattern.compile("^[^@\\s<>()\\[\\],;:\"]+@[^@\\s<>()\\[\\],;:\"]+\\.[^@\\s<>()\\[\\],;:\".]{2,}$");

    private EmailAddresses() {}

    /** Адреса поля по порядку, без повторов (без учёта регистра); пусто — пустой список. */
    public static List<String> parse(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String part : split(raw)) {
            if (!valid(part)) throw new BadRequestException("Неверный адрес почты: «" + part + "»");
            out.putIfAbsent(part.toLowerCase(Locale.ROOT), part);
        }
        return List.copyOf(out.values());
    }

    /** Поле в каноническом виде «a@x.kz, b@y.kz»; пусто — пустая строка (так форма карточки очищает почту). */
    public static String normalize(String raw) {
        String joined = String.join(", ", parse(raw));
        if (joined.length() > MAX_FIELD) {
            throw new BadRequestException("Слишком много адресов: в поле почты помещается " + MAX_FIELD + " символов");
        }
        return joined;
    }

    /** Те же адреса без учёта регистра и разделителей. Формат не проверяется: в карточке бывают старые кривые адреса. */
    public static boolean same(String a, String b) {
        return lowered(a).equals(lowered(b));
    }

    /** Для лога — только домены: адреса — персональные данные (спека §10). */
    public static String domains(List<String> addresses) {
        return addresses.stream().map(a -> a.substring(Math.max(0, a.indexOf('@')))).distinct()
                .reduce((x, y) -> x + ", " + y).orElse("—");
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : SEPARATORS.split(raw.trim())) if (!part.isEmpty()) out.add(part);
        return out;
    }

    private static List<String> lowered(String raw) {
        return split(raw).stream().map(s -> s.toLowerCase(Locale.ROOT)).distinct().toList();
    }

    private static boolean valid(String address) {
        if (address.length() > MAX_FIELD || !SIMPLE.matcher(address).matches()) return false;
        try {
            new InternetAddress(address, true).validate();
            return true;
        } catch (AddressException e) {
            return false;
        }
    }
}
