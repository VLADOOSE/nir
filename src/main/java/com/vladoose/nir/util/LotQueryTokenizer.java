package com.vladoose.nir.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Название лота (+ характеристики из ТЗ) → значимые токены для пословного триграммного
 * реестр-матча. Канцелярит («устройство», «аппарат»…) и служебные слова выбрасываются:
 * они цепляют мусор при пониженном пороге и топят реальные изделия.
 *
 * <p>{@code weight} здесь — только ФАКТОР ИСТОЧНИКА: 1.0 для токенов имени, 0.5 для токенов
 * из ТЗ (вторичный сигнал). Различительная значимость слова (позиционная эвристика «первое
 * слово — главное» ненадёжна: у «Компьютерный томограф» различает как раз хвост) навешивается
 * не здесь, а в {@code RegistryMatchService} через IDF (редкость токена в реестре).
 */
public final class LotQueryTokenizer {

    public record WeightedToken(String token, double weight) {}

    private static final double NAME_WEIGHT = 1.0;
    private static final double SPEC_FACTOR = 0.5;   // токены из ТЗ — вторичный сигнал к имени
    private static final int MAX_PER_SOURCE = 5;

    private static final Set<String> STOP = Set.of(
            // канцелярит госзакуп-названий
            "устройство", "устройства", "аппарат", "аппарата", "аппаратный", "аппаратная",
            "система", "системы", "комплекс", "комплекса", "изделие", "изделия",
            "установка", "установки", "установку", "установок",   // родовое «unit» — цепляет воду/воздух/стерилизацию
            "прибор", "прибора", "оборудование", "оборудования", "комплект", "комплекта",
            "набор", "набора", "товар", "товара", "товаров", "штука", "штук",
            "размер", "размеры", "размера", "размеров",
            "медицинский", "медицинская", "медицинское", "медицинские", "медицинских",
            // родовые дескрипторы форм-фактора: цепляют «портативный анализатор» на «портативный
            // дефибриллятор» и т.п. — частота в реестре как у профильных слов, IDF их не различает
            "портативный", "портативная", "портативное", "портативные",
            "переносной", "переносная", "переносное", "носимый",
            "стационарный", "стационарная", "стационарное",
            "мобильный", "мобильная", "мобильное", "настольный", "настольная",
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
            if (i++ >= MAX_PER_SOURCE) break;
            out.add(new WeightedToken(t, NAME_WEIGHT));
        }
        int j = 0;
        for (String t : specTokens) {
            if (j++ >= MAX_PER_SOURCE) break;
            out.add(new WeightedToken(t, SPEC_FACTOR));
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
