package com.vladoose.nir.service.market;

import java.util.*;

/**
 * Категория цен по тексту (спека §9): ключевое слово — первое значимое (без общих «изделие», «медицинский», «аппарат», «набор»…),
 * категории набора с этим словом — лучшая по совпадению слов текста с названием и описанием, при равенстве — где больше договоров.
 */
public final class MarketCategories {

    public record Category(String code, String name, String description, int count) {}

    private static final Set<String> GENERIC = Set.of("изделие", "изделия", "изделий", "медицинский", "медицинская", "медицинское",
            "медицинские", "медицинских", "медицинского", "медицинской", "аппарат", "аппарата", "аппараты", "набор", "набора", "наборы",
            "система", "системы", "комплект", "комплекта", "прибор", "приборы", "оборудование", "оборудования", "устройство",
            "устройства", "товар", "товары", "расходный", "расходные", "материал", "материалы", "принадлежности", "одноразовый",
            "одноразовые", "стерильный", "стерильные", "для", "при", "под", "без", "или", "and", "for", "with");

    private MarketCategories() {}

    public static String keyword(String text) {
        if (text == null) return null;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.length() >= 3 && !GENERIC.contains(w)) return w;
        }
        return null;
    }

    public static Category pick(String text, List<Category> cats) {
        if (cats.isEmpty()) return null;
        Set<String> t = stems(text);
        return cats.stream().max(Comparator.comparingInt((Category c) -> overlap(t, stems(c.name() + " " + Objects.toString(c.description(), ""))))
                .thenComparingInt(Category::count)).orElse(null);
    }

    static Set<String> stems(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !GENERIC.contains(w)) out.add(w.length() > 5 ? w.substring(0, 5) : w);
        }
        return out;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String x : a) if (b.contains(x)) n++;
        return n;
    }
}
