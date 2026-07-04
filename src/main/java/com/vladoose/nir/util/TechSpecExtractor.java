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
}
