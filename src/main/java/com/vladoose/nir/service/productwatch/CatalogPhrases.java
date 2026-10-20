package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.integration.MedicalGoodsVocabulary;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * «ТЗ списано с нашего товара» (спека §5.4). Отрезки названий и описаний товаров (между «.», «,», «;», скобками,
 * « – ») → окна по 2–4 значимых слова подряд, в окне — хотя бы одно редкое для каталога слово, окно не из списка общих
 * фраз. Отрезок засчитывается один раз, если в ТЗ есть любое его окно: начала слов по порядку, между соседними — не
 * больше одного другого слова. Оценка семейства (товары одного бренда в группе) — число разных найденных отрезков.
 * Порог и способ подсчёта — калибровка на настоящих ТЗ (задача 6 плана).
 */
public final class CatalogPhrases {

    /** Не меньше стольких разных отрезков — «списано» (калибровка). */
    public static final int COPY_MIN = 3;
    /** Считать по семейству (товары одного бренда в группе), а не по одному товару (калибровка). */
    public static final boolean BY_FAMILY = true;
    /** Слово «редкое», если встречается не больше чем у этой доли товаров каталога. */
    static final double RARE_SHARE = 0.05;
    /** Описание товара — первые столько символов (длинные описания без пользы раздувают окна). */
    static final int DESCRIPTION_MAX = 3000;

    /** Окно отрезка: товар, номер отрезка у товара, текст отрезка (для экрана), начала слов окна. */
    public record Phrase(long productId, int segment, String text, List<String> prefixes) {}

    public record Copy(long productId, int count, String evidence) {}

    static final Set<String> GENERIC = loadGeneric();

    private CatalogPhrases() {}

    public static Map<Long, List<Phrase>> build(List<CatalogProduct> products) {
        Map<Long, List<List<String>>> segments = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        for (CatalogProduct p : products) {
            List<List<String>> segs = new ArrayList<>();
            String description = p.description() == null ? null
                    : p.description().length() > DESCRIPTION_MAX ? p.description().substring(0, DESCRIPTION_MAX) : p.description();
            for (String src : new String[] { p.name(), description }) {
                if (src == null) continue;
                for (String seg : ProductText.normalize(src).split("[.,;:()\\[\\]\\n\\r•·|!?]|\\s[–—-]\\s")) {
                    List<String> sig = ProductText.words(seg).stream().filter(ProductText::significant).toList();
                    if (sig.size() >= 2) segs.add(sig);
                }
            }
            segments.put(p.id(), segs);
            Set<String> seen = new HashSet<>();
            for (List<String> s : segs) for (String w : s) if (seen.add(ProductText.prefix(w))) df.merge(ProductText.prefix(w), 1, Integer::sum);
        }
        int rareMax = Math.max(1, (int) Math.floor(products.size() * RARE_SHARE));
        Map<Long, List<Phrase>> out = new HashMap<>();
        for (CatalogProduct p : products) {
            List<Phrase> phrases = new ArrayList<>();
            List<List<String>> segs = segments.get(p.id());
            for (int si = 0; si < segs.size(); si++) {
                List<String> sig = segs.get(si);
                String text = String.join(" ", sig);
                Set<String> keys = new HashSet<>();
                for (int len = 2; len <= Math.min(4, sig.size()); len++) {
                    for (int i = 0; i + len <= sig.size(); i++) {
                        List<String> prefixes = sig.subList(i, i + len).stream().map(ProductText::prefix).toList();
                        String k = String.join(" ", prefixes);
                        if (GENERIC.contains(k) || !keys.add(k)) continue;
                        if (prefixes.stream().anyMatch(x -> df.getOrDefault(x, 0) <= rareMax)) phrases.add(new Phrase(p.id(), si, text, prefixes));
                    }
                }
            }
            out.put(p.id(), List.copyOf(phrases));
        }
        return out;
    }

    /** Позиции начал слов ТЗ — чтобы окно проверялось только от мест, где стоит его первое слово. */
    public static Map<String, List<Integer>> index(List<String> specPrefixes) {
        Map<String, List<Integer>> idx = new HashMap<>();
        for (int i = 0; i < specPrefixes.size(); i++) idx.computeIfAbsent(specPrefixes.get(i), k -> new ArrayList<>()).add(i);
        return idx;
    }

