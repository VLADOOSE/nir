package com.vladoose.nir.service.market;

import com.vladoose.nir.integration.MedicalGoodsVocabulary;

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

    /**
     * Лучшая категория: её главное слово (первое значимое слово названия, у названия из общих слов — описания) есть в тексте,
     * дальше — больше слов текста в её названии и описании, то же главное слово, больше договоров. Слова сравниваются целиком,
     * окончание — до 3 букв (живая проверка 2026-10-22: по 5 буквам «электрохирургический» = «электрод», «интерпретацией» =
     * «интернет»). Найденная только по доп. описанию пункта плана — не про этот товар («Эндоскоп» → «Услуги по обучению» с
     * «эндоскопической диагностикой»); услуги — только для услуги.
     */
    public static Category pick(String text, List<Category> cats) {
        List<String> t = words(text);
        String kw = keyword(text);
        return relevant(text, cats).stream().filter(c -> mainWordIn(c, t)).filter(c -> overlap(t, catWords(c)) > 0)
                .max(Comparator.comparingInt((Category c) -> overlap(t, catWords(c)))
                        .thenComparing(c -> kw != null && kw.equals(keyword(c.name())))   // «Тележка» — не «Чемодан-тележка»
                        .thenComparingInt(Category::count)).orElse(null);
    }

    /** Категории для товара — без работ и услуг по названию (тара для медотходов — товар); для услуги — все. */
    public static List<Category> relevant(String text, List<Category> cats) {
        if (service(text, null)) return cats;
        return cats.stream().filter(c -> !service(c.name(), c.description())).toList();
    }

    private static boolean service(String name, String description) {
        String n = Objects.toString(name, "");
        return MedicalGoodsVocabulary.hasWorksServices(n)
                && !MedicalGoodsVocabulary.isMedicalWasteContainer(n, Objects.toString(description, ""));
    }

    /** «Работы по ремонту …», «Услуги по …» — главное слово после них. */
    private static final Set<String> SERVICE_HEAD = Set.of("работы", "работ", "работа", "услуги", "услуг", "услуга", "оказание", "выполнение");

    private static boolean mainWordIn(Category c, List<String> text) {
        String main = mainWord(c.name());
        if (main == null) main = mainWord(c.description());
        if (main == null) return false;
        for (String w : text) if (same(w, main)) return true;
        return false;
    }

    private static String mainWord(String s) {
        for (String w : words(s)) if (!SERVICE_HEAD.contains(w)) return w;
        return null;
    }

    private static List<String> catWords(Category c) {
        return words(c.name() + " " + Objects.toString(c.description(), ""));
    }

    /** Значимые слова: нижний регистр, от 3 букв, без общих. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !GENERIC.contains(w)) out.add(w);
        }
        return out;
    }

    /** Одно слово с точностью до окончания: длины различаются не больше чем на 3, короче — без 2 последних букв (не короче 4) — начало длинного. */
    static boolean same(String a, String b) {
        String s = a.length() <= b.length() ? a : b;
        String l = s == a ? b : a;
        if (l.length() - s.length() > 3) return false;
        int p = Math.min(s.length(), Math.max(4, s.length() - 2));
        return l.startsWith(s.substring(0, p));
    }

    /** Сколько слов текста есть среди слов категории. */
    private static int overlap(List<String> text, List<String> cat) {
        int n = 0;
        for (String x : text) {
            for (String y : cat) {
                if (same(x, y)) { n++; break; }
            }
        }
        return n;
    }
}
