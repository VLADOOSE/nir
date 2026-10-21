package com.vladoose.nir.service.productwatch;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Нормализация текста для правил «нашей продукции» (спека 2026-10-19-product-watch §5.1). */
public final class ProductText {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Map<Character, Character> LOOKALIKE = Map.ofEntries(
            Map.entry('a', 'а'), Map.entry('c', 'с'), Map.entry('e', 'е'), Map.entry('o', 'о'), Map.entry('p', 'р'),
            Map.entry('x', 'х'), Map.entry('y', 'у'), Map.entry('k', 'к'), Map.entry('m', 'м'), Map.entry('t', 'т'),
            Map.entry('h', 'н'), Map.entry('b', 'в'));
    /** Служебные слова фраз и ключевых слов (спека §5.4). */
    static final Set<String> STOP = Set.of("для", "или", "при", "под", "над", "без", "более", "менее", "шт", "мм", "см", "кг",
            "мл", "вт", "что", "как", "это", "его", "все", "всех", "также", "тип", "типа", "and", "the", "for", "with");

    /**
     * Начала родовых слов названия лота: ЕНС ТРУ «Изделия различные прочие, не включенные в другие группировки»,
     * «Аппарат», «Изделие медицинское», «Расходный материал» — предмет закупки такой лот называет только в описании.
     */
    private static final Set<String> GENERIC_NAME = Set.of("издел", "разли", "прочи", "включ", "други", "групп", "медиц",
            "назна", "товар", "обору", "прибо", "устро", "аппар", "систе", "компл", "набор", "инстр", "прина", "расхо", "матер",
            "специ", "средс", "техни", "проду", "однор", "приме");

    private ProductText() {}

    /** Название лота из одних родовых слов (или пустое) — предмет закупки ищется в описании. Текст — уже нормализован. */
    public static boolean genericName(String normalized) {
        for (String w : words(normalized)) if (significant(w) && !GENERIC_NAME.contains(prefix(w))) return false;
        return true;
    }

    /** Нижний регистр, «ё» → «е», латинские двойники внутри слов с кириллицей → кириллица; разделители — как были. */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        String low = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        Matcher m = TOKEN.matcher(low);
        StringBuilder out = new StringBuilder(low.length());
        int last = 0;
        while (m.find()) {
            out.append(low, last, m.start());
            String tok = m.group();
            out.append(hasCyrillic(tok) ? delatin(tok) : tok);
            last = m.end();
        }
        return out.append(low.substring(last)).toString();
    }

    /** Слова (буквы и цифры подряд) уже нормализованного текста. */
    public static List<String> words(String normalized) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(normalized == null ? "" : normalized);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Начала слов произвольного текста (нормализация + {@link #prefix}). */
    public static List<String> prefixes(String text) {
        return words(normalize(text)).stream().map(ProductText::prefix).toList();
    }

    /** Значимое слово: не короче 3 символов, не служебное, не одни цифры. */
    public static boolean significant(String w) {
        return w.length() >= 3 && !STOP.contains(w) && !w.chars().allMatch(Character::isDigit);
    }

    /** Начало слова для сравнения с учётом окончаний: первые 5 символов (короче — целиком). */
    public static String prefix(String w) {
        return w.length() <= 5 ? w : w.substring(0, 5);
    }

    /** Локальный стем слова поиска площадки: без конечных гласных, «ь», «й»; не короче 5 символов. */
    public static String searchStem(String word) {
        String n = normalize(word).strip();
        String s = n.replaceAll("[аеиоуыэюяьй]+$", "");
        return s.length() >= 5 ? s : n;
    }

    static boolean hasCyrillic(String t) {
        for (int i = 0; i < t.length(); i++) if (Character.UnicodeBlock.of(t.charAt(i)) == Character.UnicodeBlock.CYRILLIC) return true;
        return false;
    }

    static int letters(String t) {
        int n = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isLetter(t.charAt(i))) n++;
        return n;
    }

    public static int digits(String t) {
        int n = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) n++;
        return n;
    }

    private static String delatin(String tok) {
        StringBuilder b = new StringBuilder(tok.length());
        for (int i = 0; i < tok.length(); i++) b.append(LOOKALIKE.getOrDefault(tok.charAt(i), tok.charAt(i)));
        return b.toString();
    }
}
