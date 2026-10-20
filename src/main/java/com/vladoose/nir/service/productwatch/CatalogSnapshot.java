package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;
import com.vladoose.nir.service.productwatch.fit.ForeignBrand;
import com.vladoose.nir.service.productwatch.fit.ProductCard;
import com.vladoose.nir.service.productwatch.fit.ProductCards;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.service.productwatch.fit.RegistrationInfo;

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
    private final CatalogPhrases.Rules rules;
    /** Карточки товаров для сверки ТЗ (спека 2026-10-20-product-spec-fit §4): все и по группам. */
    private final Map<Long, ProductCard> cards;
    private final Map<Long, List<ProductCard>> groupCards;
    private final Map<Long, GroupRule> groupById;
    /** Наши бренды и серии; бренды производителей реестра — для «ТЗ под чужой бренд» (§5.5). */
    private final Set<String> ours;
    private final Set<String> knownBrands;

    private CatalogSnapshot(List<GroupRule> groups, List<CatalogProduct> products, Map<Long, List<CatalogProduct>> groupProducts,
                            List<BrandModels.Code> globalCodes, Map<Long, List<BrandModels.Code>> groupCodes,
                            Map<Long, List<CatalogPhrases.Phrase>> phrases, Map<String, Double> idf, CatalogPhrases.Rules rules,
                            Map<Long, ProductCard> cards, Map<Long, List<ProductCard>> groupCards, Map<Long, GroupRule> groupById,
                            Set<String> ours, Set<String> knownBrands) {
        this.groups = groups; this.products = products; this.groupProducts = groupProducts; this.globalCodes = globalCodes;
        this.groupCodes = groupCodes; this.phrases = phrases; this.idf = idf; this.rules = rules;
        this.cards = cards; this.groupCards = groupCards; this.groupById = groupById; this.ours = ours; this.knownBrands = knownBrands;
    }

    public static CatalogSnapshot build(List<ProductGroup> groups, List<SiteProduct> siteProducts) {
        return build(groups, siteProducts, Map.of(), Set.of(), CatalogPhrases.Rules.DEFAULT);
    }

    /** Снимок с правилом фраз — для калибровки (задача 6 плана); в работе — {@link CatalogPhrases.Rules#DEFAULT}. */
    public static CatalogSnapshot build(List<ProductGroup> groups, List<SiteProduct> siteProducts, CatalogPhrases.Rules phraseRules) {
        return build(groups, siteProducts, Map.of(), Set.of(), phraseRules);
    }

    /** Снимок с РУ товаров (по № РУ сайта) и брендами реестра — как в работе ({@link ProductCatalog#refresh()}). */
    public static CatalogSnapshot build(List<ProductGroup> groups, List<SiteProduct> siteProducts,
                                        Map<String, RegistrationInfo> registrations, Set<String> knownBrands) {
        return build(groups, siteProducts, registrations, knownBrands, CatalogPhrases.Rules.DEFAULT);
    }

    public static CatalogSnapshot build(List<ProductGroup> groups, List<SiteProduct> siteProducts,
                                        Map<String, RegistrationInfo> registrations, Set<String> knownBrands,
                                        CatalogPhrases.Rules phraseRules) {
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
        Map<Long, ProductCard> cards = new HashMap<>();
        for (SiteProduct p : siteProducts) {
            if (p.getRemovedAt() != null || p.getId() == null) continue;
            String reg = RegNumbers.normalize(p.getRegistrationCert());
            cards.put(p.getId(), ProductCards.of(p, reg == null ? null : registrations.get(reg)));
        }
        // кандидаты сверки ТЗ — товары того же вида, тем же правилом, что лот: слово группы в НАЗВАНИИ товара, уточнение — в
        // названии или кратком описании, исключения — в названии. По бренду группы («аксион», «фотек») в группу попадает вся
        // продукция бренда, по категории — принадлежности: ЭКГ-ТЗ сверялось бы с облучателем, электрокоагулятор — с кабелем
        // нейтрального электрода; без уточнений бактерицидное ТЗ — с облучателем фототерапии ОФТН
        Map<Long, List<ProductCard>> groupCards = new HashMap<>();
        for (GroupRule r : rules) {
            groupCards.put(r.id(), products.stream().filter(p -> sameKind(r, p))
                    .map(p -> cards.get(p.id())).filter(Objects::nonNull).toList());
        }
        Map<Long, GroupRule> byId = new HashMap<>();
        rules.forEach(r -> byId.put(r.id(), r));
        Set<String> ours = ForeignBrand.ours(products.stream().map(CatalogProduct::name).toList(),
                products.stream().map(CatalogProduct::brand).toList());
        return new CatalogSnapshot(rules, products, byGroup, List.copyOf(global), groupCodes,
                CatalogPhrases.build(products, phraseRules), SimilarProducts.idf(products), phraseRules,
                Map.copyOf(cards), Map.copyOf(groupCards), Map.copyOf(byId), ours, Set.copyOf(knownBrands));
    }

    private static boolean sameKind(GroupRule r, CatalogProduct p) {
        String name = ProductText.normalize(Objects.toString(p.name(), ""));
        if (!MedicalGoodsVocabulary.matchesAny(name, r.stems())) return false;
        String scope = ProductText.normalize(name + " " + Objects.toString(p.shortDescription(), ""));
        if (!r.require().isEmpty() && !MedicalGoodsVocabulary.matchesAny(scope, r.require())) return false;
        return r.exclude().isEmpty() || !MedicalGoodsVocabulary.matchesAny(name, r.exclude());
    }

    public List<GroupRule> groups() { return groups; }
    public List<CatalogProduct> products() { return products; }
    public List<CatalogProduct> groupProducts(long groupId) { return groupProducts.getOrDefault(groupId, List.of()); }
    public List<BrandModels.Code> globalCodes() { return globalCodes; }
    public List<BrandModels.Code> groupCodes(long groupId) { return groupCodes.getOrDefault(groupId, List.of()); }
    public Map<Long, List<CatalogPhrases.Phrase>> phrases() { return phrases; }
    public Map<String, Double> idf() { return idf; }
    public CatalogPhrases.Rules rules() { return rules; }
    public ProductCard card(long productId) { return cards.get(productId); }
    public List<ProductCard> groupCards(long groupId) { return groupCards.getOrDefault(groupId, List.of()); }
    public GroupRule group(long groupId) { return groupById.get(groupId); }
    public Set<String> ours() { return ours; }
    public Set<String> knownBrands() { return knownBrands; }
}
