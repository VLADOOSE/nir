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

    /** Не меньше стольких разных мест ТЗ совпало с фразами семейства — «списано» (калибровка 2026-10-20, задача 6). */
    public static final int COPY_MIN = 2;
    /** …и они покрывают не меньше этой доли слов тела ТЗ: длинное чужое ТЗ делит с нашими описаниями пару общих мест. */
    public static final double COVER_MIN = 0.04;
    /** Окна описаний — не короче стольких слов (двухсловные «цветном сенсорном», «лампы мощностью» есть в любом ТЗ вида). */
    public static final int MIN_WINDOW_DESCRIPTION = 3;
    /** Считать по семейству (товары одного бренда в группе), а не по одному товару (калибровка). */
    public static final boolean BY_FAMILY = true;
    /** Слово «редкое», если встречается не больше чем у этой доли товаров каталога. */
    static final double RARE_SHARE = 0.05;
    /** Описание товара — первые столько символов (длинные описания без пользы раздувают окна). */
    static final int DESCRIPTION_MAX = 3000;

    /** Окно отрезка: товар, номер отрезка у товара, текст отрезка (для экрана), начала слов окна, редкие из них. */
    public record Phrase(long productId, int segment, String text, List<String> prefixes, List<String> rare) {}

    public record Copy(long productId, int count, String evidence) {}

    /**
     * Настройки правила (калибровка, задача 6 плана): фразы из описаний или только из названий, наименьшее окно фраз
     * названия и описания, наибольшее окно, доля «редкого» слова, порог и способ подсчёта.
     */
    public record Rules(boolean descriptions, int minWindowName, int minWindowDescription, int maxWindow, double rareShare,
                        int copyMin, boolean byFamily, double coverMin) {
        public static final Rules DEFAULT = new Rules(true, 2, MIN_WINDOW_DESCRIPTION, 4, RARE_SHARE, COPY_MIN, BY_FAMILY, COVER_MIN);
    }

    static final Set<String> GENERIC = loadGeneric();

    private CatalogPhrases() {}

    public static Map<Long, List<Phrase>> build(List<CatalogProduct> products) {
        return build(products, Rules.DEFAULT);
    }

    public static Map<Long, List<Phrase>> build(List<CatalogProduct> products, Rules rules) {
        Map<Long, List<List<String>>> segments = new HashMap<>();
        Map<Long, Integer> nameSegments = new HashMap<>();   // у товара первые столько отрезков — из названия
        Map<String, Integer> df = new HashMap<>();
        for (CatalogProduct p : products) {
            List<List<String>> segs = new ArrayList<>();
            String description = p.description() == null ? null
                    : p.description().length() > DESCRIPTION_MAX ? p.description().substring(0, DESCRIPTION_MAX) : p.description();
            for (String src : new String[] { p.name(), rules.descriptions() ? description : null }) {
                if (src == null) continue;
                for (String seg : ProductText.normalize(src).split("[.,;:()\\[\\]\\n\\r•·|!?]|\\s[–—-]\\s")) {
                    List<String> sig = ProductText.words(seg).stream().filter(ProductText::significant).toList();
                    if (sig.size() >= 2) segs.add(sig);
                }
                if (src == p.name()) nameSegments.put(p.id(), segs.size());
            }
            segments.put(p.id(), segs);
            Set<String> seen = new HashSet<>();
            for (List<String> s : segs) for (String w : s) if (seen.add(ProductText.prefix(w))) df.merge(ProductText.prefix(w), 1, Integer::sum);
        }
        int rareMax = Math.max(1, (int) Math.floor(products.size() * rules.rareShare()));
        Map<Long, List<Phrase>> out = new HashMap<>();
        for (CatalogProduct p : products) {
            List<Phrase> phrases = new ArrayList<>();
            List<List<String>> segs = segments.get(p.id());
            for (int si = 0; si < segs.size(); si++) {
                List<String> sig = segs.get(si);
                String text = String.join(" ", sig);
                Set<String> keys = new HashSet<>();
                int minLen = si < nameSegments.getOrDefault(p.id(), 0) ? rules.minWindowName() : rules.minWindowDescription();
                for (int len = minLen; len <= Math.min(rules.maxWindow(), sig.size()); len++) {
                    for (int i = 0; i + len <= sig.size(); i++) {
                        List<String> prefixes = sig.subList(i, i + len).stream().map(ProductText::prefix).toList();
                        String k = String.join(" ", prefixes);
                        if (GENERIC.contains(k) || !keys.add(k)) continue;
                        List<String> rare = prefixes.stream().filter(x -> df.getOrDefault(x, 0) <= rareMax).toList();
                        if (!rare.isEmpty()) phrases.add(new Phrase(p.id(), si, text, prefixes, rare));
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
        return find(p, spec, idx) != null;
    }

    /** Первое место окна в ТЗ — {начало, конец} (позиции слов) или null. */
    static int[] find(List<String> p, List<String> spec, Map<String, List<Integer>> idx) {
        outer:
        for (int start : idx.getOrDefault(p.get(0), List.of())) {
            int pos = start;
            for (int k = 1; k < p.size(); k++) {
                if (pos + 1 < spec.size() && spec.get(pos + 1).equals(p.get(k))) pos += 1;
                else if (pos + 2 < spec.size() && spec.get(pos + 2).equals(p.get(k))) pos += 2;
                else continue outer;
            }
            return new int[] {start, pos};
        }
        return null;
    }

    /** Доля слов ТЗ, покрытых совпадениями. */
    static double coverage(List<int[]> spans, int specWords) {
        if (specWords == 0) return 0;
        BitSet covered = new BitSet(specWords);
        for (int[] x : spans) covered.set(x[0], Math.min(specWords, x[1] + 1));
        return (double) covered.cardinality() / specWords;
    }

    /** Число разных мест ТЗ: пересекающиеся совпадения — одно место (одна фраза ТЗ, совпавшая с несколькими окнами). */
    static int places(List<int[]> spans) {
        List<int[]> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingInt((int[] x) -> x[0]));
        int n = 0, end = -1;
        for (int[] x : sorted) {
            if (x[0] > end) n++;
            end = Math.max(end, x[1]);
        }
        return n;
    }

    /**
     * Совпало ли ТЗ с товарами группы. Лучшее семейство — больше разных найденных отрезков; товар отметки — в этом
     * семействе больше отрезков, затем в названии есть стем группы, затем длиннее название (набор длиннее своих частей).
     * null — порог не набран.
     */
    public static Copy copied(List<CatalogProduct> groupProducts, Map<Long, List<Phrase>> phrases, List<String> specPrefixes,
                              List<String> groupStems) {
        return copied(groupProducts, phrases, specPrefixes, groupStems, Set.of(), Rules.DEFAULT);
    }

    /**
     * ownWords — начала слов самой группы (слова поиска и уточнения): они есть в любом ТЗ своего вида, поэтому окно
     * засчитывается, только если в нём есть другое редкое слово (у рециркуляторов «рециркулятор бактерицидный» — общее).
     */
    public static Copy copied(List<CatalogProduct> groupProducts, Map<Long, List<Phrase>> phrases, List<String> specPrefixes,
                              List<String> groupStems, Set<String> ownWords, Rules rules) {
        Map<String, List<Integer>> idx = index(specPrefixes);
        Map<Long, LinkedHashSet<String>> found = new HashMap<>();   // товар → тексты найденных отрезков
        Map<Long, List<int[]>> spans = new HashMap<>();             // товар → места ТЗ найденных окон
        for (CatalogProduct p : groupProducts) {
            LinkedHashSet<String> segs = new LinkedHashSet<>();
            List<int[]> at = new ArrayList<>();
            for (Phrase x : phrases.getOrDefault(p.id(), List.of())) {
                if (x.rare().stream().allMatch(ownWords::contains)) continue;
                int[] span = find(x.prefixes(), specPrefixes, idx);
                if (span == null) continue;
                segs.add(x.text());
                at.add(span);
            }
            if (!segs.isEmpty()) {
                found.put(p.id(), segs);
                spans.put(p.id(), at);
            }
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
            List<int[]> famSpans = new ArrayList<>();
            fam.forEach(p -> famSpans.addAll(spans.get(p.id())));
            List<int[]> counted = rules.byFamily() ? famSpans : spans.get(top.id());
            int count = places(counted);
            double cover = coverage(counted, specPrefixes.size());
            if (count < rules.copyMin() || cover < rules.coverMin()) continue;
            if (best == null || count > best.count()) {
                String list = texts.stream().limit(4).collect(Collectors.joining("; "));
                best = new Copy(top.id(), count, "совпало " + count + " " + phrasesWord(count) + " (" + Math.round(cover * 100)
                        + " % ТЗ): " + list);
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
