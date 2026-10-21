package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SimilarProductRef;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;
import com.vladoose.nir.util.TechSpecExtractor;

import java.util.*;

/**
 * Лоты объявления → отметки «наш товар» (спека §5). Чистая функция над снимком: без базы и сети.
 * <ul>
 *   <li>группа «Наши бренды и модели» — сразу NAMED, если её слово (или общий бренд / код модели) есть в названии
 *       лота (у одно-лотового объявления — и в названии объявления) или в ТЗ;</li>
 *   <li>остальные — TYPE по {@link GroupRule#typeWord}; бренд или модель группы / общий список → NAMED; фразы каталога
 *       в ТЗ → COPIED (ТЗ есть только у разобранных лотов).</li>
 *   <li>работа или услуга по названию лота ({@link #serviceLot}) — без отметок.</li>
 * </ul>
 */
public final class ProductMatcher {

    private ProductMatcher() {}

    public static List<LotMatch> match(CatalogSnapshot s, String announcementName, List<LotInput> lots) {
        List<LotMatch> out = new ArrayList<>();
        String ann = ProductText.normalize(announcementName);
        boolean single = lots.size() == 1;
        for (LotInput lot : lots) {
            if (serviceLot(lot.name(), lot.description())) continue;
            String lotName = ProductText.normalize(lot.name());
            String lotText = ProductText.normalize(String.join(" ", Objects.toString(lot.name(), ""), Objects.toString(lot.description(), "")));
            String spec = lot.spec() == null || lot.spec().isBlank() ? null : ProductText.normalize(lot.spec());
            // «списано» — по телу ТЗ: закупочная шапка goszakup (название закупки, лота, места, сроки) — не текст товара
            String body = lot.spec() == null ? null : TechSpecExtractor.characteristics(lot.spec());
            String specBody = body == null || body.isBlank() ? spec : ProductText.normalize(body);
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
                String word = r.typeWord(lotName, lotText, ann, single);
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
                            ProductText.words(specBody).stream().map(ProductText::prefix).toList(), r.stems(), ownWords(r), s.rules());
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

    /**
     * Работа или услуга по названию лота — не товар ни одной группы, и бренда тоже («Работы по ремонту/модернизации
     * медицинского оборудования», «Услуги по техническому обслуживанию аппаратов ФОТЕК»). Маркеры работ и услуг импорта
     * ({@link MedicalGoodsVocabulary#WORKS_SERVICES}; слабые — нет: «Светильник для осмотра» — товар; сварочное и дроны
     * отсекают исключения групп); тара для сбора медотходов — товар, как у импорта.
     */
    public static boolean serviceLot(String name, String description) {
        String n = Objects.toString(name, "");
        return MedicalGoodsVocabulary.hasWorksServices(n)
                && !MedicalGoodsVocabulary.isMedicalWasteContainer(n, Objects.toString(description, ""));
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

    /** Начала слов группы (слова поиска и уточнения) — для правила фраз (§5.4): общие для любого ТЗ её вида. */
    static Set<String> ownWords(GroupRule r) {
        Set<String> out = new HashSet<>();
        for (String w : r.searchWords()) ProductText.words(ProductText.normalize(w)).forEach(x -> out.add(ProductText.prefix(x)));
        for (String w : r.require()) ProductText.words(w).forEach(x -> out.add(ProductText.prefix(x)));
        return out;
    }
}
