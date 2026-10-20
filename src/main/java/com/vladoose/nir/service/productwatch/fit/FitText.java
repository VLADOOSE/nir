package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.service.productwatch.ProductText;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Слова для сверки (спека §5.3): стем — первые 6 букв (5 у ProductText мало: «размер» и «размещение» совпали бы);
 * два стема совпадают, если один — начало другого и короче не меньше 4 букв («ламп» / «лампы»); синонимы параметров.
 */
public final class FitText {

    private static final List<List<String>> SYNONYMS = List.of(
            List.of("вакуум", "разреж"), List.of("произв", "поток", "расход"), List.of("крепле", "размещ", "монтаж"),
            List.of("емкост", "объем", "вмести"), List.of("масса", "вес"), List.of("габари", "размер"),
            List.of("количе", "число"), List.of("шум", "звук"));

    /** Служебные слова требований («наличие», «возможность», «должен») — не признак товара. */
    private static final List<String> STOP = List.of("наличи", "возмож", "функци", "должн", "необхо", "обязат", "требуе");

    private FitText() {}

    public static String stem(String w) {
        return w.length() <= 6 ? w : w.substring(0, 6);
    }

    public static Set<String> stems(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) return out;
        for (String w : ProductText.words(ProductText.normalize(text))) {
            if (!ProductText.significant(w) || ProductText.digits(w) > 0) continue;
            String st = stem(w);
            if (STOP.stream().anyMatch(st::startsWith)) continue;
            out.add(canon(st));
        }
        return out;
    }

    static String canon(String s) {
        for (List<String> g : SYNONYMS) for (String m : g) if (same(s, m)) return g.get(0);
        return s;
    }

    static boolean same(String a, String b) {
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b, longer = shorter == a ? b : a;
        return shorter.length() >= 4 && longer.startsWith(shorter);
    }

    /** Доля стемов need, найденных в have. need пуст — 0. */
    public static double overlap(Set<String> need, Set<String> have) {
        if (need.isEmpty()) return 0;
        int hit = 0;
        for (String n : need) {
            for (String h : have) if (same(n, h)) { hit++; break; }
        }
        return (double) hit / need.size();
    }
}
