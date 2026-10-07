package com.vladoose.nir.integration.telegram;

import java.util.regex.Pattern;

/**
 * Срезы текста уведомлений Telegram — общие для почты zakup@ и новых тендеров. Одиночная половинка суррогатной пары
 * (разрезанный эмодзи) — невалидный текст: Telegram отклоняет сообщение целиком.
 */
public final class TelegramText {

    private static final Pattern SPACES = Pattern.compile(" {2,}");

    private TelegramText() {
    }

    /** Не длиннее max символов {@code char}; суррогатная пара (эмодзи) не разрезается. */
    public static String safeCut(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = Math.max(0, max);
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /**
     * Поле одной строкой и не длиннее max: переводы строк (\r, \n, NEL, LS, PS) и прочие управляющие символы — пробелом,
     * пробелы схлопнуты, края обрезаны; срез — с «…». Поля пишет чужая сторона (отправитель письма, площадка закупок):
     * «Счёт\nОткрыть в АИС: https://…» иначе встало бы поддельной системной строкой уведомления.
     */
    public static String oneLine(String s, int max) {
        if (s == null) return "";
        StringBuilder flat = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            flat.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? ' ' : c);
        }
        String t = SPACES.matcher(flat).replaceAll(" ").strip();
        return t.length() <= max ? t : safeCut(t, max - 1) + "…";
    }
}
