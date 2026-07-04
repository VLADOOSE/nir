package com.vladoose.nir.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Название лота (+ характеристики из ТЗ) → значимые токены для пословного триграммного
 * реестр-матча. Канцелярит («устройство», «аппарат»…) и служебные слова выбрасываются:
 * они цепляют мусор при пониженном пороге и топят реальные изделия. Веса позиционные —
 * головное существительное в госзакуп-названиях идёт первым.
 */
public final class LotQueryTokenizer {

    public record WeightedToken(String token, double weight) {}

    // круто затухающие: головное существительное должно доминировать, иначе хвостовые
    // слова («портативный») перевешивают и топ забивают смежные изделия
    private static final double[] WEIGHTS = {1.0, 0.5, 0.35, 0.25, 0.2};
    private static final double SPEC_FACTOR = 0.5;
    private static final int MAX_PER_SOURCE = 5;

    private static final Set<String> STOP = Set.of(
            // канцелярит госзакуп-названий
            "устройство", "устройства", "аппарат", "аппарата", "аппаратный", "аппаратная",
            "система", "системы", "комплекс", "комплекса", "изделие", "изделия",
            "прибор", "прибора", "оборудование", "оборудования", "комплект", "комплекта",
            "набор", "набора", "товар", "товара", "товаров", "штука", "штук",
            "размер", "размеры", "размера", "размеров",
            "медицинский", "медицинская", "медицинское", "медицинские", "медицинских",
            // служебные
            "для", "или", "не", "более", "менее", "с", "со", "по", "на", "из", "к", "от", "до", "в", "и");

    private LotQueryTokenizer() {}

    public static List<WeightedToken> tokenize(String lotName, String specCharacteristics) {
        LinkedHashSet<String> nameTokens = significant(lotName);
        LinkedHashSet<String> specTokens = significant(specCharacteristics);
        specTokens.removeAll(nameTokens);

        List<WeightedToken> out = new ArrayList<>();
        int i = 0;
        for (String t : nameTokens) {
            if (i >= MAX_PER_SOURCE) break;
            out.add(new WeightedToken(t, WEIGHTS[i++]));
        }
        int j = 0;
        for (String t : specTokens) {
            if (j >= MAX_PER_SOURCE) break;
            out.add(new WeightedToken(t, WEIGHTS[j++] * SPEC_FACTOR));
        }
        return out;
    }

    private static LinkedHashSet<String> significant(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        // дефисные слова целиком; всё остальное не-буквенное — разделитель
        for (String raw : text.toLowerCase().split("[^\\p{L}-]+")) {
            String t = raw.replaceAll("^-+|-+$", "");
            if (t.length() < 3) continue;          // короткие и «мм/шт»
            if (!t.chars().anyMatch(Character::isLetter)) continue;
            if (STOP.contains(t)) continue;
            out.add(t);
        }
        return out;
    }
}
