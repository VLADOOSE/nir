package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;

import java.util.*;

/** Снимок групп и каталога для правил (спека §5): строится при загрузке каталога и сохранении групп. */
public final class CatalogSnapshot {

    private final List<GroupRule> groups;
    private final List<CatalogProduct> products;
    private final Map<Long, List<CatalogProduct>> groupProducts;
    private final List<BrandModels.Code> globalCodes;
    private final Map<Long, List<BrandModels.Code>> groupCodes;
    private final Map<Long, List<CatalogPhrases.Phrase>> phrases;
    private final Map<String, Double> idf;

    private CatalogSnapshot(List<GroupRule> groups, List<CatalogProduct> products, Map<Long, List<CatalogProduct>> groupProducts,
                            List<BrandModels.Code> globalCodes, Map<Long, List<BrandModels.Code>> groupCodes,
                            Map<Long, List<CatalogPhrases.Phrase>> phrases, Map<String, Double> idf) {
        this.groups = groups; this.products = products; this.groupProducts = groupProducts; this.globalCodes = globalCodes;
        this.groupCodes = groupCodes; this.phrases = phrases; this.idf = idf;
    }

    public static CatalogSnapshot build(List<ProductGroup> groups, List<SiteProduct> siteProducts) {
        List<GroupRule> rules = groups.stream().filter(ProductGroup::isActive).map(GroupRule::of).toList();
        List<CatalogProduct> products = siteProducts.stream().filter(p -> p.getRemovedAt() == null)
                .map(p -> new CatalogProduct(p.getId(), p.getName(), p.getBrand(), p.getSlug(), p.getCategory(),
                        p.getShortDescription(), p.getDescription())).toList();
        List<BrandModels.Code> global = new ArrayList<>();
        for (String b : BrandModels.GLOBAL_BRANDS) global.add(new BrandModels.Code(b, BrandModels.compile(b), null));
        Map<Long, List<BrandModels.Code>> productCodes = new HashMap<>();
        for (CatalogProduct p : products) {
            List<BrandModels.Code> codes = new ArrayList<>();
            for (String c : BrandModels.modelCodes(p.name())) {
                BrandModels.Code code = new BrandModels.Code(c, BrandModels.compile(c), p.id());
                codes.add(code);
                if (BrandModels.globalCode(c)) global.add(code);
            }
            productCodes.put(p.id(), codes);
        }
        Map<Long, List<CatalogProduct>> byGroup = new HashMap<>();
        Map<Long, List<BrandModels.Code>> groupCodes = new HashMap<>();
        for (GroupRule r : rules) {
            List<CatalogProduct> mine = new ArrayList<>();
            for (CatalogProduct p : products) {
                String text = ProductText.normalize(String.join(" ", Objects.toString(p.name(), ""),
                        Objects.toString(p.shortDescription(), ""), Objects.toString(p.category(), "")));
                boolean byWord = MedicalGoodsVocabulary.matchesAny(text, r.stems());
                boolean byBrand = BrandModels.first(r.codes(), ProductText.normalize(p.name())) != null;
                if (r.brandGroup() || byWord || byBrand) mine.add(p);
            }
            byGroup.put(r.id(), List.copyOf(mine));
            // коды товаров группы — раньше слов группы: у кода есть товар («в ТЗ: эк12т-01» → ЭК12Т-01), у слова — нет
            List<BrandModels.Code> codes = new ArrayList<>();
            for (CatalogProduct p : mine) codes.addAll(productCodes.getOrDefault(p.id(), List.of()));
            codes.addAll(r.codes());
            groupCodes.put(r.id(), List.copyOf(codes));
        }
        return new CatalogSnapshot(rules, products, byGroup, List.copyOf(global), groupCodes,
                CatalogPhrases.build(products), SimilarProducts.idf(products));
    }

    public List<GroupRule> groups() { return groups; }
    public List<CatalogProduct> products() { return products; }
    public List<CatalogProduct> groupProducts(long groupId) { return groupProducts.getOrDefault(groupId, List.of()); }
    public List<BrandModels.Code> globalCodes() { return globalCodes; }
    public List<BrandModels.Code> groupCodes(long groupId) { return groupCodes.getOrDefault(groupId, List.of()); }
    public Map<Long, List<CatalogPhrases.Phrase>> phrases() { return phrases; }
    public Map<String, Double> idf() { return idf; }
}
