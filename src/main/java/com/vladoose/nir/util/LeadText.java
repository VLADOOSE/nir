package com.vladoose.nir.util;

/** Мелочи обработки текста обращений (обрезка под длину колонки, пустое → null). */
public final class LeadText {

    private LeadText() {}

    public static boolean isBlank(String s) { return s == null || s.isBlank(); }

    public static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }

    public static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
