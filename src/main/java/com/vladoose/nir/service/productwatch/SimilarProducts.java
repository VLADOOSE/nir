package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.SimilarProductRef;

import java.util.*;

/**
 * Похожие наши товары (спека §5.5): ключевые слова товара — значимые слова начала названия (до первого кода, кавычки,
 * скобки или тире), пусто — краткого описания; оценка — доля веса ключевых слов, найденных в тексте лота (вес —
 * редкость слова в каталоге). Ниже {@link #MIN} не показывается, не больше {@link #LIMIT}.
 */
public final class SimilarProducts {

    static final double MIN = 0.35;
    static final int LIMIT = 3;

    private SimilarProducts() {}

    static List<String> keyWords(CatalogProduct p) {
        String head = ProductText.normalize(p.name()).split("[«\"“(]|\\s[–—-]\\s|\\.\\s")[0];
        List<String> out = new ArrayList<>();
        for (String w : ProductText.words(head)) {
            if (ProductText.digits(w) > 0) break;
            if (ProductText.significant(w)) out.add(ProductText.prefix(w));
        }
        if (out.isEmpty() && p.shortDescription() != null) {
            ProductText.words(ProductText.normalize(p.shortDescription())).stream().filter(ProductText::significant)
                    .limit(8).map(ProductText::prefix).forEach(out::add);
        }
        return out;
    }

    public static Map<String, Double> idf(List<CatalogProduct> products) {
        Map<String, Integer> df = new HashMap<>();
        for (CatalogProduct p : products) for (String k : new HashSet<>(keyWords(p))) df.merge(k, 1, Integer::sum);
        Map<String, Double> out = new HashMap<>();
        int n = Math.max(1, products.size());
        df.forEach((k, v) -> out.put(k, Math.log(1.0 + (double) n / v)));
        return out;
    }

    public static List<SimilarProductRef> top(List<CatalogProduct> candidates, Set<String> lotPrefixes, Map<String, Double> idf) {
        record Scored(CatalogProduct p, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (CatalogProduct p : candidates) {
            List<String> key = keyWords(p);
            if (key.isEmpty()) continue;
            double num = 0, den = 0;
            for (String k : new LinkedHashSet<>(key)) {
                double w = idf.getOrDefault(k, 1.0);
                den += w;
                if (lotPrefixes.contains(k)) num += w;
            }
            double s = den == 0 ? 0 : num / den;
            if (s >= MIN) scored.add(new Scored(p, s));
        }
        scored.sort(Comparator.comparingDouble((Scored x) -> -x.score()).thenComparingInt(x -> x.p().name().length()));
        List<SimilarProductRef> out = new ArrayList<>();
        Set<String> brands = new HashSet<>();
        for (Scored x : scored) {                            // сначала разные бренды — подсказка, что предлагать
            if (out.size() == LIMIT) break;
            if (brands.add(String.valueOf(x.p().brand()))) out.add(ref(x.p(), x.score()));
        }
        for (Scored x : scored) {
            if (out.size() == LIMIT) break;
            if (out.stream().noneMatch(r -> r.productId() == x.p().id())) out.add(ref(x.p(), x.score()));
        }
        return out;
    }

    private static SimilarProductRef ref(CatalogProduct p, double s) {
        return new SimilarProductRef(p.id(), p.name(), p.brand(), p.slug(), Math.round(s * 1000) / 1000.0);
    }
}
