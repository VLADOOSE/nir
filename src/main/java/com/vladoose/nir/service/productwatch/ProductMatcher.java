package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SimilarProductRef;

import java.util.*;

/**
 * Лоты объявления → отметки «наш товар» (спека §5). Чистая функция над снимком: без базы и сети.
 * <ul>
 *   <li>группа «Наши бренды и модели» — сразу NAMED, если её слово (или общий бренд / код модели) есть в названии
 *       лота (у одно-лотового объявления — и в названии объявления) или в ТЗ;</li>
 *   <li>остальные — TYPE по {@link GroupRule#typeWord}; бренд или модель группы / общий список → NAMED; фразы каталога
 *       в ТЗ → COPIED (ТЗ есть только у разобранных лотов).</li>
 * </ul>
 */
public final class ProductMatcher {

    private ProductMatcher() {}

    public static List<LotMatch> match(CatalogSnapshot s, String announcementName, List<LotInput> lots) {
        List<LotMatch> out = new ArrayList<>();
        String ann = ProductText.normalize(announcementName);
        boolean single = lots.size() == 1;
        for (LotInput lot : lots) {
            String lotText = ProductText.normalize(String.join(" ", Objects.toString(lot.name(), ""), Objects.toString(lot.description(), "")));
            String spec = lot.spec() == null || lot.spec().isBlank() ? null : ProductText.normalize(lot.spec());
            String nameScope = single ? lotText + " | " + ann : lotText;
            for (GroupRule r : s.groups()) {
                if (r.brandGroup()) {
                    List<BrandModels.Code> codes = new ArrayList<>(r.codes());
                    codes.addAll(s.globalCodes());
                    String[] ev = named(codes, nameScope, spec);
                    if (ev != null) {
                        BrandModels.Hit h = BrandModels.first(codes, ev[1].equals("name") ? nameScope : spec);
                        out.add(new LotMatch(lot.lotId(), r.id(), h.label(), ProductMatchLevel.NAMED, ev[0], h.productId(),
                                List.of(), spec != null));
                    }
                    continue;
                }
                String word = r.typeWord(lotText, ann, single);
                if (word == null) continue;
                ProductMatchLevel level = ProductMatchLevel.TYPE;
                String evidence = null;
                Long productId = null;
                List<BrandModels.Code> codes = new ArrayList<>(s.groupCodes(r.id()));
                codes.addAll(s.globalCodes());
                String[] ev = named(codes, nameScope, spec);
                if (ev != null) {
                    BrandModels.Hit h = BrandModels.first(codes, ev[1].equals("name") ? nameScope : spec);
                    level = ProductMatchLevel.NAMED;
                    evidence = ev[0];
                    productId = h.productId();
                }
                if (spec != null) {
                    CatalogPhrases.Copy c = CatalogPhrases.copied(s.groupProducts(r.id()), s.phrases(),
                            ProductText.words(spec).stream().map(ProductText::prefix).toList(), r.stems());
                    if (c != null) {
                        evidence = c.evidence() + (evidence == null ? "" : "; " + evidence);
                        level = ProductMatchLevel.COPIED;
                        productId = c.productId();
                    }
                }
                Set<String> lotPrefixes = new HashSet<>(ProductText.words(nameScope).stream().map(ProductText::prefix).toList());
                if (spec != null) ProductText.words(spec.length() > 3000 ? spec.substring(0, 3000) : spec)
                        .forEach(w -> lotPrefixes.add(ProductText.prefix(w)));
                List<SimilarProductRef> similar = SimilarProducts.top(s.groupProducts(r.id()), lotPrefixes, s.idf());
                out.add(new LotMatch(lot.lotId(), r.id(), word, level, evidence, productId, similar, spec != null));
            }
        }
        return out;
    }

    /** {текст для экрана, где: "name" / "spec"} первого бренда или кода, или null. */
    private static String[] named(List<BrandModels.Code> codes, String nameScope, String spec) {
        BrandModels.Hit h = BrandModels.first(codes, nameScope);
        if (h != null) return new String[] { "в названии лота: " + h.label(), "name" };
        if (spec != null) {
            h = BrandModels.first(codes, spec);
            if (h != null) return new String[] { "в ТЗ: " + h.label(), "spec" };
        }
        return null;
    }
}
