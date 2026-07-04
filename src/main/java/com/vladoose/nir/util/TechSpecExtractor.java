package com.vladoose.nir.util;

/**
 * Вырезает русскую секцию из двуязычной техспеки goszakup (сначала казахская, затем русская).
 * Маркеры русской шапки: «Приложение …» / «Техническая спецификация» (в казахской части их нет —
 * там «қосымша» и «техникалық ерекшелігі»). Маркеров нет → весь текст как есть.
 */
public final class TechSpecExtractor {

    private static final String[] MARKERS = {"приложение", "техническая спецификация"};

    private TechSpecExtractor() {}

    public static String russianSection(String fullText) {
        if (fullText == null || fullText.isBlank()) return null;
        String lower = fullText.toLowerCase();
        int best = -1;
        for (String m : MARKERS) {
            int i = lower.indexOf(m);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        String section = best >= 0 ? fullText.substring(best) : fullText;
        return section.strip();
    }

    /** Якорь блока с самым содержательным описанием изделия в шаблоне ТЗ goszakup. */
    private static final String CHARS_ANCHOR = "характеристики закупаемых товаров";

    /**
     * Самое содержательное описание изделия из ТЗ — текст после метки «…характеристики
     * закупаемых товаров…» (в шаблоне goszakup там требования к товару). Для реестр-матча
     * это точнее куцего наименования лота. null — якорь не найден.
     */
    public static String characteristics(String fullText) {
        if (fullText == null || fullText.isBlank()) return null;
        // метка в PDF разбита переносами — нормализуем пробелы для поиска
        String norm = fullText.replaceAll("\\s+", " ");
        int i = norm.toLowerCase().indexOf(CHARS_ANCHOR);
        if (i < 0) return null;
        String tail = norm.substring(i + CHARS_ANCHOR.length()).strip();
        // после якоря в шаблоне идёт двоеточие/«товара» и т.п. — срезаем ведущие связки
        tail = tail.replaceFirst("^[:*\\s]+", "").strip();
        return tail.isBlank() ? null : tail;
    }
}
