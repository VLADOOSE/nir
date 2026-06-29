package com.vladoose.nir.integration.goszakup;

import java.time.LocalDate;

/** Null-безопасный разбор полей goszakup. */
final class GoszakupParse {
    private GoszakupParse() {}

    static LocalDate localDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try { return LocalDate.parse(iso.substring(0, 10)); }
        catch (Exception e) { return null; }
    }

    static Integer intOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.valueOf(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
