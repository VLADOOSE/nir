package com.vladoose.nir.util;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ранжирует компоненты комплектности аппарата против айтема лота: доля токенов лота (слова изделия
 * + размеры «55»/«80» — здесь это дискриминаторы), встречающихся в тексте компонента. Чистый Java —
 * компонентов у аппарата ≤~15, БД не нужна. Абсолютное значение — не метрика, важен относительный ранг.
 */
public final class ComplectComponentMatcher {

    // мусорные короткие слова единиц/предлогов; цифры и слова-изделия сохраняем
    private static final Set<String> NOISE = Set.of("мм", "см", "шт", "для", "или", "по", "на", "штук");

    private ComplectComponentMatcher() {}

    public static Set<String> tokenize(String lotText) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (lotText == null) return out;
        for (String raw : lotText.toLowerCase().split("[^\\p{L}\\p{Nd}]+")) {
            if (raw.length() < 2) continue;              // одиночные буквы/цифры-шум
            if (NOISE.contains(raw)) continue;
            out.add(raw);
        }
        return out;
    }

    /** Доля токенов лота, встречающихся подстрокой в тексте компонента (0..1). */
    public static double score(Set<String> lotTokens, String componentText) {
        if (lotTokens.isEmpty() || componentText == null) return 0.0;
        String hay = componentText.toLowerCase();
        long hit = lotTokens.stream().filter(hay::contains).count();
        return (double) hit / lotTokens.size();
    }
}
