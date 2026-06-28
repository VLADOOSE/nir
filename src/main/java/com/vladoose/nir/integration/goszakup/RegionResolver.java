package com.vladoose.nir.integration.goszakup;

import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RegionResolver {

    /** Паттерн (нижний регистр, подстрока) → каноническое имя региона (== одной из REGIONS на фронте). */
    private static final Map<String, String> PATTERNS = new LinkedHashMap<>();
    static {
        // --- области (специфичные подстроки идут раньше городов) ---
        PATTERNS.put("акмолинск", "Акмолинская область");
        PATTERNS.put("актюбинск", "Актюбинская область");
        PATTERNS.put("алматинск", "Алматинская область");
        PATTERNS.put("атырауск", "Атырауская область");
        PATTERNS.put("восточно-казахстанск", "Восточно-Казахстанская область");
        PATTERNS.put("вко", "Восточно-Казахстанская область");
        PATTERNS.put("жамбылск", "Жамбылская область");
        PATTERNS.put("западно-казахстанск", "Западно-Казахстанская область");
        PATTERNS.put("зко", "Западно-Казахстанская область");
        PATTERNS.put("карагандинск", "Карагандинская область");
        PATTERNS.put("костанайск", "Костанайская область");
        PATTERNS.put("кызылординск", "Кызылординская область");
        PATTERNS.put("мангистауск", "Мангистауская область");
        PATTERNS.put("мангыстауск", "Мангистауская область");
        PATTERNS.put("павлодарск", "Павлодарская область");
        PATTERNS.put("северо-казахстанск", "Северо-Казахстанская область");
        PATTERNS.put("ско", "Северо-Казахстанская область");
        PATTERNS.put("туркестанск", "Туркестанская область");
        PATTERNS.put("абайск", "Абайская область");
        PATTERNS.put("область абай", "Абайская область");
        PATTERNS.put("жетысуск", "Жетысуская область");
        PATTERNS.put("жетісу", "Жетысуская область");
        PATTERNS.put("улытауск", "Улытауская область");
        PATTERNS.put("ұлытау", "Улытауская область");
        // --- города республиканского значения ---
        PATTERNS.put("нур-султан", "г. Астана");
        PATTERNS.put("нұр-сұлтан", "г. Астана");
        PATTERNS.put("астана", "г. Астана");
        PATTERNS.put("шымкент", "г. Шымкент");
        PATTERNS.put("алматы", "г. Алматы");  // последним: омоним «алматинск» уже отработал выше
    }

    public String resolve(String... textCandidates) {
        if (textCandidates == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String c : textCandidates) {
            if (c != null && !c.isBlank()) sb.append(c.toLowerCase(Locale.ROOT)).append(" | ");
        }
        String hay = sb.toString();
        if (hay.isBlank()) return null;
        // короткие аббревиатуры (вко/зко/ско) матчим как отдельный токен,
        // чтобы «ско» не ловилось внутри «республиканское» и т.п.
        Set<String> tokens = new HashSet<>(Arrays.asList(hay.split("[^\\p{L}]+")));
        for (Map.Entry<String, String> e : PATTERNS.entrySet()) {
            String key = e.getKey();
            boolean matched = key.length() <= 3 ? tokens.contains(key) : hay.contains(key);
            if (matched) return e.getValue();
        }
        return null;
    }
}
