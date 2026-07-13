package com.vladoose.nir.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Мост латиница↔кириллица для брендов мед.техники. Реестр НЦЭЛС РК пишет производителя то латиницей
 * («Samsung Medison Co.,Ltd.»), то кириллицей («Самсунг Медисон Ко., Лтд.») — а pg_trgm word_similarity
 * между разными скриптами = 0 (нет общих триграмм), поэтому поиск по «Samsung» не находит кириллическую
 * запись и наоборот. {@link #expand} к введённому/извлечённому бренд-термину добавляет вариант в другом
 * скрипте по словарю известных брендов, чтобы {@code findApparatusByTerm} нашёл запись независимо от того,
 * каким скриптом в ней записан производитель. Оригинал всегда первым; неизвестный бренд → синглтон.
 */
public final class BrandTransliterator {

    // бренд(латиница, lower) → бренд(кириллица); подстрочная замена регистронезависимо в обе стороны
    private static final Map<String, String> LAT_TO_CYR = new LinkedHashMap<>();
    static {
        LAT_TO_CYR.put("samsung", "Самсунг");
        LAT_TO_CYR.put("medison", "Медисон");
        LAT_TO_CYR.put("mindray", "Майндрей");
        LAT_TO_CYR.put("philips", "Филипс");
        LAT_TO_CYR.put("siemens", "Сименс");
        LAT_TO_CYR.put("toshiba", "Тошиба");
        LAT_TO_CYR.put("hitachi", "Хитачи");
        LAT_TO_CYR.put("canon", "Кэнон");
        LAT_TO_CYR.put("olympus", "Олимпус");
        LAT_TO_CYR.put("fujifilm", "Фуджифилм");
        LAT_TO_CYR.put("shimadzu", "Шимадзу");
        LAT_TO_CYR.put("carestream", "Кэрстрим");
        LAT_TO_CYR.put("storz", "Шторц");
        LAT_TO_CYR.put("draeger", "Дрегер");
        LAT_TO_CYR.put("drager", "Дрегер");
    }

    private BrandTransliterator() {}

    /** Оригинал (первым) + варианты в другом скрипте по известным брендам. Пусто/незнакомый → как есть. */
    public static List<String> expand(String term) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (term == null || term.isBlank()) return new ArrayList<>();
        String t = term.trim();
        out.add(t);
        String lower = t.toLowerCase();
        for (Map.Entry<String, String> e : LAT_TO_CYR.entrySet()) {
            String lat = e.getKey(), cyr = e.getValue();
            if (lower.contains(lat)) out.add(replaceCI(t, lat, cyr));
            if (lower.contains(cyr.toLowerCase())) out.add(replaceCI(t, cyr, lat));
        }
        return new ArrayList<>(out);
    }

    private static String replaceCI(String src, String find, String repl) {
        return Pattern.compile(Pattern.quote(find), Pattern.CASE_INSENSITIVE)
                .matcher(src).replaceAll(Matcher.quoteReplacement(repl));
    }
}
