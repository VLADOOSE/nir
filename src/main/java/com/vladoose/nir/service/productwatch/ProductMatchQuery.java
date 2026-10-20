package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.dto.response.LotProductMatchDto;
import com.vladoose.nir.dto.response.OurProductSummary;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.ProductGroupRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Сводки отметок «наш товар» по тендерам — для списка «Тендеры», карточки и уведомлений (спека 2026-10-19-product-watch
 * §9, §12). Без N+1: отметки, группы, товары и лоты — по одному запросу на всю выборку. Тендеры — уже проверенные на рынок.
 */
@Service
public class ProductMatchQuery {

    /** Наш лот: что за лот, уровень, группа и её слово, доказательство, товар каталога, похожие наши товары. */
    public record LotLine(long lotId, String lotName, Integer qty, ProductMatchLevel level, String groupName, String word,
                          String evidence, String productName, String productSlug, List<SimilarProductRef> similar) {}

    /** Итог тендера: наибольший уровень, подпись чипа, группы, наши лоты (сначала точные). */
    public record Summary(long tenderId, ProductMatchLevel level, String label, List<String> groups, List<LotLine> lots) {}

    private final TenderLotProductMatchRepository matches;
    private final ProductGroupRepository groups;
    private final SiteProductRepository products;
    private final TenderLotRepository lots;
    /** Адрес сайта для ссылок на товары, без «/» в конце. */
    private final String siteUrl;

    public ProductMatchQuery(TenderLotProductMatchRepository matches, ProductGroupRepository groups, SiteProductRepository products,
                             TenderLotRepository lots, @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.matches = matches;
        this.groups = groups;
        this.products = products;
        this.lots = lots;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    /** Сводки для списка «Тендеры» — одним заходом на весь список (спека §12.2). */
    @Transactional(readOnly = true)
    public Map<Long, OurProductSummary> ourProduct(Collection<Long> tenderIds) {
        Map<Long, OurProductSummary> out = new HashMap<>();
        summaries(tenderIds).forEach((id, s) -> out.put(id, new OurProductSummary(s.level().name(), s.label(), s.groups())));
        return out;
    }

    /** Отметки лотов одного тендера для карточки (спека §12.3): товар и похожие — ссылками на сайт. */
    @Transactional(readOnly = true)
    public Map<Long, List<LotProductMatchDto>> lotMatches(long tenderId) {
        Summary s = summaries(List.of(tenderId)).get(tenderId);
        if (s == null) return Map.of();
        Map<Long, List<LotProductMatchDto>> out = new HashMap<>();
        for (LotLine l : s.lots()) {
            List<LotProductMatchDto.Similar> sim = l.similar() == null ? List.of() : l.similar().stream()
                    .map(x -> new LotProductMatchDto.Similar(shortName(x.name()), x.brand(), url(x.slug())))
                    .toList();
            out.computeIfAbsent(l.lotId(), k -> new ArrayList<>()).add(new LotProductMatchDto(l.groupName(), l.word(), l.level().name(),
                    l.evidence(), l.productName() == null ? null : shortName(l.productName()), url(l.productSlug()), sim));
        }
        return out;
    }

    private String url(String slug) {
        return slug == null || slug.isBlank() ? null : WestmedLeadMapper.productUrl(siteUrl, slug);
    }

    @Transactional(readOnly = true)
    public Map<Long, Summary> summaries(Collection<Long> tenderIds) {
        if (tenderIds == null || tenderIds.isEmpty()) return Map.of();
        List<TenderLotProductMatch> rows = matches.findByTenderIdIn(tenderIds);
        if (rows.isEmpty()) return Map.of();
        Map<Long, String> groupNames = groups.findAllById(rows.stream().map(TenderLotProductMatch::getGroupId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProductGroup::getId, ProductGroup::getName));
        Set<Long> productIds = rows.stream().map(TenderLotProductMatch::getProductId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SiteProduct> prods = productIds.isEmpty() ? Map.of()
                : products.findAllById(productIds).stream().collect(Collectors.toMap(SiteProduct::getId, p -> p));
        Map<Long, TenderLot> lotById = lots.findAllById(rows.stream().map(TenderLotProductMatch::getLotId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(TenderLot::getId, l -> l));
        Map<Long, List<TenderLotProductMatch>> byTender = rows.stream().collect(Collectors.groupingBy(TenderLotProductMatch::getTenderId));
        Map<Long, Summary> out = new HashMap<>();
        byTender.forEach((tid, list) -> {
            List<LotLine> lines = list.stream()
                    .sorted(Comparator.comparing((TenderLotProductMatch m) -> -m.getLevel().ordinal())
                            .thenComparing(TenderLotProductMatch::getLotId)
                            .thenComparing(TenderLotProductMatch::getGroupId))
                    .map(m -> {
                        TenderLot l = lotById.get(m.getLotId());
                        SiteProduct p = m.getProductId() == null ? null : prods.get(m.getProductId());
                        return new LotLine(m.getLotId(), l == null ? null : l.getEquipName(), l == null ? null : l.getQuantity(),
                                m.getLevel(), groupNames.get(m.getGroupId()), m.getWord(), m.getEvidence(),
                                p == null ? null : p.getName(), p == null ? null : p.getSlug(), m.getSimilar());
                    }).toList();
            ProductMatchLevel level = lines.stream().map(LotLine::level).reduce(null, ProductMatchLevel::max);
            List<String> gs = lines.stream().map(LotLine::groupName).filter(Objects::nonNull).distinct().toList();
            out.put(tid, new Summary(tid, level, label(level, lines), gs, lines));
        });
        return out;
    }

    /** Подпись чипа: «★ наш товар: АСД-ФОТЕК» / «★ наш товар: Фотек» / «🏷 наш вид: отсасыватель». */
    static String label(ProductMatchLevel level, List<LotLine> lines) {
        LotLine top = lines.get(0);
        if (level != null && level.exact()) return "★ наш товар: " + what(top);
        return "🏷 наш вид: " + top.word();
    }

    /** Строка для «🏥 Новый тендер» (спека §9): самый точный наш лот. */
    public static String telegramLine(Summary s) {
        LotLine top = s.lots().get(0);
        return switch (s.level()) {
            case COPIED -> "★ ТЗ списано с нашего товара: " + shortName(Objects.toString(top.productName(), top.word()));
            case NAMED -> "★ Назван наш товар: " + what(top);
            case TYPE -> "🏷 Наш вид: " + top.word() + similarTail(top);
        };
    }

    /** Что именно наше: товар каталога, иначе то, что найдено (после «в названии лота: » / «в ТЗ: »), иначе слово группы. */
    static String what(LotLine l) {
        if (l.productName() != null) return shortName(l.productName());
        String ev = l.evidence();
        if (ev != null && ev.contains(": ")) {
            String found = ev.substring(ev.indexOf(": ") + 2);
            int semi = found.indexOf("; ");
            return semi > 0 ? found.substring(0, semi) : found;
        }
        return l.word();
    }

    static String similarTail(LotLine l) {
        if (l.similar() == null || l.similar().isEmpty()) return "";
        return " (похоже: " + l.similar().stream().limit(2).map(x -> shortName(x.name())).collect(Collectors.joining(", ")) + ")";
    }

    /** Название товара без хвоста «. Набор с …» — для строки и чипа. */
    public static String shortName(String name) {
        if (name == null) return "";
        int dot = name.indexOf(". ");
        String s = dot > 0 ? name.substring(0, dot) : name;
        return s.length() <= 80 ? s : s.substring(0, 79) + "…";
    }
}