    /** Окно есть в ТЗ: начала слов по порядку, между соседними — не больше одного другого слова. */
    static boolean occurs(List<String> p, List<String> spec, Map<String, List<Integer>> idx) {
        outer:
        for (int start : idx.getOrDefault(p.get(0), List.of())) {
            int pos = start;
            for (int k = 1; k < p.size(); k++) {
                if (pos + 1 < spec.size() && spec.get(pos + 1).equals(p.get(k))) pos += 1;
                else if (pos + 2 < spec.size() && spec.get(pos + 2).equals(p.get(k))) pos += 2;
                else continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * Совпало ли ТЗ с товарами группы. Лучшее семейство — больше разных найденных отрезков; товар отметки — в этом
     * семействе больше отрезков, затем в названии есть стем группы, затем длиннее название (набор длиннее своих частей).
     * null — порог не набран.
     */
    public static Copy copied(List<CatalogProduct> groupProducts, Map<Long, List<Phrase>> phrases, List<String> specPrefixes,
                              List<String> groupStems) {
        Map<String, List<Integer>> idx = index(specPrefixes);
        Map<Long, LinkedHashSet<String>> found = new HashMap<>();   // товар → тексты найденных отрезков
        for (CatalogProduct p : groupProducts) {
            LinkedHashSet<String> segs = new LinkedHashSet<>();
            for (Phrase x : phrases.getOrDefault(p.id(), List.of())) {
                if (!segs.contains(x.text()) && occurs(x.prefixes(), specPrefixes, idx)) segs.add(x.text());
            }
            if (!segs.isEmpty()) found.put(p.id(), segs);
        }
        if (found.isEmpty()) return null;
        Map<String, List<CatalogProduct>> families = groupProducts.stream().filter(p -> found.containsKey(p.id()))
                .collect(Collectors.groupingBy(p -> p.brand() == null ? "" : p.brand().toLowerCase(Locale.ROOT)));
        Copy best = null;
        for (List<CatalogProduct> fam : families.values()) {
            LinkedHashSet<String> texts = new LinkedHashSet<>();
            fam.stream().sorted(Comparator.comparingInt((CatalogProduct p) -> -found.get(p.id()).size()))
                    .forEach(p -> texts.addAll(found.get(p.id())));
            CatalogProduct top = fam.stream().max(Comparator.comparingInt((CatalogProduct p) -> found.get(p.id()).size())
                    .thenComparing(p -> hasStem(p.name(), groupStems))
                    .thenComparingInt(p -> p.name().length())).orElseThrow();
            int count = BY_FAMILY ? texts.size() : found.get(top.id()).size();
            if (count < COPY_MIN) continue;
            if (best == null || count > best.count()) {
                String list = texts.stream().limit(4).collect(Collectors.joining("; "));
                best = new Copy(top.id(), count, "совпало " + count + " " + phrasesWord(count) + ": " + list);
            }
        }
        return best;
    }

    private static boolean hasStem(String name, List<String> stems) {
        String n = ProductText.normalize(name);
        return stems.stream().anyMatch(s -> MedicalGoodsVocabulary.matchesAny(n, List.of(s)));
    }

    static String phrasesWord(int n) {
        int d = n % 10, h = n % 100;
        if (d == 1 && h != 11) return "фраза";
        if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return "фразы";
        return "фраз";
    }

    static String key(String text) {
        return ProductText.words(ProductText.normalize(text)).stream().filter(ProductText::significant)
                .map(ProductText::prefix).collect(Collectors.joining(" "));
    }

    private static Set<String> loadGeneric() {
        try (InputStream in = CatalogPhrases.class.getResourceAsStream("/product-watch/generic-phrases.txt")) {
            if (in == null) return Set.of();
            Set<String> out = new HashSet<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(key(t));
            }
            return Set.copyOf(out);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("product-watch/generic-phrases.txt не читается", e);
        }
    }
}
