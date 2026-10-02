package com.vladoose.nir.service;

import com.vladoose.nir.entity.CompanyProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Строки реквизитов под логотипом бланка (спека §6.3) — одни и те же в КП, PDF заявки и Excel рентабельности:
 * идентификаторы; адрес; счета; «Банк: … БИК: …»; «Тел. …, электронный адрес: …». Пустое не печатается.
 */
public final class CompanyLines {

    private CompanyLines() {}

    public static List<String> of(CompanyProfile p) {
        List<String> lines = new ArrayList<>();
        add(lines, p.getIdsLine());
        lines.addAll(split(p.getAddress()));
        lines.addAll(split(p.getAccounts()));
        add(lines, join(" ", prefixed("Банк: ", p.getBankName()), prefixed("БИК: ", p.getBik())));
        add(lines, join(", ", prefixed("Тел. ", p.getPhone()), prefixed("электронный адрес: ", p.getEmail())));
        return lines;
    }

    /** Непустые строки текста без пробелов по краям. */
    public static List<String> split(String text) {
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static void add(List<String> lines, String s) {
        if (s != null && !s.isBlank()) lines.add(s.trim());
    }

    private static String prefixed(String prefix, String value) {
        return value == null || value.isBlank() ? null : prefix + value.trim();
    }

    private static String join(String separator, String... parts) {
        String joined = Arrays.stream(parts).filter(Objects::nonNull).collect(Collectors.joining(separator));
        return joined.isEmpty() ? null : joined;
    }
}
